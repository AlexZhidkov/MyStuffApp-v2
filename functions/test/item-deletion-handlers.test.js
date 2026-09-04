import assert from "node:assert/strict";
import { test } from "node:test";
import { createItemDeletionHandlers } from "../src/item-deletion-handlers.js";
import {
  InvalidItemDeletionError,
  ItemDeletionMembershipError,
} from "../src/item-deletion-module.js";

test("delete handler rejects unauthenticated callers", async () => {
  const handlers = createItemDeletionHandlers({
    itemDeletionModule: { deleteItem: async () => {} },
    logger: { error() {} },
  });

  await assert.rejects(
    handlers.deleteInventoryItem({ auth: null, data: {} }),
    { code: "unauthenticated" },
  );
});

test("delete handler delegates the requested Household and Item as the Member", async () => {
  let request;
  const handlers = createItemDeletionHandlers({
    itemDeletionModule: {
      async deleteItem(value) {
        request = value;
        return { itemId: "drill" };
      },
    },
    logger: { error() {} },
  });

  const result = await handlers.deleteInventoryItem({
    auth: { uid: "member-1" },
    data: { householdId: "household-1", itemId: "drill" },
  });

  assert.deepEqual(result, { itemId: "drill" });
  assert.deepEqual(request, {
    householdId: "household-1",
    itemId: "drill",
    memberId: "member-1",
  });
});

test("delete handler translates membership and Item validation failures", async () => {
  const membershipHandlers = createItemDeletionHandlers({
    itemDeletionModule: {
      async deleteItem() {
        throw new ItemDeletionMembershipError();
      },
    },
    logger: { error() {} },
  });
  const validationHandlers = createItemDeletionHandlers({
    itemDeletionModule: {
      async deleteItem() {
        throw new InvalidItemDeletionError("An Item with Child Items cannot be deleted.");
      },
    },
    logger: { error() {} },
  });
  const request = {
    auth: { uid: "member-1" },
    data: { householdId: "household-1", itemId: "drill" },
  };

  await assert.rejects(membershipHandlers.deleteInventoryItem(request), {
    code: "permission-denied",
  });
  await assert.rejects(validationHandlers.deleteInventoryItem(request), {
    code: "failed-precondition",
  });
});

test("cleanup handler delegates the durable deletion job", async () => {
  let cleanup;
  const handlers = createItemDeletionHandlers({
    itemDeletionModule: {
      async cleanupItem(value) {
        cleanup = value;
      },
    },
    logger: { error() {} },
  });

  await handlers.cleanupDeletedInventoryItem({
    params: { householdId: "household-1", itemId: "drill" },
  });

  assert.deepEqual(cleanup, { householdId: "household-1", itemId: "drill" });
});
