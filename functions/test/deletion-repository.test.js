import assert from "node:assert/strict";
import { test } from "node:test";
import { createDeletionRepository } from "../src/deletion-repository.js";

test("Account Deletion atomically removes a Member from the Storage access projection", async () => {
  const database = fakeDatabase({
    "households/household-1": {
      storageMemberIds: { "member-1": true, "member-2": true },
      storageAccessRevoked: false,
    },
  });
  const repository = createDeletionRepository({ database });

  await repository.enqueueAccount({
    memberId: "member-2",
    householdId: "household-1",
    deletesHousehold: false,
  });

  assert.equal(database.data("accountDeletionJobs/member-2").status, "pending");
  assert.deepEqual(database.data("households/household-1").storageMemberIds, {
    "member-1": true,
  });
});

test("Household Deletion atomically revokes the Household Storage projection", async () => {
  const database = fakeDatabase({
    "households/household-1": {
      storageMemberIds: { "member-1": true },
      storageAccessRevoked: false,
    },
  });
  const repository = createDeletionRepository({ database });

  await repository.enqueueHousehold({ householdId: "household-1" });

  assert.equal(database.data("householdDeletionJobs/household-1").status, "pending");
  assert.equal(database.data("households/household-1").storageAccessRevoked, true);
});

function fakeDatabase(initialDocuments) {
  const documents = new Map(Object.entries(initialDocuments));
  return {
    doc(path) {
      return { path };
    },
    async runTransaction(work) {
      const writes = [];
      const transaction = {
        async get(reference) {
          const data = documents.get(reference.path);
          return { exists: data !== undefined, data: () => data };
        },
        create(reference, data) {
          writes.push(() => documents.set(reference.path, data));
        },
        update(reference, data) {
          writes.push(() => documents.set(reference.path, {
            ...documents.get(reference.path),
            ...data,
          }));
        },
      };
      const result = await work(transaction);
      writes.forEach((write) => write());
      return result;
    },
    data(path) {
      return documents.get(path);
    },
  };
}
