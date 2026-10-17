import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { once } from "node:events";
import { createServer } from "node:http";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { promisify } from "node:util";
import { pathToFileURL } from "node:url";
import { test } from "node:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { createMcpApp } from "../src/mcp-app.js";
import { createHouseholdAccessModule } from "../src/household-access-module.js";
import { createDeletionRepository } from "../src/deletion-repository.js";

const verifier = "a".repeat(64);
const challenge = createHash("sha256").update(verifier).digest("base64url");
const callback = "http://localhost:32145/oauth/callback";

test("a Member approves one named Household and reads only its synced Item fields", async (t) => {
  const api = await fixture(t);
  const flow = await api.begin();
  assert.equal(flow.registration.status, 201);
  assert.match(flow.cookie, /^__session=/);
  assert.equal((await api.call("tools/list")).status, 401);
  const preview = await api.approval("preview", flow);
  assert.deepEqual(preview.body, {
    householdId: "home", householdName: "Our Home", clientName: "Gemini CLI", scope: "inventory:read",
  });
  const token = await api.connect(flow);
  const tools = await api.call("tools/list", {}, token);
  assert.deepEqual(tools.body.result.tools.map((tool) => tool.name), ["get_item"]);
  assert.equal(tools.body.result.tools[0].annotations.readOnlyHint, true);
  const item = await api.item("drill-1", token);
  assert.deepEqual(item.body.result.structuredContent, {
    id: "drill-1", path: ["Our Home", "Garage", "Cabinet", "Drill"],
    name: "Drill", description: "Cordless drill",
  });
  assert.deepEqual(JSON.parse(item.body.result.content[0].text), item.body.result.structuredContent);
  assert.deepEqual((await api.item("drill-2", token)).body.result.structuredContent, {
    id: "drill-2", path: ["Our Home", "Garage", "Cabinet", "Drill"],
    name: "Drill", description: null,
  });
});

test("OAuth discovery offers read-only PKCE authorization at the shared server", async (t) => {
  const api = await fixture(t);
  const unauthenticated = await api.call("initialize", { protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "Gemini CLI", version: "test" } });
  assert.equal(unauthenticated.status, 401);
  assert.match(unauthenticated.headers.get("www-authenticate"), /resource_metadata=".*\/.well-known\/oauth-protected-resource\/mcp"/);
  const resource = await api.request("/.well-known/oauth-protected-resource/mcp");
  assert.equal(resource.body.resource, `${api.origin}/mcp`);
  const metadata = await api.request("/.well-known/oauth-authorization-server");
  assert.deepEqual(metadata.body.scopes_supported, ["inventory:read"]);
  assert.deepEqual(metadata.body.code_challenge_methods_supported, ["S256"]);
  assert.deepEqual(metadata.body.grant_types_supported, ["authorization_code", "refresh_token"]);
  assert.equal(metadata.body.authorization_response_iss_parameter_supported, true);
  const token = await api.connect();
  const initialized = await api.call("initialize", { protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "Gemini CLI", version: "test" } }, token);
  assert.equal(initialized.status, 200);
  assert.equal(initialized.body.result.protocolVersion, "2025-11-25");
});

test("approval requires an existing Member, the same browser, and the reviewed Household", async (t) => {
  const api = await fixture(t);
  const flow = await api.begin();
  assert.equal((await api.approval("approve", flow)).status, 400);
  assert.equal((await api.approval("preview", flow, { identity: "outsider" })).status, 400);
  assert.equal((await api.approval("preview", flow, { identity: "expired-identity" })).status, 400);
  assert.equal((await api.approval("preview", flow, { cookie: "" })).status, 400);
  await api.approval("preview", flow);
  assert.equal((await api.approval("approve", flow, { householdId: "foreign" })).status, 400);
  assert.equal((await api.approval("approve", flow, { identity: "other" })).status, 400);
  const otherBrowser = await api.begin();
  assert.equal((await api.approval("approve", flow, { cookie: otherBrowser.cookie })).status, 400);
  const crossOrigin = await api.request("/oauth/approve", {
    method: "POST", headers: { "Content-Type": "application/json", Origin: "https://foreign.example", Cookie: flow.cookie, Authorization: "Bearer member" },
    body: JSON.stringify({ requestId: flow.requestId, householdId: "home" }),
  });
  assert.equal(crossOrigin.status, 403);
  assert.equal((await api.item("drill-1", "member")).status, 401);
  const token = await api.connect(flow);
  assert.equal((await api.item("drill-1", token)).body.result.structuredContent.id, "drill-1");
});

