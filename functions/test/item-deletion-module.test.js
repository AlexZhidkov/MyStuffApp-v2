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

test("deleting an Item removes its document tree and every stored photo", async () => {
  const deletedDocuments = [];
  const recursivelyDeleted = [];
  const deletedPrefixes = [];
  const deletedFiles = [];
  const itemReference = { path: `households/${householdId}/items/drill` };
  const database = {
    doc(path) {
      if (path === "memberships/member-1") {
        return {
          async get() {
            return { exists: true, data: () => ({ householdId }) };
          },
        };
      }
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
              exists: true,
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

  const result = await deletion.deleteItem({
    householdId,
    itemId: "drill",
    memberId: "member-1",
  });

  assert.deepEqual(result, { itemId: "drill" });
  assert.deepEqual(deletedDocuments, [itemReference.path]);
  assert.deepEqual(recursivelyDeleted, [itemReference.path]);
  assert.deepEqual(deletedPrefixes, [`households/${householdId}/items/drill/`]);
  assert.deepEqual(deletedFiles, [
    {
      path: `households/${householdId}/items/drill.webp`,
      options: { ignoreNotFound: true },
    },
    {
      path: `households/${householdId}/items/drill-thumb.webp`,
      options: { ignoreNotFound: true },
    },
  ]);
});
