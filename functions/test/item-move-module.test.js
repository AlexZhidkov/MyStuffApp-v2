import assert from "node:assert/strict";
import { test } from "node:test";
import {
  InvalidItemMoveError,
  validateItemMove,
} from "../src/item-move-module.js";

const householdId = "household-1";

test("Item move validation preserves the subtree by changing only the source Parent Item", () => {
  const source = { id: "source", data: { householdId, parentItemId: householdId } };
  const child = { id: "child", data: { householdId, parentItemId: "source" } };
  const target = { id: "target", data: { householdId, parentItemId: householdId } };

  const result = validateItemMove({
    householdId,
    itemId: source.id,
    newParentItemId: target.id,
    items: [
      { id: householdId, data: { householdId, parentItemId: null } },
      source,
      child,
      target,
    ],
  });

  assert.equal(result, source.data);
  assert.equal(source.data.parentItemId, householdId);
  assert.equal(child.data.parentItemId, source.id);
});

test("Item move validation rejects a descendant Parent Item", () => {
  assert.throws(
    () => validateItemMove({
      householdId,
      itemId: "source",
      newParentItemId: "child",
      items: [
        { id: householdId, data: { householdId, parentItemId: null } },
        { id: "source", data: { householdId, parentItemId: householdId } },
        { id: "child", data: { householdId, parentItemId: "source" } },
      ],
    }),
    InvalidItemMoveError,
  );
});

test("Item move validation rejects missing and cross-Household Items", () => {
  const items = [
    { id: householdId, data: { householdId, parentItemId: null } },
    { id: "source", data: { householdId, parentItemId: householdId } },
  ];

  assert.throws(
    () => validateItemMove({
      householdId,
      itemId: "source",
      newParentItemId: "missing",
      items,
    }),
    InvalidItemMoveError,
  );
  assert.throws(
    () => validateItemMove({
      householdId,
      itemId: "source",
      newParentItemId: "other",
      items: [...items, { id: "other", data: { householdId: "household-2" } }],
    }),
    InvalidItemMoveError,
  );
});
