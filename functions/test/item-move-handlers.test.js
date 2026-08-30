import assert from "node:assert/strict";
import { test } from "node:test";
import { createItemMoveHandlers } from "../src/item-move-handlers.js";

test("move handler rejects unauthenticated callers", async () => {
  const handlers = createItemMoveHandlers({
    itemMoveModule: { moveItem: async () => {} },
    logger: { error() {} },
  });

  await assert.rejects(
    handlers.moveInventoryItem({ auth: null, data: {} }),
    { code: "unauthenticated" },
  );
});

test("move handler delegates the requested Household and Item to the Member-scoped module", async () => {
  let request;
  const handlers = createItemMoveHandlers({
    itemMoveModule: {
      moveItem: async (value) => {
        request = value;
        return { itemId: "source", parentItemId: "target" };
      },
    },
    logger: { error() {} },
  });

  const result = await handlers.moveInventoryItem({
    auth: { uid: "member-1", token: { name: "Alex" } },
    data: {
      householdId: "household-1",
      itemId: "source",
      newParentItemId: "target",
    },
  });

  assert.deepEqual(result, { itemId: "source", parentItemId: "target" });
  assert.deepEqual(request, {
    householdId: "household-1",
    itemId: "source",
    newParentItemId: "target",
    updatedById: "member-1",
    updatedByDisplayName: "Alex",
  });
});
