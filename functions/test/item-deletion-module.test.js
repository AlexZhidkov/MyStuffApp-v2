import assert from "node:assert/strict";
import { test } from "node:test";
import {
  createItemDeletionModule,
  InvalidItemDeletionError,
  validateItemDeletion,
} from "../src/item-deletion-module.js";

const householdId = "household-1";

test("Item deletion validation accepts a childless non-root Item", () => {
  const item = {
    id: "drill",
    data: { householdId, parentItemId: householdId },
  };

  assert.equal(
    validateItemDeletion({ householdId, itemId: item.id, item, childItems: [] }),
    item.data,
  );
});

test("Item deletion validation rejects the Household root", () => {
  const item = {
    id: householdId,
    data: { householdId, parentItemId: null },
  };

  assert.throws(
    () => validateItemDeletion({ householdId, itemId: item.id, item, childItems: [] }),
    InvalidItemDeletionError,
  );
});

test("Item deletion validation rejects an Item with Child Items", () => {
  const item = {
    id: "garage",
    data: { householdId, parentItemId: householdId },
  };
  const childItems = [
    { id: "drill", data: { householdId, parentItemId: item.id } },
  ];

  assert.throws(
    () => validateItemDeletion({ householdId, itemId: item.id, item, childItems }),
    InvalidItemDeletionError,
  );
});

test("deletion schedules durable cleanup that can retry after a partial failure", async () => {
  const deletedDocuments = [];
  const scheduledCleanupJobs = [];
  const completedCleanupJobs = [];
  const recursivelyDeleted = [];
  const deletedPrefixes = [];
  const deletedFiles = [];
  let itemExists = true;
  let failPhotoCleanup = true;
  const itemReference = { path: `households/${householdId}/items/drill` };
  const cleanupReference = {
    path: `households/${householdId}/itemDeletionJobs/drill`,
    async delete() {
      completedCleanupJobs.push(this.path);
    },
  };
  const database = {
    doc(path) {
      if (path === "memberships/member-1") {
        return {
          async get() {
            return { exists: true, data: () => ({ householdId }) };
          },
        };
      }
      if (path === cleanupReference.path) return cleanupReference;
      return itemReference;
    },
    collection() {
      return { where: () => ({ limit: () => ({ childQuery: true }) }) };
    },
    async runTransaction(action) {
      return action({
        async get(target) {
          if (target === itemReference) {
            return {
              exists: itemExists,
              id: "drill",
              data: () => ({
                householdId,
                parentItemId: householdId,
                photoUrl: `gs://bucket/households/${householdId}/items/drill.webp`,
                photoThumbnailUrl:
                  `gs://bucket/households/${householdId}/items/drill-thumb.webp`,
              }),
            };
          }
          return { docs: [] };
        },
        delete(reference) {
          deletedDocuments.push(reference.path);
          itemExists = false;
        },
        set(reference, data) {
          scheduledCleanupJobs.push({ path: reference.path, data });
        },
      });
    },
    async recursiveDelete(reference) {
      recursivelyDeleted.push(reference.path);
    },
  };
  const bucket = {
    async deleteFiles({ prefix }) {
      deletedPrefixes.push(prefix);
      if (failPhotoCleanup) {
        failPhotoCleanup = false;
        throw new Error("Storage is unavailable.");
      }
    },
    file(path) {
      return {
        async delete(options) {
          deletedFiles.push({ path, options });
        },
      };
    },
  };
  const deletion = createItemDeletionModule({ database, bucket });

  const request = {
    householdId,
    itemId: "drill",
    memberId: "member-1",
  };

  const result = await deletion.deleteItem(request);

  await assert.rejects(
    deletion.cleanupItem({ householdId, itemId: "drill" }),
    /Storage is unavailable/,
  );
  await deletion.cleanupItem({ householdId, itemId: "drill" });

  assert.deepEqual(result, { itemId: "drill" });
  assert.deepEqual(deletedDocuments, [itemReference.path]);
  assert.deepEqual(scheduledCleanupJobs, [
    { path: cleanupReference.path, data: { itemId: "drill" } },
  ]);
  assert.deepEqual(recursivelyDeleted, [itemReference.path, itemReference.path]);
  assert.deepEqual(deletedPrefixes, [
    `households/${householdId}/items/drill/`,
    `households/${householdId}/items/drill/`,
  ]);
  assert.deepEqual(deletedFiles, [
    {
      path: `households/${householdId}/items/drill.webp`,
      options: { ignoreNotFound: true },
    },
    {
      path: `households/${householdId}/items/drill-thumb.webp`,
      options: { ignoreNotFound: true },
    },
    {
      path: `households/${householdId}/items/drill.webp`,
      options: { ignoreNotFound: true },
    },
    {
      path: `households/${householdId}/items/drill-thumb.webp`,
      options: { ignoreNotFound: true },
    },
  ]);
  assert.deepEqual(completedCleanupJobs, [cleanupReference.path]);
});
