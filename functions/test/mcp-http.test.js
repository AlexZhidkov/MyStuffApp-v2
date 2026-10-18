import { isolatedGeminiCli } from "../test-support/gemini-cli.js";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { once } from "node:events";
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
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

test("literal Search returns Android's broad fallback with complete Item Paths and content fields only", async (t) => {
  const api = await fixture(t);
  const token = await api.connect();
  const response = await api.search({ query: "  DRÍLL  " }, token);
  assert.equal(response.body.result.isError, undefined);
  assert.deepEqual(response.body.result.structuredContent, {
    literalOnlyFallback: true,
    items: [
      { id: "drill-1", path: ["Our Home", "Garage", "Cabinet", "Drill"], name: "Drill", description: "Cordless drill" },
      { id: "drill-2", path: ["Our Home", "Garage", "Cabinet", "Drill"], name: "Drill", description: null },
    ],
    nextCursor: null,
  });
  assert.deepEqual(JSON.parse(response.body.result.content[0].text), response.body.result.structuredContent);
});

test("literal Search agrees with the shared Android normalization, ranking, short-query, and excluded-input fixtures", async (t) => {
  const api = await fixture(t);
  for (const path of [...api.documents.keys()]) {
    if (path.startsWith("households/home/items/")) api.documents.delete(path);
  }
  const readRows = async (name) => (await readFile(new URL(`../../app/src/test/resources/literal-search-${name}.tsv`, import.meta.url), "utf8"))
    .trimEnd().split("\n").slice(1).map((line) => line.split("\t"));
  for (const [id, parentItemId, name, tags, description, webUrl, photoUrl] of await readRows("items")) {
    await api.seed(`households/home/items/${id}`, {
      householdId: "home", parentItemId: parentItemId === "-" ? null : parentItemId, name,
      tags: tags === "-" ? [] : tags.split("|"), description: description === "-" ? null : description,
      webUrl: webUrl === "-" ? null : webUrl, photoUrl: photoUrl === "-" ? null : photoUrl,
      attachments: [{ name: "attachmentOnly", text: "attachmentTextOnly" }],
    });
  }
  const token = await api.connect();
  for (const [name, query, expectedIds] of await readRows("queries")) {
    const ids = [];
    let cursor;
    do {
      const response = await api.search({ query, ...(cursor ? { cursor } : {}) }, token);
      assert.equal(response.body.result.isError, undefined, name);
      const page = response.body.result.structuredContent;
      ids.push(...page.items.map((item) => item.id));
      assert.equal(page.literalOnlyFallback, true);
      assert.ok(page.items.length <= 20);
      assert.ok(page.items.every((item) => Object.keys(item).sort().join(",") === "description,id,name,path"));
      cursor = page.nextCursor;
      assert.ok(ids.length <= 40, "fixture Search must terminate");
    } while (cursor !== null);
    assert.deepEqual(ids, expectedIds === "-" ? [] : expectedIds.split(","), name);
  }
  for (const query of ["attachmentOnly", "attachmentTextOnly"]) {
    assert.deepEqual((await api.search({ query }, token)).body.result.structuredContent.items, []);
  }
});

test("a fixed Inventory searches every literal match once in Android order across pages of at most 20", async (t) => {
  const api = await fixture(t);
  await seedChildren(api, 41);
  const token = await api.connect();
  const ids = [];
  const sizes = [];
  let cursor;
  do {
    const response = await api.search({ query: "duplicate", ...(cursor ? { cursor } : {}) }, token);
    assert.equal(response.body.result.isError, undefined);
    const page = response.body.result.structuredContent;
    assert.equal(page.literalOnlyFallback, true);
    sizes.push(page.items.length);
    ids.push(...page.items.map((item) => item.id));
    cursor = page.nextCursor;
    assert.ok(sizes.length <= 3, "Search must terminate");
  } while (cursor !== null);
  assert.deepEqual(sizes, [20, 20, 1]);
  // Equal Search ranks retain Android's document-ID snapshot order, ignoring displayOrder.
  assert.deepEqual(ids, ["child-0", "child-1", "child-10", "child-11", "child-12", "child-13", "child-14",
    "child-15", "child-16", "child-17", "child-18", "child-19", "child-2", "child-20", "child-21", "child-22",
    "child-23", "child-24", "child-25", "child-26", "child-27", "child-28", "child-29", "child-3", "child-30",
    "child-31", "child-32", "child-33", "child-34", "child-35", "child-36", "child-37", "child-38", "child-39",
    "child-4", "child-40", "child-5", "child-6", "child-7", "child-8", "child-9"]);
});