test("cancellation and expired approvals cannot produce credentials", async (t) => {
  const api = await fixture(t);
  const cancelled = await api.begin();
  await api.approval("preview", cancelled);
  const response = await api.approval("cancel", cancelled);
  const redirect = new URL(response.body.redirect);
  assert.equal(redirect.searchParams.get("error"), "access_denied");
  assert.equal(redirect.searchParams.get("state"), "client-state");
  assert.equal(redirect.searchParams.has("code"), false);
  assert.equal((await api.approval("approve", cancelled)).status, 400);
  const expired = await api.begin();
  await api.approval("preview", expired);
  api.advance(10 * 60 * 1000);
  assert.equal((await api.approval("approve", expired)).status, 400);
});

test("authorization codes enforce PKCE, client, callback, resource, expiry, and single use", async (t) => {
  const api = await fixture(t);
  const flow = await api.begin();
  await api.approval("preview", flow);
  const approved = await api.approval("approve", flow);
  const code = new URL(approved.body.redirect).searchParams.get("code");
  const other = await api.begin();
  assert.equal((await api.exchange(flow, code, { code_verifier: "b".repeat(64) })).status, 400);
  assert.equal((await api.exchange(flow, code, { client_id: other.clientId })).status, 400);
  assert.equal((await api.exchange(flow, code, { redirect_uri: "https://attacker.example/callback" })).status, 400);
  assert.equal((await api.exchange(flow, code, { resource: "https://foreign.example/mcp" })).status, 400);
  const concurrent = await Promise.all([api.exchange(flow, code), api.exchange(flow, code)]);
  assert.deepEqual(concurrent.map((result) => result.status).sort(), [200, 400]);
  const token = concurrent.find((result) => result.status === 200).body.access_token;
  assert.equal((await api.item("drill-1", token)).status, 200);
  const expiring = await api.begin();
  await api.approval("preview", expiring);
  const grant = await api.approval("approve", expiring);
  api.advance(60_000);
  assert.equal((await api.exchange(expiring, new URL(grant.body.redirect).searchParams.get("code"))).status, 400);
});

test("an active App Connection renews expired access tokens without browser approval", async (t) => {
  const api = await fixture(t);
  const flow = await api.begin();
  const credentials = await api.credentials(flow);
  assert.equal(typeof credentials.refresh_token, "string");
  api.advance(3600 * 1000);
  assert.equal((await api.item("drill-1", credentials.access_token)).status, 401);
  const renewed = await api.refresh(flow, credentials.refresh_token);
  assert.equal(renewed.status, 200);
  assert.notEqual(renewed.body.access_token, credentials.access_token);
  assert.notEqual(renewed.body.refresh_token, credentials.refresh_token);
  assert.equal(renewed.body.expires_in, 3600);
  assert.equal((await api.item("drill-1", renewed.body.access_token)).body.result.structuredContent.id, "drill-1");
});

