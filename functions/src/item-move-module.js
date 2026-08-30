import { FieldValue } from "firebase-admin/firestore";

export function createItemMoveModule({ database }) {
  return {
    async moveItem({ householdId, itemId, newParentItemId, updatedById, updatedByDisplayName }) {
      const membership = await database.doc(`memberships/${updatedById}`).get();
      if (!membership.exists || membership.data()?.householdId !== householdId) {
        throw new ItemMoveMembershipError();
      }

      return database.runTransaction(async (transaction) => {
        const items = new Map();
        const readItem = async (readItemId) => {
          if (items.has(readItemId)) return items.get(readItemId);
          const document = await transaction.get(
            database.doc(`households/${householdId}/items/${readItemId}`),
          );
          const item = document.exists
            ? { id: document.id, data: document.data() }
            : undefined;
          items.set(readItemId, item);
          return item;
        };

        await readItem(itemId);
        const visited = new Set();
        let ancestorId = newParentItemId;
        while (!visited.has(ancestorId)) {
          visited.add(ancestorId);
          const ancestor = await readItem(ancestorId);
          if (ancestor === undefined || typeof ancestor.data.parentItemId !== "string") {
            break;
          }
          if (ancestorId === householdId) break;
          ancestorId = ancestor.data.parentItemId;
        }

        const source = validateItemMove({
          householdId,
          itemId,
          newParentItemId,
          items: [...items.values()].filter((item) => item !== undefined),
        });
        transaction.update(
          database.doc(`households/${householdId}/items/${itemId}`),
          {
            parentItemId: newParentItemId,
            updatedAt: FieldValue.serverTimestamp(),
            updatedById,
            updatedByDisplayName,
          },
        );
        return { itemId, parentItemId: newParentItemId };
      });
    },
  };
}

export function validateItemMove({ householdId, itemId, newParentItemId, items }) {
  const itemsById = new Map(items.map((item) => [item.id, item.data]));
  const source = itemsById.get(itemId);
  if (source === undefined || source.householdId !== householdId) {
    throw new InvalidItemMoveError("The Item no longer exists in this Household.");
  }
  if (itemId === householdId || typeof source.parentItemId !== "string") {
    throw new InvalidItemMoveError("The Household root Item cannot be moved.");
  }
  if (typeof newParentItemId !== "string") {
    throw new InvalidItemMoveError("The selected Parent Item is invalid.");
  }
  if (itemId === newParentItemId) {
    throw new InvalidItemMoveError("An Item cannot be its own Parent Item.");
  }

  let ancestorId = newParentItemId;
  const visited = new Set();
  while (true) {
    if (ancestorId === itemId) {
      throw new InvalidItemMoveError(
        "An Item cannot be moved beneath one of its Child Items.",
      );
    }
    if (visited.has(ancestorId)) {
      throw new InvalidItemMoveError("The Inventory already contains a cycle.");
    }
    visited.add(ancestorId);
    const ancestor = itemsById.get(ancestorId);
    if (ancestor === undefined || ancestor.householdId !== householdId) {
      throw new InvalidItemMoveError(
        "The selected Parent Item no longer exists in this Household.",
      );
    }
    if (ancestorId === householdId) return source;
    if (typeof ancestor.parentItemId !== "string") {
      throw new InvalidItemMoveError("The selected Parent Item is invalid.");
    }
    ancestorId = ancestor.parentItemId;
  }
}

export class ItemMoveMembershipError extends Error {
  constructor() {
    super("The Member does not belong to this Household.");
    this.name = "ItemMoveMembershipError";
  }
}

export class InvalidItemMoveError extends Error {
  constructor(message) {
    super(message);
    this.name = "InvalidItemMoveError";
  }
}
