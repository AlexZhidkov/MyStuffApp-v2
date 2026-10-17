import { createHash, randomBytes } from "node:crypto";
import {
  InvalidClientMetadataError, InvalidGrantError, InvalidRequestError,
  InvalidScopeError, InvalidTokenError,
} from "@modelcontextprotocol/sdk/server/auth/errors.js";
import { activeHouseholdIdForMember } from "./member-authorization.js";
import { isDocumentId } from "./mcp-inventory.js";
import { readConnectionGenerations, readMcpConnection } from "./mcp-connections.js";

export const INVENTORY_READ_SCOPE = "inventory:read";
const APPROVAL_LIFETIME_MS = 10 * 60 * 1000;
const ACCESS_LIFETIME_SECONDS = 3600;
const opaque = () => randomBytes(32).toString("base64url");
const digest = (value) => createHash("sha256").update(value).digest("hex");
const omitUndefinedProperties = (value) => JSON.parse(JSON.stringify(value));

export function createMcpOAuth({ database, authentication, origin, now = Date.now }) {
  const resource = `${origin}/mcp`;
  const reference = (collection, secret) => database.doc(`${collection}/${digest(secret)}`);
  const pendingReference = (secret) => reference("mcpApprovals", secret);

  function issueTokens(transaction, connectionId) {
    const token = opaque();
    const refreshToken = opaque();
    transaction.create(reference("mcpAccessTokens", token), {
      connectionId, expiresAt: now() + ACCESS_LIFETIME_SECONDS * 1000, resource,
    });
    transaction.create(reference("mcpRefreshTokens", refreshToken), { connectionId, resource, usedAt: null });
    return { access_token: token, refresh_token: refreshToken, token_type: "Bearer",
      expires_in: ACCESS_LIFETIME_SECONDS, scope: INVENTORY_READ_SCOPE };
  }

  async function currentConnection(transaction, connectionId) {
    if (!isDocumentId(connectionId)) throw new InvalidTokenError("Invalid App Connection.");
    const connection = await readMcpConnection(database, transaction, connectionId, now());
    if (!connection) {
      throw new InvalidTokenError("App Connection or Household Access is unavailable.");
    }
    return connection;
  }

  function validateResource(requested) {
    // Older clients omit resource. Such tokens are still issued only for this
    // server; an explicitly different audience is never accepted.
    if (requested !== undefined && requested.href !== resource) {
      throw new InvalidRequestError("Unknown resource.");
    }
  }

  function callback(pending, parameters) {
    const url = new URL(pending.redirectUri);
    for (const [key, value] of Object.entries(parameters)) url.searchParams.set(key, value);
    if (pending.state !== undefined) url.searchParams.set("state", pending.state);
    url.searchParams.set("iss", `${origin}/`);
    return url.href;
  }

  async function browserApproval(request, transaction) {
    const requestId = request.body?.requestId ?? request.query?.request;
    if (typeof requestId !== "string" || !/^[\w-]{43}$/.test(requestId)) {
      throw new InvalidRequestError("Invalid approval request.");
    }
    const cookie = request.headers.cookie?.split(";").map((part) => part.trim())
      .find((part) => part.startsWith("__session="))?.split("=")[1];
    const ref = pendingReference(requestId);
    const snapshot = await (transaction ? transaction.get(ref) : ref.get());
    const pending = snapshot.data();
    if (!pending || pending.expiresAt <= now() || !cookie ||
        digest(cookie) !== pending.browserSecretHash) {
      throw new InvalidRequestError("Approval expired or belongs to another browser.");
    }
    return { pending, ref };
  }

  async function member(request) {
    let identity;
    try {
      const token = request.headers.authorization?.match(/^Bearer (\S+)$/i)?.[1];
      if (!token) throw new Error();
      identity = await authentication.verifyIdToken(token, true);
    } catch {
      throw new InvalidTokenError("Sign in with the Google Account you already use for MyStuff.");
    }
    if (identity.firebase?.sign_in_provider !== "google.com" || !identity.email_verified ||
        !isDocumentId(identity.uid)) {
      throw new InvalidTokenError("A verified Google sign-in is required.");
    }
    const householdId = await activeHouseholdIdForMember(database, identity.uid);
    if (!isDocumentId(householdId)) {
      throw new InvalidTokenError("An existing MyStuff Account with active Household Access is required.");
    }
    return { memberId: identity.uid, householdId };
  }

  const provider = {
    clientsStore: {
      async getClient(clientId) {
        if (!isDocumentId(clientId)) return undefined;
        return (await database.doc(`mcpClients/${clientId}`).get()).data();
      },
      async registerClient(client) {
        for (const redirect of client.redirect_uris) {
          const url = new URL(redirect);
          const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
          if (url.hash || url.username || url.password ||
              (url.protocol !== "https:" && !(loopback && url.protocol === "http:"))) {
            throw new InvalidClientMetadataError("Use HTTPS or a loopback HTTP callback.");
          }
        }
        if (client.redirect_uris.length === 0) {
          throw new InvalidClientMetadataError("A callback is required.");
        }
        const registered = omitUndefinedProperties(client);
        await database.doc(`mcpClients/${registered.client_id}`).create(registered);
        return registered;
      },
    },
    async authorize(client, params, response) {
      validateResource(params.resource);
      if (params.scopes?.some((scope) => scope !== INVENTORY_READ_SCOPE)) {
        throw new InvalidScopeError("Only read-only Inventory access is supported.");
      }
      if (!/^[\w-]{43}$/.test(params.codeChallenge)) {
        throw new InvalidRequestError("S256 PKCE is required.");
      }
      const requestId = opaque();
      const browserSecret = opaque();
      await pendingReference(requestId).create(omitUndefinedProperties({
        clientId: client.client_id, clientName: client.client_name ?? "External application",
        redirectUri: params.redirectUri, state: params.state,
        codeChallenge: params.codeChallenge, browserSecretHash: digest(browserSecret),
        expiresAt: now() + APPROVAL_LIFETIME_MS,
      }));
      // Firebase Hosting forwards only the __session cookie to HTTP functions.
      response.cookie("__session", browserSecret, {
        httpOnly: true, secure: origin.startsWith("https:"), sameSite: "lax",
        path: "/oauth", maxAge: APPROVAL_LIFETIME_MS,
      });
      response.redirect(302, `${origin}/oauth/approve?request=${requestId}`);
    },
    async challengeForAuthorizationCode(client, code) {
      const grant = (await reference("mcpCodes", code).get()).data();
      if (!grant || grant.clientId !== client.client_id || grant.expiresAt <= now()) {
        throw new InvalidGrantError("Invalid or expired authorization code.");
      }
      return grant.codeChallenge;
    },
    async exchangeAuthorizationCode(client, code, _verifier, redirectUri, requestedResource) {
      validateResource(requestedResource);
      return database.runTransaction(async (transaction) => {
        const ref = reference("mcpCodes", code);
        const grant = (await transaction.get(ref)).data();
        if (!grant || grant.clientId !== client.client_id || grant.expiresAt <= now() ||
            redirectUri !== grant.redirectUri) {
          throw new InvalidGrantError("Invalid or expired authorization code.");
        }
        // Recheck access at token exchange as well as approval and every read.
        if (!await readMcpConnection(database, transaction, grant.connectionId, now())) {
          throw new InvalidGrantError("Fresh approval is required.");
        }
        transaction.delete(ref);
        return issueTokens(transaction, grant.connectionId);
      });
    },
    async exchangeRefreshToken(client, refreshToken, scopes, requestedResource) {
      validateResource(requestedResource);
      if (scopes?.some((scope) => scope !== INVENTORY_READ_SCOPE)) {
        throw new InvalidScopeError("Only read-only Inventory access is supported.");
      }
      if (!/^[\w-]{43}$/.test(refreshToken)) throw new InvalidGrantError("Invalid refresh token.");
      const tokens = await database.runTransaction(async (transaction) => {
        const ref = reference("mcpRefreshTokens", refreshToken);
        const grant = (await transaction.get(ref)).data();
        if (!grant || grant.resource !== resource) throw new InvalidGrantError("Invalid refresh token.");
        const connection = await readMcpConnection(database, transaction, grant.connectionId, now());
        if (!connection) throw new InvalidGrantError("Fresh approval is required.");
        if (connection.clientId !== client.client_id) throw new InvalidGrantError("Invalid refresh token.");
        if (grant.usedAt !== null) {
          transaction.update(database.doc(`appConnections/${grant.connectionId}`), { revokedAt: now() });
          return null;
        }
        transaction.update(ref, { usedAt: now() });
        return issueTokens(transaction, grant.connectionId);
      });
      // Throw after commit so replay invalidation is not rolled back.
      if (!tokens) throw new InvalidGrantError("Refresh token was reused. Fresh approval is required.");
      return tokens;
    },
    async verifyAccessToken(token) {
      if (!/^[\w-]{43}$/.test(token)) throw new InvalidTokenError("Invalid access token.");
      return database.runTransaction(async (transaction) => {
        const record = (await transaction.get(reference("mcpAccessTokens", token))).data();
        if (!record || record.expiresAt <= now() || record.resource !== resource) {
          throw new InvalidTokenError("Invalid or expired access token.");
        }
        const connection = await currentConnection(transaction, record.connectionId);
        transaction.update(database.doc(`appConnections/${record.connectionId}`), { lastUsedAt: now() });
        return {
          token, clientId: connection.clientId, scopes: [INVENTORY_READ_SCOPE],
          expiresAt: record.expiresAt / 1000, resource: new URL(resource),
          extra: { connectionId: record.connectionId, memberId: connection.memberId,
            householdId: connection.householdId },
        };
      });
    },
  };

  return {
    provider,
    async preview(request) {
      const identity = await member(request);
      const { ref, pending } = await browserApproval(request);
      const household = (await database.doc(`households/${identity.householdId}`).get()).data();
      if (typeof household?.name !== "string") {
        throw new InvalidRequestError("Household data is unavailable.");
      }
      await ref.update(identity);
      return { householdId: identity.householdId, householdName: household.name,
        clientName: pending.clientName, scope: INVENTORY_READ_SCOPE };
    },
    async approve(request) {
      const identity = await member(request);
      const code = opaque();
      const connectionId = opaque();
      return database.runTransaction(async (transaction) => {
        const { ref, pending } = await browserApproval(request, transaction);
        if (pending.memberId !== identity.memberId || pending.householdId !== identity.householdId ||
            request.body.householdId !== pending.householdId) {
          throw new InvalidRequestError("Review and approve the named Household first.");
        }
        const reader = { doc: (path) => ({ get: () => transaction.get(database.doc(path)) }) };
        if (await activeHouseholdIdForMember(reader, identity.memberId) !== identity.householdId) {
          throw new InvalidTokenError("Household Access is unavailable.");
        }
        const generations = await readConnectionGenerations(
          database, transaction, identity.memberId, identity.householdId,
        );
        transaction.create(database.doc(`appConnections/${connectionId}`), {
          ...identity, ...generations, clientId: pending.clientId, clientName: pending.clientName,
          scope: INVENTORY_READ_SCOPE, createdAt: now(), lastUsedAt: now(), revokedAt: null,
        });
        transaction.create(reference("mcpCodes", code), {
          clientId: pending.clientId, connectionId, codeChallenge: pending.codeChallenge,
          redirectUri: pending.redirectUri, expiresAt: now() + 60_000,
        });
        transaction.delete(ref);
        return { redirect: callback(pending, { code }) };
      });
    },
    async cancel(request) {
      return database.runTransaction(async (transaction) => {
        const { ref, pending } = await browserApproval(request, transaction);
        transaction.delete(ref);
        return { redirect: callback(pending, { error: "access_denied" }) };
      });
    },
  };
}