test("Search markers cannot change query, connection, Household, or page limit", async (t) => {
  const api = await fixture(t);
  await seedChildren(api, 41);
  await seedChildren(api, 21, "foreign", "foreign");
  const token = await api.connect();
  const otherConnection = await api.connect();
  const foreignToken = (await api.credentials(undefined, "other", "foreign")).access_token;
  const first = (await api.search({ query: "duplicate" }, token)).body.result.structuredContent;
  const foreign = (await api.search({ query: "duplicate" }, foreignToken)).body.result.structuredContent;
  const cursor = first.nextCursor;
  const altered = `${cursor[0] === "a" ? "b" : "a"}${cursor.slice(1)}`;
  const listing = (await api.children({ parentItemId: "drill-1" }, token)).body.result.structuredContent;
  for (const [arguments_, credential] of [
    [{ query: "drill", cursor }, token], [{ query: "", cursor }, token],
    [{ query: "duplicate", cursor: altered }, token], [{ query: "duplicate", cursor: "../foreign" }, token],
    [{ query: "duplicate", cursor: "" }, token], [{ query: "duplicate", cursor }, otherConnection],
    [{ query: "duplicate", cursor }, foreignToken], [{ query: "duplicate", cursor: foreign.nextCursor }, token],
    [{ query: "duplicate", cursor: listing.nextCursor }, token], [{ query: null }, token], [{}, token],
  ]) {
    const response = await api.search(arguments_, credential);
    assert.equal(response.body.result.isError, true);
    assert.equal(response.body.result.structuredContent, undefined);
    assert.doesNotMatch(JSON.stringify(response.body), /Private Item|Other Home/);
  }
  const continued = (await api.search({ query: "duplicate", cursor, householdId: "foreign", itemId: "private",
    parentItemId: "foreign", pageSize: 1000, limit: 1000, offset: 0 }, token)).body.result.structuredContent;
  assert.equal(continued.items.length, 20);
  assert.ok(continued.items.every((item) => item.path[0] === "Our Home"));
  assert.deepEqual((await api.search({ query: "duplicate", cursor }, token)).body.result.structuredContent.items, continued.items);
  assert.equal((await api.children({ parentItemId: "drill-1", cursor }, token)).body.result.isError, true);
  assert.deepEqual((await api.search({ query: "Private Item", householdId: "foreign" }, token)).body.result.structuredContent.items, []);
});

test("Search continuation checks current authorization and never treats unavailable Inventory as empty fallback", async (t) => {
  for (const change of ["revocation", "access", "different Household", "account deletion", "owner deletion", "Household deletion",
    "missing root", "missing ancestor", "cycle", "foreign Item", "missing tags", "corrupt tags", "missing description",
    "deleted Item", "storage failure"]) {
    await t.test(change, async (t) => {
      const api = await fixture(t);
      await seedChildren(api, 21);
      const token = await api.connect();
      const first = (await api.search({ query: "duplicate" }, token)).body.result.structuredContent;
      const unauthorized = ["revocation", "access", "different Household", "account deletion", "owner deletion", "Household deletion"].includes(change);
      if (change === "revocation") [...api.documents].find(([path]) => path.startsWith("appConnections/"))[1].revokedAt = Date.now();
      if (change === "access") api.documents.delete("memberships/member");
      if (change === "different Household") api.documents.set("memberships/member", { householdId: "foreign" });
      const deletion = { "account deletion": "accountDeletionJobs/member", "owner deletion": "accountDeletionJobs/owner",
        "Household deletion": "householdDeletionJobs/home", "deleted Item": "households/home/itemDeletionJobs/child-9" }[change];
      if (deletion) api.documents.set(deletion, { status: "pending" });
      if (change === "missing root") api.documents.delete("households/home/items/home");
      if (change === "missing ancestor") api.documents.delete("households/home/items/cabinet");
      if (change === "cycle") api.documents.get("households/home/items/cabinet").parentItemId = "drill-1";
      if (change === "foreign Item") api.documents.get("households/home/items/child-9").householdId = "foreign";
      if (change === "missing tags") delete api.documents.get("households/home/items/child-9").tags;
      if (change === "corrupt tags") api.documents.get("households/home/items/child-9").tags = [123];
      if (change === "missing description") delete api.documents.get("households/home/items/child-9").description;
      if (change === "storage failure") api.database.collection = () => { throw new Error("Storage unavailable"); };
      for (const arguments_ of [{ query: "duplicate", cursor: first.nextCursor }, { query: "" }, { query: "no match" }]) {
        const response = await api.search(arguments_, token);
        if (unauthorized) assert.equal(response.status, 401);
        else assert.equal(response.body.result.isError, true);
        assert.equal(response.body.result?.structuredContent, undefined);
      }
    });
  }
});

