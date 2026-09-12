import assert from "node:assert/strict";
import { test } from "node:test";
import {
  InvalidDeletionConfirmationError,
  createDeletionModule,
} from "../src/deletion-module.js";

test("Household deletion requires the current Household name", async () => {
  const calls = [];
  const job = {
    memberId: "owner-1",
    email: "owner@example.com",
    householdId: "household-1",
    deletesHousehold: true,
  };
  const deletion = createDeletionModule({
    repository: fakeRepository(job, calls),
    authentication: {},
    bucket: {},
  });

  await assert.rejects(
    deletion.requestAccount({
      memberId: "owner-1",
      email: "owner@example.com",
      confirmationHouseholdName: "our home",
    }),
    InvalidDeletionConfirmationError,
  );
  assert.deepEqual(calls, []);
});

test("non-Owner Account Deletion anonymizes shared content before deleting Authentication", async () => {
  const calls = [];
  const job = {
    memberId: "member-2",
    email: "sam@example.com",
    householdId: "household-1",
    deletesHousehold: false,
  };
  const deletion = createDeletionModule({
    repository: fakeRepository(job, calls),
    authentication: {
      async deleteUser(memberId) { calls.push(["deleteUser", memberId]); },
    },
    bucket: { async deleteFiles() { assert.fail("Storage must remain"); } },
  });

  await deletion.cleanupAccount(job);

  assert.deepEqual(calls, [
    ["removeMemberData", job],
    ["deleteUser", "member-2"],
    ["completeAccount", "member-2"],
  ]);
});

test("Owner Account Deletion removes the Household and Authentication", async () => {
  const calls = [];
  const job = {
    memberId: "owner-1",
    email: "owner@example.com",
    householdId: "household-1",
    deletesHousehold: true,
  };
  const deletion = createDeletionModule({
    repository: fakeRepository(job, calls),
    authentication: {
      async deleteUser(memberId) { calls.push(["deleteUser", memberId]); },
    },
    bucket: {
      async deleteFiles(options) { calls.push(["deleteFiles", options]); },
    },
  });

  await deletion.cleanupAccount(job);

  assert.deepEqual(calls, [
    ["removeHouseholdData", "household-1"],
    ["deleteFiles", { prefix: "households/household-1/" }],
    ["deleteUser", "owner-1"],
    ["completeAccount", "owner-1"],
  ]);
});

test("standalone Household deletion preserves the Owner Authentication account", async () => {
  const calls = [];
  const job = { memberId: "owner-1", householdId: "household-1" };
  const deletion = createDeletionModule({
    repository: fakeRepository(job, calls),
    authentication: {
      async deleteUser() { assert.fail("Authentication must remain"); },
    },
    bucket: {
      async deleteFiles(options) { calls.push(["deleteFiles", options]); },
    },
  });

  await deletion.cleanupHousehold(job);

  assert.deepEqual(calls, [
    ["removeHouseholdData", "household-1"],
    ["deleteFiles", { prefix: "households/household-1/" }],
    ["completeHousehold", "household-1"],
  ]);
});

test("missing Authentication user keeps cleanup idempotent", async () => {
  const calls = [];
  const job = { memberId: "member-2", email: "sam@example.com", householdId: null };
  const deletion = createDeletionModule({
    repository: fakeRepository(job, calls),
    authentication: {
      async deleteUser() {
        const error = new Error("missing");
        error.code = "auth/user-not-found";
        throw error;
      },
    },
    bucket: { async deleteFiles() {} },
  });

  await deletion.cleanupAccount(job);

  assert.deepEqual(calls, [
    ["removeMemberData", job],
    ["completeAccount", "member-2"],
  ]);
});

function fakeRepository(job, calls) {
  return {
    async accountPlan(input) {
      return {
        ...input,
        householdId: job.householdId ?? null,
        householdName: job.deletesHousehold ? "Our Home" : null,
        deletesHousehold: job.deletesHousehold ?? false,
        memberCount: job.deletesHousehold ? 2 : 0,
        itemCount: job.deletesHousehold ? 5 : 0,
      };
    },
    async householdPlan(input) {
      return { ...input, householdName: "Our Home", memberCount: 2, itemCount: 5 };
    },
    async enqueueAccount(plan) { calls.push(["enqueueAccount", plan]); },
    async enqueueHousehold(plan) { calls.push(["enqueueHousehold", plan]); },
    async removeMemberData(value) { calls.push(["removeMemberData", value]); },
    async removeHouseholdData(id) { calls.push(["removeHouseholdData", id]); },
    async completeAccount(id) { calls.push(["completeAccount", id]); },
    async completeHousehold(id) { calls.push(["completeHousehold", id]); },
    async accountJob() { return job; },
  };
}
