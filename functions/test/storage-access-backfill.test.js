import assert from "node:assert/strict";
import { test } from "node:test";
import { createStorageAccessBackfill } from "../src/storage-access-backfill.js";

test("Storage access backfill rebuilds active members without changing data in dry-run mode", async () => {
  const database = fakeDatabase({
    "households/household-1": {
      ownerMemberId: "member-1",
      storageAccessRevoked: false,
    },
    "memberships/member-1": { householdId: "household-1" },
    "memberships/member-2": { householdId: "household-1" },
    "accountDeletionJobs/member-2": { status: "pending" },
  });

  const report = await createStorageAccessBackfill({ database }).run({ dryRun: true });

  assert.deepEqual(report.updates, [{
    householdId: "household-1",
    storageMemberIds: { "member-1": true },
    storageAccessRevoked: false,
  }]);
  assert.equal(database.data("households/household-1").storageMemberIds, undefined);
});

test("Storage access backfill preserves deletion revocation", async () => {
  const database = fakeDatabase({
    "households/household-1": { ownerMemberId: "member-1" },
    "memberships/member-1": { householdId: "household-1" },
    "memberships/member-2": { householdId: "household-1" },
    "householdDeletionJobs/household-1": { status: "pending" },
  });

  await createStorageAccessBackfill({ database }).run();

  assert.deepEqual(database.data("households/household-1"), {
    ownerMemberId: "member-1",
    storageMemberIds: { "member-1": true, "member-2": true },
    storageAccessRevoked: true,
  });
});

function fakeDatabase(initialDocuments) {
  const documents = new Map(Object.entries(initialDocuments));
  return {
    collection(name) {
      return collectionReference(name, documents);
    },
    doc(path) {
      return documentReference(path, documents);
    },
    data(path) {
      return documents.get(path);
    },
  };
}

function collectionReference(name, documents) {
  return {
    async get() {
      return {
        docs: [...documents.entries()]
          .filter(([path]) => path.split("/").length === 2 && path.startsWith(`${name}/`))
          .map(([path, data]) => snapshot(path, data, documents)),
      };
    },
    where(field, operator, value) {
      assert.equal(operator, "==");
      return {
        async get() {
          return {
            docs: [...documents.entries()]
              .filter(([path]) => path.startsWith(`${name}/`))
              .filter(([, data]) => data[field] === value)
              .map(([path, data]) => snapshot(path, data, documents)),
          };
        },
      };
    },
  };
}

function documentReference(path, documents) {
  return {
    path,
    id: path.split("/").at(-1),
    async get() {
      const data = documents.get(path);
      return snapshot(path, data, documents);
    },
    async update(data) {
      documents.set(path, { ...documents.get(path), ...data });
    },
  };
}

function snapshot(path, data, documents) {
  return {
    id: path.split("/").at(-1),
    ref: documentReference(path, documents),
    exists: data !== undefined,
    data: () => data,
  };
}