test("Search page boundaries expose a continuation marker only when more results remain", async (t) => {
  for (const count of [0, 1, 19, 20, 21, 40]) {
    await t.test(`${count} matches`, async (t) => {
      const api = await fixture(t);
      await seedChildren(api, count);
      const token = await api.connect();
      const first = (await api.search({ query: "duplicate" }, token)).body.result.structuredContent;
      assert.equal(first.items.length, Math.min(count, 20));
      assert.equal(first.nextCursor === null, count <= 20);
      if (count > 20) {
        const second = (await api.search({ query: "duplicate", cursor: first.nextCursor }, token)).body.result.structuredContent;
        assert.equal(second.items.length, count - 20);
        assert.equal(second.nextCursor, null);
      }
    });
  }
});

test("clients browse from the approved Household root into direct Child Items with stable IDs", async (t) => {
  const api = await fixture(t);
  const token = await api.connect();
  const tools = (await api.call("tools/list", {}, token)).body.result.tools;
  assert.equal(tools.find((tool) => tool.name === "list_children")?.annotations.readOnlyHint, true);
  const root = await api.children({}, token);
  assert.deepEqual(root.body.result.structuredContent, {
    parentItemId: "home", items: [{ id: "garage", name: "Garage" }], nextCursor: null,
  });
  const nested = await api.children({ parentItemId: "garage" }, token);
  assert.deepEqual(nested.body.result.structuredContent, {
    parentItemId: "garage", items: [{ id: "cabinet", name: "Cabinet" }], nextCursor: null,
  });
  const duplicates = await api.children({ parentItemId: "cabinet" }, token);
  assert.deepEqual(duplicates.body.result.structuredContent, {
    parentItemId: "cabinet", items: [{ id: "drill-2", name: "Drill" }, { id: "drill-1", name: "Drill" }], nextCursor: null,
  });
  assert.deepEqual(JSON.parse(duplicates.body.result.content[0].text), duplicates.body.result.structuredContent);
  assert.deepEqual((await api.children({ parentItemId: "drill-1" }, token)).body.result.structuredContent, {
    parentItemId: "drill-1", items: [], nextCursor: null,
  });
});

test("a fixed Inventory lists every Child Item once across pages of at most 20", async (t) => {
  const api = await fixture(t);
  const token = await api.connect();
  // Reverse insertion ensures paging follows the shared order, not insertion.
  await seedChildren(api, 41);
  const ids = [];
  const sizes = [];
  let cursor;
  do {
    const response = await api.children({ parentItemId: "drill-1", ...(cursor ? { cursor } : {}) }, token);
    assert.equal(response.body.result.isError, undefined);
    const page = response.body.result.structuredContent;
    assert.equal(page.parentItemId, "drill-1");
    sizes.push(page.items.length);
    ids.push(...page.items.map((item) => item.id));
    cursor = page.nextCursor;
    if (cursor !== null) assert.equal(typeof cursor, "string");
    assert.ok(sizes.length <= 3, "listing must terminate");
  } while (cursor !== null);
  assert.deepEqual(sizes, [20, 20, 1]);
  assert.deepEqual(ids, Array.from({ length: 41 }, (_, index) => `child-${index}`));
});

