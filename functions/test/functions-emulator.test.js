import { isolatedGeminiCli } from "../test-support/gemini-cli.js";
import assert from "node:assert/strict";
import express from "express";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { once } from "node:events";
import { onCall } from "firebase-functions/v2/https";
import { createAppConnectionHandlers } from "../src/app-connections.js";
import { after, before, test } from "node:test";
import { deleteApp, initializeApp } from "firebase/app";
import {
  connectAuthEmulator,
  getAuth,
  signInWithEmailAndPassword,
  signInAnonymously,
} from "firebase/auth";
import {
  connectFirestoreEmulator,
  doc,
  getDoc,
  getFirestore as getClientFirestore,
} from "firebase/firestore";
import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
} from "firebase/functions";
import { getApps, initializeApp as initializeAdminApp } from "firebase-admin/app";
import { getAuth as getAdminAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage as getAdminStorage } from "firebase-admin/storage";

const projectId = "demo-mystuff-search";
const emulatorAvailable =
  process.env.FIRESTORE_EMULATOR_HOST !== undefined &&
  process.env.FIREBASE_AUTH_EMULATOR_HOST !== undefined;
const householdId = "e2e-search-household";
const otherHouseholdId = "e2e-other-household";
const itemId = "e2e-clock";
const otherItemId = "e2e-private-clock";
const moveSourceId = "e2e-move-source";
const moveChildId = "e2e-move-child";
const moveTargetId = "e2e-move-target";
let clientApp;
let database;
let memberId;