// Optional compatibility check against an installed Gemini CLI bundle. All
// identities and Inventory are local fixtures; no Member credentials are read.
test("the installed Gemini CLI transport automatically renews and reads an Item", {
  skip: process.env.GEMINI_CLI_BUNDLE === undefined,
}, async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "mystuff-gemini-renewal-"));
  const previousHome = process.env.GEMINI_CLI_HOME;
  process.env.GEMINI_CLI_HOME = directory;
  t.after(async () => {
    if (previousHome === undefined) delete process.env.GEMINI_CLI_HOME;
    else process.env.GEMINI_CLI_HOME = previousHome;
    await rm(directory, { recursive: true, force: true });
  });
  const { MCPOAuthTokenStorage, createTransport } = await import(pathToFileURL(process.env.GEMINI_CLI_BUNDLE));
  const api = await fixture(t);
  const flow = await api.begin();
  const credentials = await api.credentials(flow);
  const storage = new MCPOAuthTokenStorage();
  await storage.saveToken("mystuff-renewal", {
    accessToken: credentials.access_token, refreshToken: credentials.refresh_token,
    tokenType: "Bearer", scope: "inventory:read", expiresAt: Date.now() - 1,
  }, flow.clientId, `${api.origin}/token`, `${api.origin}/mcp`);
  api.advance(3600 * 1000);
  assert.equal((await api.item("drill-1", credentials.access_token)).status, 401);
  const transport = await createTransport("mystuff-renewal", { httpUrl: `${api.origin}/mcp` }, false, {
    emitMcpDiagnostic() {}, sanitizationConfig: {},
  });
  const client = new Client({ name: "mystuff-renewal-verification", version: "1.0.0" });
  t.after(() => client.close());
  await client.connect(transport);
  const tools = await client.listTools();
  assert.deepEqual(tools.tools.map((tool) => tool.name), ["get_item"]);
  const item = await client.callTool({ name: "get_item", arguments: { itemId: "drill-1" } });
  assert.equal(item.structuredContent.id, "drill-1");
  const saved = await storage.getCredentials("mystuff-renewal");
  assert.notEqual(saved.token.accessToken, credentials.access_token);
  assert.notEqual(saved.token.refreshToken, credentials.refresh_token);
  // Exercise the actual CLI executable as well, forcing its stored token to
  // expire so `mcp list` has to renew before it can report a connected server.
  await client.close();
  await storage.saveToken("mystuff-renewal", { ...saved.token, expiresAt: Date.now() - 1 },
    flow.clientId, `${api.origin}/token`, `${api.origin}/mcp`);
  await writeFile(join(directory, ".gemini/settings.json"), JSON.stringify({
    mcpServers: { "mystuff-renewal": { httpUrl: `${api.origin}/mcp` } },
  }));
  await writeFile(join(directory, ".gemini/trustedFolders.json"), JSON.stringify({
    [directory]: "TRUST_FOLDER",
  }));
  const cli = await promisify(execFile)(process.execPath,
    [join(dirname(process.env.GEMINI_CLI_BUNDLE), "gemini.js"), "mcp", "list"],
    { cwd: directory, env: process.env, timeout: 30_000 });
  assert.match(cli.stdout + cli.stderr, /Connected/);
  const cliSaved = await storage.getCredentials("mystuff-renewal");
  assert.notEqual(cliSaved.token.accessToken, saved.token.accessToken);
  assert.notEqual(cliSaved.token.refreshToken, saved.token.refreshToken);
});

test("refresh credentials enforce client, resource, scope, rotation, and replay invalidation", async (t) => {
  const api = await fixture(t);
  const flow = await api.begin();
  const credentials = await api.credentials(flow);
  const otherFlow = await api.begin();
  const independent = await api.credentials(otherFlow);
  for (const changes of [{ client_id: otherFlow.clientId }, { resource: "https://foreign.example/mcp" },
    { scope: "inventory:write" }]) {
    assert.equal((await api.refresh(flow, credentials.refresh_token, changes)).status, 400);
  }
  const renewed = await api.refresh(flow, credentials.refresh_token, { scope: "inventory:read" });
  assert.equal(renewed.status, 200);
  assert.equal((await api.item("drill-1", renewed.body.access_token)).status, 200);
  assert.equal((await api.refresh(flow, credentials.refresh_token)).body.error, "invalid_grant");
  // A replay may indicate theft. Neither the superseded nor the new credentials
  // may survive, and another App Connection must remain usable.
  assert.equal((await api.item("drill-1", credentials.access_token)).status, 401);
  assert.equal((await api.item("drill-1", renewed.body.access_token)).status, 401);
  assert.equal((await api.refresh(flow, renewed.body.refresh_token)).body.error, "invalid_grant");
  assert.equal((await api.item("drill-1", independent.access_token)).status, 200);
});