test("Child Item order matches Android's missing-position and tied-position rules", async (t) => {
  const api = await fixture(t);
  const token = await api.connect();
  for (const [id, order] of [["f", undefined], ["e", 1], ["d", -1], ["c", 0], ["b", null], ["a", undefined]]) {
    await api.seed(`households/home/items/${id}`, {
      householdId: "home", parentItemId: "drill-1", name: "Same name",
      ...(order !== undefined ? { displayOrder: order } : {}),
    });
  }
  const page = (await api.children({ parentItemId: "drill-1" }, token)).body.result.structuredContent;
  // Android: missing order = sibling index in the ID-ordered snapshot; ties = that index.
  assert.deepEqual(page.items.map((item) => item.id), ["d", "a", "c", "b", "e", "f"]);
});

test("Child Item page boundaries give a marker only when more Items remain", async (t) => {
  for (const count of [0, 1, 19, 20, 21, 40]) {
    await t.test(`${count} children`, async (t) => {
      const api = await fixture(t);
      await seedChildren(api, count);
      const token = await api.connect();
      const first = (await api.children({ parentItemId: "drill-1" }, token)).body.result.structuredContent;
      assert.equal(first.items.length, Math.min(count, 20));
      assert.equal(first.nextCursor === null, count <= 20);
      if (count > 20) {
        const second = (await api.children({ parentItemId: "drill-1", cursor: first.nextCursor }, token)).body.result.structuredContent;
        assert.equal(second.items.length, count - 20);
        assert.equal(second.nextCursor, null);
      }
    });
  }
});

test("continuation markers cannot change Parent Item, connection, Household, or page limit", async (t) => {
  const api = await fixture(t);
  await seedChildren(api, 41, "home");
  await seedChildren(api, 21, "foreign", "foreign");
  const token = await api.connect();
  const otherConnection = await api.connect();
  const foreignToken = (await api.credentials(undefined, "other", "foreign")).access_token;
  const first = (await api.children({}, token)).body.result.structuredContent;
  const cursor = first.nextCursor;
  assert.equal(first.items.length, 20);
  const foreign = (await api.children({}, foreignToken)).body.result.structuredContent;
  assert.equal(foreign.parentItemId, "foreign");
  const altered = `${cursor[0] === "a" ? "b" : "a"}${cursor.slice(1)}`;
  for (const [arguments_, credential] of [
    [{ cursor: altered }, token], [{ cursor: "" }, token], [{ cursor: "../foreign" }, token],
    [{ cursor, parentItemId: "garage" }, token], [{ cursor }, otherConnection], [{ cursor }, foreignToken],
    [{ cursor: foreign.nextCursor }, token], [{ parentItemId: "private" }, token],
    [{ parentItemId: "foreign" }, token], [{ parentItemId: "../foreign" }, token],
  ]) {
    const response = await api.children(arguments_, credential);
    assert.equal(response.body.result.isError, true);
    assert.equal(response.body.result.structuredContent, undefined);
    assert.doesNotMatch(JSON.stringify(response.body), /Private Item|Other Home/);
  }
  const continued = (await api.children({ cursor, parentItemId: "home", householdId: "foreign",
    pageSize: 1000, limit: 1000, offset: 0 }, token)).body.result.structuredContent;
  assert.equal(continued.parentItemId, "home");
  assert.equal(continued.items.length, 20);
  assert.equal(continued.items[0].id, "child-20");
  const replay = (await api.children({ cursor }, token)).body.result.structuredContent;
  assert.deepEqual(replay.items, continued.items);
});

