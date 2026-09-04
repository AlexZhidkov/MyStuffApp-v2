export function createItemDeletionModule({ database, bucket }) {
  return {
    async deleteItem({ householdId, itemId, memberId }) {
      const membership = await database.doc(`memberships/${memberId}`).get();
      if (!membership.exists || membership.data()?.householdId !== householdId) {
        throw new ItemDeletionMembershipError();
      }

      const itemReference = database.doc(
        `households/${householdId}/items/${itemId}`,
      );
      await database.runTransaction(async (transaction) => {
        const itemDocument = await transaction.get(itemReference);
        const childDocuments = await transaction.get(
          database
            .collection(`households/${householdId}/items`)
            .where("parentItemId", "==", itemId)
            .limit(1),
        );
        validateItemDeletion({
          householdId,
          itemId,
          item: itemDocument.exists
            ? { id: itemDocument.id, data: itemDocument.data() }
            : undefined,
          childItems: childDocuments.docs.map((document) => ({
            id: document.id,
            data: document.data(),
          })),
        });
        transaction.delete(itemReference);
      });

      await Promise.all([
        database.recursiveDelete(itemReference),
        deleteItemPhotos(bucket, householdId, itemId),
      ]);
      return { itemId };
    },
  };
}

export function validateItemDeletion({ householdId, itemId, item, childItems }) {
  if (item === undefined || item.data.householdId !== householdId) {
    throw new InvalidItemDeletionError("The Item no longer exists in this Household.");
  }
  if (itemId === householdId || typeof item.data.parentItemId !== "string") {
    throw new InvalidItemDeletionError("The Household root Item cannot be deleted.");
  }
  if (childItems.length > 0) {
    throw new InvalidItemDeletionError("An Item with Child Items cannot be deleted.");
  }
  return item.data;
}

async function deleteItemPhotos(bucket, householdId, itemId) {
  const itemPrefix = `households/${householdId}/items/${itemId}`;
  await Promise.all([
    bucket.deleteFiles({ prefix: `${itemPrefix}/` }),
    bucket.file(`${itemPrefix}.webp`).delete({ ignoreNotFound: true }),
    bucket.file(`${itemPrefix}-thumb.webp`).delete({ ignoreNotFound: true }),
  ]);
}

export class ItemDeletionMembershipError extends Error {
  constructor() {
    super("The Member does not belong to this Household.");
    this.name = "ItemDeletionMembershipError";
  }
}

export class InvalidItemDeletionError extends Error {
  constructor(message) {
    super(message);
    this.name = "InvalidItemDeletionError";
  }
}