test("a different Household requires approval naming it and never redirects former credentials", async (t) => {
  const api = await fixture(t);
  const flow = await api.begin();
  const credentials = await api.credentials(flow);
  api.documents.set("memberships/member", { householdId: "foreign" });
  assert.equal((await api.item("private", credentials.access_token)).status, 401);
  assert.equal((await api.item("drill-1", credentials.access_token)).status, 401);
  assert.equal((await api.refresh(flow, credentials.refresh_token)).body.error, "invalid_grant");
  const newFlow = await api.begin();
  const preview = await api.approval("preview", newFlow);
  assert.equal(preview.body.householdName, "Other Home");
  assert.equal((await api.approval("approve", newFlow)).status, 400);
  const current = await api.credentials(newFlow, "member", "foreign");
  assert.equal((await api.item("private", current.access_token)).body.result.structuredContent.id, "private");
  assert.equal((await api.item("drill-1", current.access_token)).body.result.isError, true);
  assert.equal((await api.item("private", credentials.access_token)).status, 401);
});

test("90 days without MCP use requires fresh approval, while reads keep a connection active", async (t) => {
  const api = await fixture(t);
  const idleFlow = await api.begin();
  const idle = await api.credentials(idleFlow);
  const activeFlow = await api.begin();
  let active = await api.credentials(activeFlow);
  const day = 24 * 3600 * 1000;
  api.advance(89 * day);
  // Token renewal alone must not disguise an unused App Connection as active.
  const idleRenewal = await api.refresh(idleFlow, idle.refresh_token);
  assert.equal(idleRenewal.status, 200);
  active = (await api.refresh(activeFlow, active.refresh_token)).body;
  assert.equal((await api.item("drill-1", active.access_token)).status, 200);
  api.advance(day - 1);
  const boundaryToken = await api.refresh(idleFlow, idleRenewal.body.refresh_token);
  assert.equal(boundaryToken.status, 200);
  api.advance(1);
  assert.equal((await api.item("drill-1", boundaryToken.body.access_token)).status, 401);
  assert.equal((await api.call("tools/list", { cursor: "continuation" }, boundaryToken.body.access_token)).status, 401);
  assert.equal((await api.refresh(idleFlow, boundaryToken.body.refresh_token)).body.error, "invalid_grant");
  active = (await api.refresh(activeFlow, active.refresh_token)).body;
  assert.equal((await api.item("drill-1", active.access_token)).body.result.structuredContent.id, "drill-1");
  assert.equal((await api.item("drill-1", await api.connect())).status, 200);
});

test("access removed and restored before the next request still requires fresh approval", async (t) => {
  const api = await fixture(t);
  const flow = await api.begin();
  const credentials = await api.credentials(flow);
  // Issue 01 connections did not store generation fields. They remain usable
  // until an invalidating event, and must then obey the same lifecycle.
  for (const [path, connection] of api.documents) {
    if (path.startsWith("appConnections/")) {
      const { memberGeneration: _member, householdGeneration: _household, ...legacy } = connection;
      api.documents.set(path, legacy);
    }
  }
  assert.equal((await api.item("drill-1", credentials.access_token)).status, 200);
  const pendingFlow = await api.begin();
  await api.approval("preview", pendingFlow);
  const pendingApproval = await api.approval("approve", pendingFlow);
  const pendingCode = new URL(pendingApproval.body.redirect).searchParams.get("code");
  const otherFlow = await api.begin();
  const other = await api.credentials(otherFlow, "other", "foreign");
  const owner = await api.credentials(await api.begin(), "owner", "home");
  const membership = api.documents.get("memberships/member");
  const access = createHouseholdAccessModule({ database: api.database });
  await access.removeAccess({ memberId: "owner", householdId: "home", email: "member@example.com" });
  // Simulate the Owner restoring access and the same identity joining again,
  // with no intervening MCP read or renewal to observe the removal.
  api.documents.set("memberships/member", membership);
  assert.equal((await api.item("drill-1", credentials.access_token)).status, 401);
  assert.equal((await api.refresh(flow, credentials.refresh_token)).body.error, "invalid_grant");
  assert.equal((await api.exchange(pendingFlow, pendingCode)).body.error, "invalid_grant");
  assert.equal((await api.item("private", other.access_token)).body.result.structuredContent.id, "private");
  assert.equal((await api.item("drill-1", owner.access_token)).body.result.structuredContent.id, "drill-1");
  assert.equal((await api.item("drill-1", await api.connect())).status, 200);
  assert.equal((await api.item("drill-1", credentials.access_token)).status, 401);
});