test("each continuation rechecks App Connection, Household Access, and deletion states", async (t) => {
  for (const change of ["revocation", "access", "account deletion", "owner deletion", "Household deletion"]) {
    await t.test(change, async (t) => {
      const api = await fixture(t);
      await seedChildren(api, 21);
      const token = await api.connect();
      const first = (await api.children({ parentItemId: "drill-1" }, token)).body.result.structuredContent;
      if (change === "revocation") {
        const connection = [...api.documents].find(([path]) => path.startsWith("appConnections/"))[1];
        connection.revokedAt = Date.now();
      } else if (change === "access") api.documents.delete("memberships/member");
      else {
        const path = { "account deletion": "accountDeletionJobs/member", "owner deletion": "accountDeletionJobs/owner",
          "Household deletion": "householdDeletionJobs/home" }[change];
        api.documents.set(path, { status: "pending" });
      }
      const response = await api.children({ parentItemId: "drill-1", cursor: first.nextCursor }, token);
      assert.equal(response.status, 401);
      assert.equal(response.body.result, undefined);
    });
  }
});

test("missing, deleted, corrupt, and unavailable Inventory returns listing errors", async (t) => {
  for (const change of ["missing Parent", "missing ancestor", "deleted Parent", "deleted ancestor", "deleted Child",
    "corrupt Child", "corrupt order", "storage failure"]) {
    await t.test(change, async (t) => {
      const api = await fixture(t);
      await seedChildren(api, 21);
      const token = await api.connect();
      const first = (await api.children({ parentItemId: "drill-1" }, token)).body.result.structuredContent;
      if (change === "missing Parent") api.documents.delete("households/home/items/drill-1");
      if (change === "missing ancestor") api.documents.delete("households/home/items/cabinet");
      if (change === "deleted Parent") api.documents.set("households/home/itemDeletionJobs/drill-1", {});
      if (change === "deleted ancestor") api.documents.set("households/home/itemDeletionJobs/garage", {});
      if (change === "deleted Child") api.documents.set("households/home/itemDeletionJobs/child-20", {});
      if (change === "corrupt Child") api.documents.get("households/home/items/child-20").householdId = "foreign";
      if (change === "corrupt order") api.documents.get("households/home/items/child-20").displayOrder = "20";
      if (change === "storage failure") api.database.collection = () => { throw new Error("Storage unavailable"); };
      const response = await api.children({ parentItemId: "drill-1", cursor: first.nextCursor }, token);
      assert.equal(response.body.result.isError, true);
      assert.equal(response.body.result.structuredContent, undefined);
    });
  }
});

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
  assert.deepEqual(tools.body.result.tools.map((tool) => tool.name), ["get_item", "list_children", "search_inventory"]);
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
  const gemini = await isolatedGeminiCli(t);
  const { storage, createTransport } = gemini;
  const api = await fixture(t);
  const flow = await api.begin();
  const credentials = await api.credentials(flow);
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
  assert.deepEqual(tools.tools.map((tool) => tool.name), ["get_item", "list_children", "search_inventory"]);
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
  await gemini.configure({ "mystuff-renewal": { httpUrl: `${api.origin}/mcp` } });
  assert.match(await gemini.list(), /Connected/);
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

