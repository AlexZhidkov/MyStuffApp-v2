import assert from "node:assert/strict";
import { test } from "node:test";
import { createDeletionHandlers, isRecentAuthentication } from "../src/deletion-handlers.js";

test("Account Deletion requires verified and recent Google authentication", async () => {
  const handlers = createDeletionHandlers({
    deletion: { async requestAccount() { assert.fail("must not delegate"); } },
    authentication: {},
    logger: { error() {} },
    now: () => 1_000_000,
  });

  await assert.rejects(handlers.requestAccountDeletion({ auth: null }), {
    code: "unauthenticated",
  });
  await assert.rejects(handlers.requestAccountDeletion({
    auth: {
      uid: "member-1",
      token: { email: "alex@example.com", email_verified: true, auth_time: 699 },
    },
  }), { code: "unauthenticated" });
});

test("a recent request delegates the verified identity", async () => {
  let input;
  const handlers = createDeletionHandlers({
    deletion: {
      async requestAccount(value) {
        input = value;
        return { deletesHousehold: false };
      },
    },
    authentication: {},
    logger: { error() {} },
    now: () => 1_000_000,
  });

  const result = await handlers.requestAccountDeletion({
    auth: {
      uid: "member-1",
      token: { email: " Alex@Example.com ", email_verified: true, auth_time: 999 },
    },
  });

  assert.deepEqual(input, {
    memberId: "member-1",
    email: "alex@example.com",
    confirmationHouseholdName: undefined,
  });
  assert.deepEqual(result, { deletesHousehold: false });
});

test("recent authentication accepts a five minute window", () => {
  assert.equal(isRecentAuthentication({ auth_time: 700 }, 1_000_000), true);
  assert.equal(isRecentAuthentication({ auth_time: 699 }, 1_000_000), false);
});

test("cleanup failures are logged and rethrown for platform retries", async () => {
  const failure = new Error("Storage unavailable");
  const logged = [];
  const handlers = createDeletionHandlers({
    deletion: { async cleanupAccount() { throw failure; } },
    authentication: {},
    logger: { error(...values) { logged.push(values); } },
  });

  await assert.rejects(
    handlers.cleanupAccountDeletion({ data: { exists: true, data: () => ({ memberId: "m" }) } }),
    failure,
  );
  assert.equal(logged.length, 1);
});

test("manual email deletion previews before explicit execution", async () => {
  const requests = [];
  const plan = {
    deletesHousehold: true,
    householdName: "Our Home",
    memberCount: 2,
    itemCount: 5,
  };
  const handlers = createDeletionHandlers({
    deletion: {
      async previewAccount(input) {
        requests.push(["preview", input]);
        return plan;
      },
      async requestAccount(input) {
        requests.push(["request", input]);
        return plan;
      },
    },
    authentication: {
      async getUserByEmail(email) {
        requests.push(["lookup", email]);
        return { uid: "owner-1" };
      },
    },
    logger: { error() {} },
  });
  const previewResponse = responseRecorder();
  await handlers.manuallyDeleteAccount(
    { method: "POST", body: { email: " Owner@Example.com " } },
    previewResponse,
  );
  assert.equal(previewResponse.statusCode, 200);
  assert.deepEqual(previewResponse.body, {
    execute: false,
    memberId: "owner-1",
    ...plan,
  });

  const executeResponse = responseRecorder();
  await handlers.manuallyDeleteAccount(
    { method: "POST", body: { email: "owner@example.com", execute: true } },
    executeResponse,
  );
  assert.equal(executeResponse.statusCode, 202);
  assert.deepEqual(executeResponse.body, plan);
  assert.deepEqual(requests.at(-1), ["request", {
    memberId: "owner-1",
    email: "owner@example.com",
    confirmationHouseholdName: "Our Home",
  }]);
});

function responseRecorder() {
  return {
    statusCode: null,
    body: null,
    status(code) {
      this.statusCode = code;
      return this;
    },
    json(body) {
      this.body = body;
      return this;
    },
  };
}