test("deletion and completed cleanup cannot revive former approval after later sign-in", async (t) => {
  for (const kind of ["account", "owner", "household"]) {
    await t.test(kind, async (t) => {
      const api = await fixture(t);
      const flow = await api.begin();
      const credentials = await api.credentials(flow);
      const deletion = createDeletionRepository({ database: api.database });
      if (kind === "household") {
        await deletion.enqueueHousehold({ householdId: "home" });
      } else {
        await deletion.enqueueAccount({ memberId: kind === "owner" ? "owner" : "member",
          householdId: "home", deletesHousehold: kind === "owner" });
      }
      assert.equal((await api.item("drill-1", credentials.access_token)).status, 401);
      assert.equal((await api.refresh(flow, credentials.refresh_token)).body.error, "invalid_grant");
      // Jobs and membership may disappear during cleanup, then be recreated by
      // a later sign-in. No MCP request observes the intermediate missing data.
      api.documents.delete("accountDeletionJobs/member");
      api.documents.delete("accountDeletionJobs/owner");
      api.documents.delete("householdDeletionJobs/home");
      api.documents.set("households/home", { name: "Our Home", ownerMemberId: "owner" });
      api.documents.set("memberships/member", { householdId: "home" });
      assert.equal((await api.item("drill-1", credentials.access_token)).status, 401);
      assert.equal((await api.refresh(flow, credentials.refresh_token)).body.error, "invalid_grant");
      assert.equal((await api.item("drill-1", await api.connect())).status, 200);
    });
  }
});

test("each read rejects revoked connections, expired tokens, access removal, and current deletion states", async (t) => {
  const api = await fixture(t);
  const token = await api.connect();
  assert.equal((await api.item("drill-1", "invalid")).status, 401);
  for (const path of ["accountDeletionJobs/member", "accountDeletionJobs/owner", "householdDeletionJobs/home"]) {
    api.documents.set(path, { state: "pending" });
    assert.equal((await api.item("drill-1", token)).status, 401, path);
    api.documents.delete(path);
  }
  const membership = api.documents.get("memberships/member");
  api.documents.delete("memberships/member");
  assert.equal((await api.item("drill-1", token)).status, 401);
  api.documents.set("memberships/member", { householdId: "foreign" });
  assert.equal((await api.item("drill-1", token)).status, 401);
  api.documents.set("memberships/member", membership);
  const secondToken = await api.connect();
  const connections = [...api.documents.entries()].filter(([path]) => path.startsWith("appConnections/"));
  api.documents.set(connections[0][0], { ...connections[0][1], revokedAt: Date.now() });
  assert.equal((await api.item("drill-1", token)).status, 401);
  assert.equal((await api.item("drill-1", secondToken)).body.result.structuredContent.id, "drill-1");
  api.advance(3600 * 1000);
  assert.equal((await api.item("drill-1", secondToken)).status, 401);
});