test("the installed Gemini CLI browses all 41 synced root children through Firestore-backed pages", {
  skip: process.env.FIRESTORE_EMULATOR_HOST === undefined || process.env.GEMINI_CLI_BUNDLE === undefined,
}, async (t) => {
  const database = getFirestore(initializeApp({ projectId: "demo-mystuff-search" }, "mcp-browse"), "mcp-browse-tests");
  t.after(async () => {
    for (const collection of ["mcpClients", "mcpApprovals", "mcpCodes", "mcpAccessTokens", "mcpRefreshTokens",
      "appConnections", "mcpChildCursors"]) {
      await database.recursiveDelete(database.collection(collection));
    }
  });
  const api = await fixture(t, database);
  await seedChildren(api, 40, "home");
  // The existing Garage is the 41st direct Child Item, with a missing order.
  const flow = await api.begin();
  const credentials = await api.credentials(flow);
  const gemini = await isolatedGeminiCli(t);
  await gemini.storage.saveToken("mystuff-browse", {
    accessToken: credentials.access_token, refreshToken: credentials.refresh_token,
    tokenType: "Bearer", scope: "inventory:read", expiresAt: Date.now() + 3600000,
  }, flow.clientId, `${api.origin}/token`, `${api.origin}/mcp`);
  await gemini.configure({ "mystuff-browse": { httpUrl: `${api.origin}/mcp` } });
  assert.match(await gemini.list(), /mystuff-browse[^\n]*Connected/);
  const transport = await gemini.createTransport("mystuff-browse", { httpUrl: `${api.origin}/mcp` }, false, {
    emitMcpDiagnostic() {}, sanitizationConfig: {},
  });
  const client = new Client({ name: "mystuff-browse-verification", version: "1.0.0" });
  t.after(() => client.close());
  await client.connect(transport);
  assert.ok((await client.listTools()).tools.some((tool) => tool.name === "list_children"));
  const ids = [];
  const sizes = [];
  let cursor;
  do {
    const result = await client.callTool({ name: "list_children", arguments: cursor ? { cursor } : {} });
    assert.equal(result.isError, undefined);
    const page = result.structuredContent;
    assert.deepEqual(JSON.parse(result.content[0].text), page);
    assert.equal(page.parentItemId, "home");
    assert.ok(page.items.every((item) => Object.keys(item).sort().join(",") === "id,name"));
    sizes.push(page.items.length);
    ids.push(...page.items.map((item) => item.id));
    cursor = page.nextCursor;
    assert.ok(sizes.length <= 3);
  } while (cursor !== null);
  assert.deepEqual(sizes, [20, 20, 1]);
  assert.deepEqual(ids, [...Array.from({ length: 40 }, (_, index) => `child-${index}`), "garage"]);
  const nested = await client.callTool({ name: "list_children", arguments: { parentItemId: ids.at(-1) } });
  assert.deepEqual(nested.structuredContent, {
    parentItemId: "garage", items: [{ id: "cabinet", name: "Cabinet" }], nextCursor: null,
  });
  const first = await client.callTool({ name: "list_children", arguments: {} });
  await database.doc("memberships/member").delete();
  await assert.rejects(client.callTool({ name: "list_children", arguments: { cursor: first.structuredContent.nextCursor } }));
  t.diagnostic("Gemini CLI discovered list_children and read 41 synced root children in pages of 20, 20, 1; nested browsing and access-loss rejection passed.");
});