before(async () => {
  if (!emulatorAvailable) return;
  if (getApps().length === 0) initializeAdminApp({ projectId });
  database = getFirestore();

  clientApp = initializeApp(
    { projectId, apiKey: "demo-key", appId: "demo-app" },
    "semantic-search-e2e",
  );
  const auth = getAuth(clientApp);
  connectAuthEmulator(auth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, {
    disableWarnings: true,
  });
  memberId = (await signInAnonymously(auth)).user.uid;
  const [host, port] = (process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001").split(":");
  connectFunctionsEmulator(getFunctions(clientApp, "australia-southeast1"), host, Number(port));
});

after(async () => {
  if (!emulatorAvailable) return;
  await database.doc(`memberships/${memberId}`).delete();
  await database.doc(`households/${householdId}/items/${itemId}`).delete();
  await database.doc(`households/${householdId}/searchIndex/${itemId}`).delete();
  await database.doc(`households/${otherHouseholdId}/items/${otherItemId}`).delete();
  await database.doc(`households/${otherHouseholdId}/searchIndex/${otherItemId}`).delete();
  await database.doc(`households/${householdId}/items/${householdId}`).delete();
  await database.doc(`households/${householdId}/items/${moveSourceId}`).delete();
  await database.doc(`households/${householdId}/items/${moveChildId}`).delete();
  await database.doc(`households/${householdId}/items/${moveTargetId}`).delete();
  await database.doc(`households/${householdId}`).delete();
  await database.doc(`households/${otherHouseholdId}`).delete();
  await deleteApp(clientApp);
});

test(
  "Item writes refresh the index and the authenticated callable stays Household-scoped",
  { skip: !emulatorAvailable, timeout: 20_000 },
  async () => {
    await database.doc(`households/${householdId}`).set({
      name: "Search Household",
      ownerMemberId: memberId,
    });
    await database.doc(`households/${otherHouseholdId}`).set({
      name: "Other Household",
      ownerMemberId: "e2e-other-owner",
    });
    await database.doc(`memberships/${memberId}`).set({ householdId });
    await database.doc(`households/${householdId}/items/${itemId}`).set({
      name: "Mantel clock",
      parentItemId: householdId,
      tags: ["timepiece"],
      description: "A decorative clock used to tell the time.",
    });
    await database.doc(`households/${otherHouseholdId}/items/${otherItemId}`).set({
      name: "Mantel clock",
      parentItemId: otherHouseholdId,
      tags: ["timepiece"],
      description: "A private decorative clock used to tell the time.",
    });

    let originalSourceHash;
    await waitFor(async () => {
      const index = await database
        .doc(`households/${householdId}/searchIndex/${itemId}`)
        .get();
      originalSourceHash = index.data()?.sourceHash;
      const otherIndex = await database
        .doc(`households/${otherHouseholdId}/searchIndex/${otherItemId}`)
        .get();
      return typeof originalSourceHash === "string" && otherIndex.exists;
    });

    const functions = getFunctions(clientApp, "australia-southeast1");
    const result = await httpsCallable(functions, "searchInventory")({ query: "clock" });

    assert.deepEqual(result.data, { itemIds: [itemId] });

    await database.doc(`households/${householdId}/items/${itemId}`).update({
      description: "An updated clock description.",
    });
    await waitFor(async () => {
      const updatedIndex = await database
        .doc(`households/${householdId}/searchIndex/${itemId}`)
        .get();
      return updatedIndex.data()?.sourceHash !== originalSourceHash;
    });

    await database.doc(`households/${householdId}/items/${itemId}`).delete();
    await waitFor(async () => {
      const deletedIndex = await database
        .doc(`households/${householdId}/searchIndex/${itemId}`)
        .get();
      return !deletedIndex.exists;
    });
  },
);

test(
  "an authenticated Member moves an Item subtree transactionally",
  { skip: !emulatorAvailable, timeout: 20_000 },
  async () => {
    await database.doc(`households/${householdId}`).set({
      name: "Search Household",
      ownerMemberId: memberId,
    });
    await database.doc(`memberships/${memberId}`).set({ householdId });
    await database.doc(`households/${householdId}/items/${householdId}`).set({
      householdId,
      parentItemId: null,
    });
    await database.doc(`households/${householdId}/items/${moveSourceId}`).set({
      householdId,
      parentItemId: householdId,
      name: "Source",
      tags: [],
      description: "",
    });
    await database.doc(`households/${householdId}/items/${moveChildId}`).set({
      householdId,
      parentItemId: moveSourceId,
      name: "Child",
      tags: [],
      description: "",
    });
    await database.doc(`households/${householdId}/items/${moveTargetId}`).set({
      householdId,
      parentItemId: householdId,
      name: "Target",
      tags: [],
      description: "",
    });

    const functions = getFunctions(clientApp, "australia-southeast1");
    const move = httpsCallable(functions, "moveInventoryItem");
    const result = await move({
      householdId,
      itemId: moveSourceId,
      newParentItemId: moveTargetId,
    });

    assert.deepEqual(result.data, {
      itemId: moveSourceId,
      parentItemId: moveTargetId,
    });
    const source = await database
      .doc(`households/${householdId}/items/${moveSourceId}`)
      .get();
    const child = await database
      .doc(`households/${householdId}/items/${moveChildId}`)
      .get();
    assert.equal(source.data()?.parentItemId, moveTargetId);
    assert.equal(source.data()?.name, "Source");
    assert.equal(source.data()?.updatedById, memberId);
    assert.equal(child.data()?.parentItemId, moveSourceId);
  },
);

test(
  "Account Deletion revokes access anonymizes shared attribution and deletes Authentication",
  { skip: !emulatorAvailable, timeout: 30_000 },
  async () => {
    const deletionHouseholdId = "e2e-deletion-household";
    const deletionMemberId = "e2e-deletion-member";
    const deletionEmail = "delete-me@example.com";
    const deletionItemId = "e2e-deletion-item";
    const adminAuthentication = getAdminAuth();
    await adminAuthentication.createUser({
      uid: deletionMemberId,
      email: deletionEmail,
      emailVerified: true,
      password: "deletion-test-password",
    });
    const deletionApp = initializeApp(
      { projectId, apiKey: "demo-key", appId: "deletion-demo-app" },
      "account-deletion-e2e",
    );
    const deletionAuthentication = getAuth(deletionApp);
    connectAuthEmulator(
      deletionAuthentication,
      `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`,
      { disableWarnings: true },
    );
    await signInWithEmailAndPassword(
      deletionAuthentication,
      deletionEmail,
      "deletion-test-password",
    );

    await database.doc(`households/${deletionHouseholdId}`).set({
      name: "Deletion Home",
      ownerMemberId: "e2e-deletion-owner",
      ownerEmail: "owner@example.com",
      useTags: false,
    });
    await database.doc("memberships/e2e-deletion-owner").set({
      householdId: deletionHouseholdId,
      role: "owner",
    });
    await database.doc(`memberships/${deletionMemberId}`).set({
      householdId: deletionHouseholdId,
      role: "member",
    });
    await database.doc(
      `households/${deletionHouseholdId}/access/${deletionEmail}`,
    ).set({
      householdId: deletionHouseholdId,
      email: deletionEmail,
      memberId: deletionMemberId,
      memberDisplayName: "Delete Me",
      memberEmail: deletionEmail,
    });
    await database.doc(
      `households/${deletionHouseholdId}/items/${deletionItemId}`,
    ).set({
      householdId: deletionHouseholdId,
      name: "Shared Item",
      parentItemId: deletionHouseholdId,
      tags: [],
      description: "Shared description",
      createdById: deletionMemberId,
      createdByDisplayName: "Delete Me",
      updatedById: deletionMemberId,
      updatedByDisplayName: "Delete Me",
    });

    const deletionFunctions = getFunctions(deletionApp, "australia-southeast1");
    const [host, port] = (process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001")
      .split(":");
    connectFunctionsEmulator(deletionFunctions, host, Number(port));
    const preview = await httpsCallable(
      deletionFunctions,
      "previewAccountDeletion",
    )();
    assert.deepEqual(preview.data, {
      deletesHousehold: false,
      householdName: null,
      memberCount: 0,
      itemCount: 0,
    });
    await httpsCallable(deletionFunctions, "requestAccountDeletion")();

    await waitFor(async () => {
      const [job, membership] = await Promise.all([
        database.doc(`accountDeletionJobs/${deletionMemberId}`).get(),
        database.doc(`memberships/${deletionMemberId}`).get(),
      ]);
      const authenticationDeleted = await adminAuthentication
        .getUser(deletionMemberId)
        .then(() => false, (error) => error.code === "auth/user-not-found");
      return !job.exists && !membership.exists && authenticationDeleted;
    });

    const [access, item] = await Promise.all([
      database.doc(
        `households/${deletionHouseholdId}/access/${deletionEmail}`,
      ).get(),
      database.doc(
        `households/${deletionHouseholdId}/items/${deletionItemId}`,
      ).get(),
    ]);
    assert.equal(access.exists, false);
    assert.equal(item.data()?.createdById, "former-member");
    assert.equal(item.data()?.createdByDisplayName, "Former member");
    assert.equal(item.data()?.updatedById, "former-member");
    assert.equal(item.data()?.updatedByDisplayName, "Former member");

    await database.recursiveDelete(database.doc(`households/${deletionHouseholdId}`));
    await database.doc("memberships/e2e-deletion-owner").delete();
    await deleteApp(deletionApp);
  },
);

test(
  "Owner Account Deletion reports visible counts and deletes the shared Household",
  { skip: !emulatorAvailable, timeout: 30_000 },
  async () => {
    const ownerId = "e2e-owner-deletion-member";
    const otherMemberId = "e2e-owner-deletion-other-member";
    const ownerEmail = "delete-owner@example.com";
    const deletionHouseholdId = "e2e-owner-deletion-household";
    const adminAuthentication = getAdminAuth();
    await adminAuthentication.createUser({
      uid: ownerId,
      email: ownerEmail,
      emailVerified: true,
      password: "owner-deletion-test-password",
    });
    const deletionApp = initializeApp(
      { projectId, apiKey: "demo-key", appId: "owner-deletion-demo-app" },
      "owner-account-deletion-e2e",
    );
    const deletionAuthentication = getAuth(deletionApp);
    connectAuthEmulator(
      deletionAuthentication,
      `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`,
      { disableWarnings: true },
    );
    await signInWithEmailAndPassword(
      deletionAuthentication,
      ownerEmail,
      "owner-deletion-test-password",
    );

    await database.doc(`households/${deletionHouseholdId}`).set({
      name: "Owner Deletion Home",
      ownerMemberId: ownerId,
      ownerEmail,
      useTags: false,
    });
    await database.doc(`memberships/${ownerId}`).set({
      householdId: deletionHouseholdId,
      role: "owner",
    });
    await database.doc(`memberships/${otherMemberId}`).set({
      householdId: deletionHouseholdId,
      role: "member",
    });
    await database.doc(
      `households/${deletionHouseholdId}/items/${deletionHouseholdId}`,
    ).set({ householdId: deletionHouseholdId, parentItemId: null });
    await database.doc(
      `households/${deletionHouseholdId}/items/visible-item`,
    ).set({ householdId: deletionHouseholdId, parentItemId: deletionHouseholdId });
    const photo = getAdminStorage()
      .bucket(`${projectId}.appspot.com`)
      .file(`households/${deletionHouseholdId}/items/visible-item.webp`);
    await photo.save(Buffer.from("test-photo"), { contentType: "image/webp" });

    const deletionFunctions = getFunctions(deletionApp, "australia-southeast1");
    const [host, port] = (process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001")
      .split(":");
    connectFunctionsEmulator(deletionFunctions, host, Number(port));
    const preview = await httpsCallable(
      deletionFunctions,
      "previewAccountDeletion",
    )();
    assert.deepEqual(preview.data, {
      deletesHousehold: true,
      householdName: "Owner Deletion Home",
      memberCount: 2,
      itemCount: 1,
    });
    await httpsCallable(deletionFunctions, "requestAccountDeletion")({
      householdName: "Owner Deletion Home",
    });

    await waitFor(async () => {
      const [job, household, ownerMembership, otherMembership] = await Promise.all([
        database.doc(`accountDeletionJobs/${ownerId}`).get(),
        database.doc(`households/${deletionHouseholdId}`).get(),
        database.doc(`memberships/${ownerId}`).get(),
        database.doc(`memberships/${otherMemberId}`).get(),
      ]);
      const authenticationDeleted = await adminAuthentication
        .getUser(ownerId)
        .then(() => false, (error) => error.code === "auth/user-not-found");
      const [photoExists] = await photo.exists();
      return !job.exists && !household.exists && !ownerMembership.exists &&
        !otherMembership.exists && authenticationDeleted && !photoExists;
    });

    await deleteApp(deletionApp);
  },
);

async function waitFor(predicate) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.fail("Timed out waiting for the expected emulator state.");
}

test("Connected apps lists only the signed-in Member's individual approvals", {
  skip: !emulatorAvailable, timeout: 20_000,
}, async (t) => {
  const connectionId = "e2e-app-connection-list";
  const foreignId = "e2e-app-connection-foreign";
  const legacyId = "e2e-app-connection-legacy";
  t.after(async () => {
    await database.doc(`appConnections/${connectionId}`).delete();
    await database.doc(`appConnections/${foreignId}`).delete();
    await database.doc(`appConnections/${legacyId}`).delete();
    await database.doc("households/legacy-approved-home").delete();
  });
  await database.doc(`appConnections/${connectionId}`).set({
    memberId, householdId: "approved-home", householdName: "Approved Home",
    clientName: "Gemini CLI", clientId: "test-client", scope: "inventory:read",
    createdAt: 1000, lastUsedAt: 2000, revokedAt: null,
  });
  await database.doc("households/legacy-approved-home").set({ name: "Legacy Home" });
  await database.doc(`appConnections/${legacyId}`).set({
    memberId, householdId: "legacy-approved-home", clientName: "External application",
    createdAt: 500, lastUsedAt: 500, revokedAt: 600,
  });
  await database.doc(`appConnections/${foreignId}`).set({
    memberId: "another-member", householdId: "private-home", householdName: "Private Home",
    clientName: "Private application", createdAt: 1000, revokedAt: null,
  });
  const functions = getFunctions(clientApp, "australia-southeast1");
  const result = await httpsCallable(functions, "listAppConnections")({ memberId: "another-member" });
  assert.deepEqual(result.data, { connections: [{
    id: connectionId, householdId: "approved-home", householdName: "Approved Home",
    clientName: "Gemini CLI", createdAt: 1000, lastUsedAt: 2000, revokedAt: null,
  }, {
    id: legacyId, householdId: "legacy-approved-home", householdName: "Legacy Home",
    clientName: "External application", createdAt: 500, lastUsedAt: 500, revokedAt: 600,
  }] });
  const unauthenticated = await fetch(
    `http://${process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001"}/${projectId}/australia-southeast1/listAppConnections`,
    { method: "POST", headers: { "Content-Type": "application/json" }, body: '{"data":{}}' },
  );
  assert.equal(unauthenticated.status, 401);
});


test("revoking one App Connection stops issued credentials and renewal while another and MyStuff sign-in remain usable", {
  skip: !emulatorAvailable, timeout: 30_000,
}, async (t) => {
  const base = `http://${process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001"}/${projectId}/australia-southeast1`;
  const mcp = `${base}/mcp`;
  const metadata = await (await fetch(`${mcp}/.well-known/oauth-authorization-server`)).json();
  const resource = `${metadata.issuer}mcp`;
  const now = Date.now();
  const digest = (value) => createHash("sha256").update(value).digest("hex");
  const tokens = ["a".repeat(43), "b".repeat(43)];
  const refresh = ["c".repeat(43), "d".repeat(43)];
  const paths = [];
  async function seed(path, data) {
    paths.push(path);
    await database.doc(path).set(data);
  }
  t.after(async () => { await Promise.all(paths.map((path) => database.doc(path).delete())); });
  await seed(`households/${householdId}`, { name: "Approved Home", ownerMemberId: memberId });
  await seed(`memberships/${memberId}`, { householdId });
  await seed(`households/${householdId}/items/${householdId}`, {
    householdId, name: "Approved Home", parentItemId: null, description: null,
  });
  await seed("mcpClients/e2e-revoke-client", {
    client_id: "e2e-revoke-client", redirect_uris: ["http://localhost/callback"],
    token_endpoint_auth_method: "none", grant_types: ["authorization_code", "refresh_token"],
  });
  for (let index = 0; index < 2; index++) {
    const connectionId = `e2e-revoke-${index}`;
    await seed(`appConnections/${connectionId}`, {
      memberId, householdId, householdName: "Approved Home", clientName: "Gemini CLI",
      clientId: "e2e-revoke-client", scope: "inventory:read", createdAt: now, lastUsedAt: now, revokedAt: null,
    });
    await seed(`mcpAccessTokens/${digest(tokens[index])}`, { connectionId, resource, expiresAt: now + 3600000 });
    await seed(`mcpRefreshTokens/${digest(refresh[index])}`, { connectionId, resource, usedAt: null });
  }
  const item = async (token) => fetch(`${mcp}/mcp`, {
    method: "POST", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json",
      Accept: "application/json, text/event-stream" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call",
      params: { name: "get_item", arguments: { itemId: householdId } } }),
  });
  const renew = async (token) => fetch(`${mcp}/token`, {
    method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: "e2e-revoke-client", grant_type: "refresh_token", refresh_token: token }),
  });
  assert.equal((await item(tokens[0])).status, 200);
  assert.equal((await item(tokens[1])).status, 200);
  const gemini = process.env.GEMINI_CLI_BUNDLE
    ? await geminiConnections(t, `${mcp}/mcp`, `${mcp}/token`, tokens, refresh)
    : null;
  if (gemini) {
    const before = await gemini.list();
    assert.match(before, /mystuff-revoke[^\n]*Connected/);
    assert.match(before, /mystuff-independent[^\n]*Connected/);
  }
  const functions = getFunctions(clientApp, "australia-southeast1");
  const revoke = httpsCallable(functions, "revokeAppConnection");
  assert.deepEqual((await revoke({ connectionId: "e2e-revoke-0" })).data, { revoked: true });
  if (gemini) {
    const after = await gemini.list();
    assert.doesNotMatch(after, /mystuff-revoke[^\n]*Connected/);
    assert.match(after, /mystuff-revoke[^\n]*(Disconnected|Error|authentication|Failed)/i);
    assert.match(after, /mystuff-independent[^\n]*Connected/);
  }
  assert.equal((await item(tokens[0])).status, 401);
  assert.equal((await renew(refresh[0])).status, 400);
  assert.equal((await item(tokens[1])).status, 200);
  const renewed = await (await renew(refresh[1])).json();
  paths.push(`mcpAccessTokens/${digest(renewed.access_token)}`, `mcpRefreshTokens/${digest(renewed.refresh_token)}`);
  assert.equal(typeof renewed.access_token, "string");
  assert.equal((await item(renewed.access_token)).status, 200);
  // Idempotent retries keep the same individual approval revoked.
  assert.deepEqual((await revoke({ connectionId: "e2e-revoke-0" })).data, { revoked: true });
  const listed = (await httpsCallable(functions, "listAppConnections")()).data.connections;
  assert.equal(typeof listed.find((connection) => connection.id === "e2e-revoke-0").revokedAt, "number");
  assert.equal(listed.find((connection) => connection.id === "e2e-revoke-1").revokedAt, null);
  assert.equal(getAuth(clientApp).currentUser.uid, memberId);
  await seed("appConnections/e2e-foreign-revoke", {
    memberId: "another-member", householdId, revokedAt: null,
  });
  await assert.rejects(revoke({ connectionId: "e2e-foreign-revoke" }), { code: "functions/not-found" });
  await assert.rejects(revoke({ connectionId: "unknown" }), { code: "functions/not-found" });
  await assert.rejects(revoke({ connectionId: "../foreign" }), { code: "functions/invalid-argument" });
  const unauthenticated = await fetch(`${base}/revokeAppConnection`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ data: { connectionId: "e2e-revoke-1" } }),
  });
  assert.equal(unauthenticated.status, 401);
});