test("foreign and malformed IDs, missing data, corrupt ancestry, and storage failures never return Item content", async (t) => {
  const api = await fixture(t);
  const token = await api.connect();
  for (const id of ["private", "missing", "../foreign/items/private", "", ".", "..", "__reserved__", "x".repeat(1501)]) {
    const response = await api.item(id, token);
    assert.equal(response.body.result.isError, true, id);
    assert.equal(response.body.result.structuredContent, undefined);
    assert.doesNotMatch(JSON.stringify(response.body), /Private Item|private\.example|secret-photo|secret-attachment/);
  }
  assert.equal((await api.item("drill-1", token, { householdId: "foreign" })).body.result.structuredContent.id, "drill-1");
  const cabinetPath = "households/home/items/cabinet";
  const cabinet = api.documents.get(cabinetPath);
  for (const replacement of [undefined, { ...cabinet, parentItemId: "cabinet" },
    { ...cabinet, parentItemId: null }, { ...cabinet, householdId: "foreign" }]) {
    if (replacement) api.documents.set(cabinetPath, replacement);
    else api.documents.delete(cabinetPath);
    assert.equal((await api.item("drill-1", token)).body.result.isError, true);
  }
  api.documents.set(cabinetPath, cabinet);
  api.documents.set("households/home/itemDeletionJobs/cabinet", { state: "pending" });
  assert.equal((await api.item("drill-1", token)).body.result.isError, true);
  api.documents.delete("households/home/itemDeletionJobs/cabinet");
  const itemPath = "households/home/items/drill-1";
  const item = api.documents.get(itemPath);
  const { description: _description, ...withoutDescription } = item;
  api.documents.set(itemPath, withoutDescription);
  assert.equal((await api.item("drill-1", token)).body.result.isError, true);
  api.documents.set(itemPath, item);
  const originalTransaction = api.database.runTransaction;
  api.database.runTransaction = async () => { throw new Error("Storage unavailable"); };
  assert.equal((await api.item("drill-1", token)).status, 500);
  api.database.runTransaction = originalTransaction;
  assert.equal((await api.item("home", token)).body.result.structuredContent.path[0], "Our Home");
});

test("HTTP approval, code exchange, and complete Item Paths work with Firestore transactions", {
  skip: process.env.FIRESTORE_EMULATOR_HOST === undefined,
}, async (t) => {
  // Repository transactions own cleanup timing here. Keep the fixture separate
  // from default-database background workers in the full Functions suite.
  const database = getFirestore(initializeApp({ projectId: "demo-mystuff-search" }, "mcp-http"), "mcp-lifecycle-tests");
  t.after(async () => {
    for (const collection of ["mcpClients", "mcpApprovals", "mcpCodes", "mcpAccessTokens", "mcpRefreshTokens",
      "appConnections", "mcpMemberEpochs", "mcpHouseholdEpochs"]) {
      await database.recursiveDelete(database.collection(collection));
    }
  });
  const api = await fixture(t, database);
  const flow = await api.begin();
  await api.approval("preview", flow);
  const approved = await api.approval("approve", flow);
  const code = new URL(approved.body.redirect).searchParams.get("code");
  const exchanged = await Promise.all([api.exchange(flow, code), api.exchange(flow, code)]);
  assert.deepEqual(exchanged.map((result) => result.status).sort(), [200, 400]);
  const credentials = exchanged.find((result) => result.status === 200).body;
  const token = credentials.access_token;
  assert.deepEqual((await api.item("drill-1", token)).body.result.structuredContent, {
    id: "drill-1", path: ["Our Home", "Garage", "Cabinet", "Drill"],
    name: "Drill", description: "Cordless drill",
  });
  const renewal = await api.refresh(flow, credentials.refresh_token);
  assert.equal(renewal.status, 200);
  assert.equal((await api.item("drill-1", renewal.body.access_token)).status, 200);
  const access = createHouseholdAccessModule({ database });
  await access.removeAccess({ memberId: "owner", householdId: "home", email: "member@example.com" });
  await database.doc("memberships/member").set({ householdId: "home" });
  assert.equal((await api.item("drill-1", renewal.body.access_token)).status, 401);
  assert.equal((await api.refresh(flow, renewal.body.refresh_token)).body.error, "invalid_grant");
  const restoredFlow = await api.begin();
  const restored = await api.credentials(restoredFlow);
  assert.equal((await api.item("drill-1", restored.access_token)).status, 200);
  const deletion = createDeletionRepository({ database });
  await deletion.enqueueHousehold({ householdId: "home" });
  await deletion.completeHousehold("home");
  await database.doc("households/home").set({ name: "Our Home", ownerMemberId: "owner" });
  assert.equal((await api.item("drill-1", restored.access_token)).status, 401);
  assert.equal((await api.refresh(restoredFlow, restored.refresh_token)).body.error, "invalid_grant");
  const currentFlow = await api.begin();
  const current = await api.credentials(currentFlow);
  const concurrent = await Promise.all([
    api.refresh(currentFlow, current.refresh_token), api.refresh(currentFlow, current.refresh_token),
  ]);
  assert.deepEqual(concurrent.map((result) => result.status).sort(), [200, 400]);
  assert.equal((await api.item("drill-1", concurrent.find((result) => result.status === 200).body.access_token)).status, 401);
});