test("the installed Gemini CLI searches all 41 synced literal matches through Firestore-backed pages", {
  skip: process.env.FIRESTORE_EMULATOR_HOST === undefined || process.env.GEMINI_CLI_BUNDLE === undefined,
}, async (t) => {
  const database = getFirestore(initializeApp({ projectId: "demo-mystuff-search" }, "mcp-search"), "mcp-search-tests");
  t.after(async () => {
    for (const collection of ["mcpClients", "mcpApprovals", "mcpCodes", "mcpAccessTokens", "mcpRefreshTokens",
      "appConnections", "mcpSearchCursors"]) {
      await database.recursiveDelete(database.collection(collection));
    }
  });
  const api = await fixture(t, database);
  await seedChildren(api, 41);
  const flow = await api.begin();
  const credentials = await api.credentials(flow);
  const gemini = await isolatedGeminiCli(t);
  await gemini.storage.saveToken("mystuff-search", {
    accessToken: credentials.access_token, refreshToken: credentials.refresh_token,
    tokenType: "Bearer", scope: "inventory:read", expiresAt: Date.now() + 3600000,
  }, flow.clientId, `${api.origin}/token`, `${api.origin}/mcp`);
  await gemini.configure({ "mystuff-search": { httpUrl: `${api.origin}/mcp` } });
  assert.match(await gemini.list(), /mystuff-search[^\n]*Connected/);
  const transport = await gemini.createTransport("mystuff-search", { httpUrl: `${api.origin}/mcp` }, false, {
    emitMcpDiagnostic() {}, sanitizationConfig: {},
  });
  const client = new Client({ name: "mystuff-search-verification", version: "1.0.0" });
  t.after(() => client.close());
  await client.connect(transport);
  assert.equal((await client.listTools()).tools.find((tool) => tool.name === "search_inventory")?.annotations.readOnlyHint, true);
  const ids = [];
  const sizes = [];
  let cursor;
  do {
    const result = await client.callTool({ name: "search_inventory", arguments: { query: "duplicate", ...(cursor ? { cursor } : {}) } });
    assert.equal(result.isError, undefined);
    const page = result.structuredContent;
    assert.deepEqual(JSON.parse(result.content[0].text), page);
    assert.equal(page.literalOnlyFallback, true);
    assert.ok(page.items.every((item) => Object.keys(item).sort().join(",") === "description,id,name,path" &&
      item.path.join("/") === "Our Home/Garage/Cabinet/Drill/Duplicate"));
    sizes.push(page.items.length);
    ids.push(...page.items.map((item) => item.id));
    cursor = page.nextCursor;
    assert.ok(sizes.length <= 3);
  } while (cursor !== null);
  assert.deepEqual(sizes, [20, 20, 1]);
  assert.equal(new Set(ids).size, 41);
  assert.deepEqual(ids, Array.from({ length: 41 }, (_, index) => `child-${index}`).sort());
  const first = await client.callTool({ name: "search_inventory", arguments: { query: "duplicate" } });
  const marker = first.structuredContent.nextCursor;
  assert.equal((await client.callTool({ name: "search_inventory", arguments: { query: "drill", cursor: marker } })).isError, true);
  const other = await api.connect();
  assert.equal((await api.search({ query: "duplicate", cursor: marker }, other)).body.result.isError, true);
  const foreign = (await api.credentials(undefined, "other", "foreign")).access_token;
  assert.equal((await api.search({ query: "duplicate", cursor: marker }, foreign)).body.result.isError, true);
  assert.deepEqual((await api.search({ query: "Private Item", householdId: "foreign" }, credentials.access_token)).body.result.structuredContent.items, []);
  await database.doc("households/home/items/cabinet").delete();
  assert.equal((await client.callTool({ name: "search_inventory", arguments: { query: "duplicate", cursor: marker } })).isError, true);
  await database.doc("households/home/items/cabinet").set(api.documents.get("households/home/items/cabinet"));
  await database.doc("memberships/member").delete();
  await assert.rejects(client.callTool({ name: "search_inventory", arguments: { query: "duplicate", cursor: marker } }));
  t.diagnostic("Gemini CLI discovered search_inventory and read all 41 synced literal matches in pages of 20, 20, 1 with fallback marked; query/connection/Household isolation, unavailable Inventory, and access-loss rejection passed.");
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
  for (const [path, data] of documents) {
    if (/^households\/[^/]+\/items\/[^/]+$/.test(path)) data.tags ??= [];
  }
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
    seed: async (path, data) => {
      documents.set(path, data);
      if (firestore) await firestore.doc(path).set(data);
    },
    search: (arguments_, token) => call("tools/call", { name: "search_inventory", arguments: arguments_ }, token),
    children: (arguments_, token) => call("tools/call", { name: "list_children", arguments: arguments_ }, token),
    item: (itemId, token, extra = {}) => call("tools/call", { name: "get_item", arguments: { itemId, ...extra } }, token),
    documents, advance: (milliseconds) => { time += milliseconds; }, database, origin };
}

async function seedChildren(api, count, parentItemId = "drill-1", householdId = "home") {
  for (let index = count - 1; index >= 0; index--) {
    await api.seed(`households/${householdId}/items/child-${index}`, {
      householdId, parentItemId, name: "Duplicate", description: null, displayOrder: index,
      tags: [],
    });
  }
}

// Firebase Admin SDK is the external boundary. No MCP or authorization module
// is mocked: every request traverses Express, OAuth handlers, and the MCP SDK.
function fakeFirestore(documents) {
  let queue = Promise.resolve();
  const snapshot = (path) => ({ id: path.split("/").at(-1), exists: documents.has(path),
    data: () => structuredClone(documents.get(path)) });
  const database = {
    collection(path) {
      return { path, where: (field, operator, value) => {
        assert.equal(operator, "==");
        return { path, field, value };
      } };
    },
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
          get: async (ref) => ref.field || ref.where ? {
            docs: [...documents.keys()].filter((path) => path.startsWith(`${ref.path}/`) &&
              !path.slice(ref.path.length + 1).includes("/") && (!ref.field || documents.get(path)[ref.field] === ref.value))
              .sort((a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b))).map(snapshot),
          } : snapshot(ref.path),
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