// Exercise the installed real CLI with synthetic approvals and an isolated
// credential store. No production configuration or Member credentials are used.
async function geminiConnections(t, httpUrl, tokenUrl, tokens, refreshTokens) {
  const gemini = await isolatedGeminiCli(t);
  const { storage } = gemini;
  const names = ["mystuff-revoke", "mystuff-independent"];
  for (let index = 0; index < names.length; index++) {
    await storage.saveToken(names[index], {
      accessToken: tokens[index], refreshToken: refreshTokens[index],
      tokenType: "Bearer", scope: "inventory:read", expiresAt: Date.now() + 3600000,
    }, "e2e-revoke-client", tokenUrl, httpUrl);
  }
  await gemini.configure(Object.fromEntries(names.map((name) => [name, { httpUrl }])));
  return gemini;
}


test("unavailable connection storage returns HTTP errors rather than empty listings or successful revocation", {
  skip: !emulatorAvailable,
}, async (t) => {
  const unavailable = async () => { throw new Error("Storage unavailable"); };
  const handlers = createAppConnectionHandlers({
    database: {
      collection: () => ({ where: () => ({ get: unavailable }) }),
      runTransaction: unavailable,
    },
    logger: { error() {} },
  });
  const app = express();
  app.use(express.json());
  for (const [name, handler] of Object.entries(handlers)) app.post(`/${name}`, onCall(handler));
  const server = createServer(app);
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const idToken = await getAuth(clientApp).currentUser.getIdToken();
  for (const name of ["listAppConnections", "revokeAppConnection"]) {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/${name}`, {
      method: "POST", headers: { Authorization: `Bearer ${idToken}`, "Content-Type": "application/json" },
      body: JSON.stringify({ data: { connectionId: "example" } }),
    });
    assert.equal(response.status, 500);
    const result = await response.json();
    assert.equal(result.error.status, "INTERNAL");
    assert.equal(result.result, undefined);
  }
});