async function fixture(t, firestore) {
  const documents = new Map(Object.entries({
    "memberships/member": { householdId: "home" },
    "memberships/owner": { householdId: "home" },
    "memberships/other": { householdId: "foreign" },
    "households/home": { name: "Our Home", ownerMemberId: "owner" },
    "households/home/access/member@example.com": { householdId: "home", memberId: "member" },
    "households/foreign": { name: "Other Home", ownerMemberId: "other" },
    "households/foreign/items/foreign": { householdId: "foreign", name: "Other Home", parentItemId: null, description: null },
    "households/home/items/home": { householdId: "home", name: "Our Home", parentItemId: null, description: null },
    "households/home/items/garage": { householdId: "home", name: "Garage", parentItemId: "home", description: null },
    "households/home/items/cabinet": { householdId: "home", name: "Cabinet", parentItemId: "garage", description: null },
    "households/home/items/drill-1": {
      householdId: "home", name: "Drill", parentItemId: "cabinet", description: "Cordless drill",
      tags: ["power tool"], webUrl: "https://private.example", photoUrl: "secret-photo",
      attachments: ["secret-attachment"], createdById: "member", displayOrder: 5,
    },
    "households/home/items/drill-2": { householdId: "home", name: "Drill", parentItemId: "cabinet", description: null },
    "households/foreign/items/private": { householdId: "foreign", name: "Private Item", parentItemId: "foreign", description: "Private" },
  }));
  const database = firestore ?? fakeFirestore(documents);
  if (firestore) {
    await Promise.all([...documents].map(([path, value]) => firestore.doc(path).set(value)));
    t.after(async () => {
      await Promise.all([...documents.keys()].map((path) => firestore.doc(path).delete()));
    });
  }
  let time = Date.now();
  const server = createServer();
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const origin = `http://127.0.0.1:${server.address().port}`;
  const app = createMcpApp({
    database, origin, firebaseConfig: {}, now: () => time,
    authentication: {
      async verifyIdToken(token) {
        if (!["member", "owner", "other", "outsider"].includes(token)) throw new Error("Invalid identity");
        return { uid: token, email_verified: true, firebase: { sign_in_provider: "google.com" } };
      },
    },
    logger: { error() {} },
  });
  server.on("request", app);
  t.after(() => new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())));
  const url = origin;

  async function request(path, options = {}) {
    const response = await fetch(`${url}${path}`, { redirect: "manual", ...options });
    const text = await response.text();
    let body;
    try { body = JSON.parse(text); } catch { body = text; }
    return { status: response.status, headers: response.headers, body };
  }
  async function begin(overrides = {}) {
    const registration = await request("/register", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ client_name: "Gemini CLI", redirect_uris: [callback],
        token_endpoint_auth_method: "none", grant_types: ["authorization_code", "refresh_token"] }),
    });
    const params = new URLSearchParams({
      client_id: registration.body.client_id, redirect_uri: callback, response_type: "code",
      code_challenge: challenge, code_challenge_method: "S256", scope: "inventory:read",
      resource: `${origin}/mcp`, state: "client-state", ...overrides,
    });
    const authorization = await request(`/authorize?${params}`);
    const location = authorization.headers.get("location");
    return { registration, authorization, clientId: registration.body.client_id,
      requestId: location ? new URL(location).searchParams.get("request") : null,
      cookie: authorization.headers.get("set-cookie")?.split(";")[0] };
  }
  async function approval(action, flow, { identity = "member", householdId = "home", cookie = flow.cookie, ...extra } = {}) {
    return request(`/oauth/${action}`, { method: "POST",
      headers: { "Content-Type": "application/json", Origin: origin, Cookie: cookie ?? "",
        Authorization: `Bearer ${identity}` },
      body: JSON.stringify({ requestId: flow.requestId, householdId, ...extra }),
    });
  }
  async function exchange(flow, code, changes = {}) {
    return request("/token", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "authorization_code", code, client_id: flow.clientId,
        code_verifier: verifier, redirect_uri: callback, resource: `${origin}/mcp`, ...changes }),
    });
  }
  async function credentials(flow = undefined, identity = "member", householdId = "home") {
    flow ??= await begin();
    assert.equal((await approval("preview", flow, { identity, householdId })).status, 200);
    const approved = await approval("approve", flow, { identity, householdId });
    assert.equal(approved.status, 200);
    const redirect = new URL(approved.body.redirect);
    assert.equal(redirect.searchParams.get("state"), "client-state");
    assert.equal(redirect.searchParams.get("iss"), `${origin}/`);
    const response = await exchange(flow, redirect.searchParams.get("code"));
    assert.equal(response.status, 200);
    return response.body;
  }
  async function connect(flow) { return (await credentials(flow)).access_token; }
  async function refresh(flow, token, changes = {}) {
    return request("/token", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "refresh_token", refresh_token: token,
        client_id: flow.clientId, resource: `${origin}/mcp`, ...changes }),
    });
  }
  async function call(method, params = {}, token) {
    return request("/mcp", { method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream",
        ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
    });
  }
  return { request, begin, approval, exchange, connect, credentials, refresh, call,
    item: (itemId, token, extra = {}) => call("tools/call", { name: "get_item", arguments: { itemId, ...extra } }, token),
    documents, advance: (milliseconds) => { time += milliseconds; }, database, origin };
}

