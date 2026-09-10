import assert from "node:assert/strict";
import { test } from "node:test";
import { createHouseholdAccessHandlers } from "../src/household-access-handlers.js";

test("claiming requires a verified Google Account", async () => {
  const handlers = handlersFor(async () => ({ householdId: null }));
  await assert.rejects(handlers.claimHouseholdAccess({ auth: null, data: {} }), { code: "unauthenticated" });
  await assert.rejects(handlers.claimHouseholdAccess({ auth: { uid: "member-1", token: {} }, data: {} }), { code: "permission-denied" });
});

test("claiming delegates the authenticated identity and removal delegates the owner request", async () => {
  const requests = [];
  const handlers = createHouseholdAccessHandlers({
    householdAccess: {
      async claimMatchingAccess(request) { requests.push(request); return { householdId: null }; },
      async removeAccess(request) { requests.push(request); return { email: "sam@example.com" }; },
    },
    logger: { error() {} },
  });
  const auth = { uid: "member-1", token: { email: "Alex@Example.com", email_verified: true, name: "Alex", firebase: { sign_in_provider: "google.com" } } };

  assert.deepEqual(await handlers.claimHouseholdAccess({ auth, data: {} }), { householdId: null });
  assert.deepEqual(await handlers.removeHouseholdAccess({ auth, data: { householdId: "household-1", email: "sam@example.com" } }), { email: "sam@example.com" });
  assert.deepEqual(requests, [
    { memberId: "member-1", authenticatedEmail: "Alex@Example.com", displayName: "Alex" },
    { memberId: "member-1", householdId: "household-1", email: "sam@example.com" },
  ]);
});

function handlersFor(claimMatchingAccess) {
  return createHouseholdAccessHandlers({
    householdAccess: { claimMatchingAccess, removeAccess: async () => ({}) },
    logger: { error() {} },
  });
}