// Firebase Admin SDK is the external boundary. No MCP or authorization module
// is mocked: every request traverses Express, OAuth handlers, and the MCP SDK.
function fakeFirestore(documents) {
  let queue = Promise.resolve();
  const snapshot = (path) => ({ exists: documents.has(path), data: () => structuredClone(documents.get(path)) });
  const database = {
    doc(path) {
      return { path, get: async () => snapshot(path),
        create: async (data) => {
          if (documents.has(path)) throw new Error("Already exists");
          documents.set(path, structuredClone(data));
        },
        update: async (data) => {
          if (!documents.has(path)) throw new Error("Not found");
          documents.set(path, { ...documents.get(path), ...structuredClone(data) });
        },
      };
    },
    async runTransaction(operation) {
      const next = queue.then(async () => {
        const writes = [];
        const result = await operation({
          get: async (ref) => snapshot(ref.path),
          create: (ref, value) => writes.push(() => {
            if (documents.has(ref.path)) throw new Error("Already exists");
            documents.set(ref.path, structuredClone(value));
          }),
          delete: (ref) => writes.push(() => documents.delete(ref.path)),
          update: (ref, value) => writes.push(() => {
            if (!documents.has(ref.path)) throw new Error("Not found");
            documents.set(ref.path, { ...documents.get(ref.path), ...structuredClone(value) });
          }),
          set: (ref, value) => writes.push(() => documents.set(ref.path, structuredClone(value))),
        });
        writes.forEach((write) => write());
        return result;
      });
      queue = next.catch(() => {});
      return next;
    },
  };
  return database;
}
