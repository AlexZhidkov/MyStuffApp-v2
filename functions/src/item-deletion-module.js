export function createItemDeletionModule({ database, bucket }) {
  return {
    async deleteItem({ householdId, itemId, memberId }) {
      const membership = await database.doc(`memberships/${memberId}`).get();
      if (!membership.exists || membership.data()?.householdId !== householdId) {
        throw new ItemDeletionMembershipError();
      }
      validateNonRootItemId(householdId, itemId);

      const itemReference = database.doc(
        `households/${householdId}/items/${itemId}`,
      );
      const cleanupReference = database.doc(
        `households/${householdId}/itemDeletionJobs/${itemId}`,
      );
      await database.runTransaction(async (transaction) => {
        const itemDocument = await transaction.get(itemReference);
        if (!itemDocument.exists) return;
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
        transaction.set(cleanupReference, { itemId });
        transaction.delete(itemReference);
      });
      return { itemId };
    },

    async cleanupItem({ householdId, itemId }) {
      const itemReference = database.doc(
        `households/${householdId}/items/${itemId}`,
      );
      await Promise.all([
        database.recursiveDelete(itemReference),
        deleteItemPhotos(bucket, householdId, itemId),
      ]);
      await database
        .doc(`households/${householdId}/itemDeletionJobs/${itemId}`)
        .delete();
    },
  };
}

export function validateItemDeletion({ householdId, itemId, item, childItems }) {
  validateNonRootItemId(householdId, itemId);
  if (item === undefined || item.data.householdId !== householdId) {
    throw new InvalidItemDeletionError("The Item no longer exists in this Household.");
  }
  if (typeof item.data.parentItemId !== "string") {
    throw new InvalidItemDeletionError("The Household root Item cannot be deleted.");
  }
  if (childItems.length > 0) {
    throw new InvalidItemDeletionError("An Item with Child Items cannot be deleted.");
  }
  return item.data;
}

function validateNonRootItemId(householdId, itemId) {
  if (itemId === householdId) {
    throw new InvalidItemDeletionError("The Household root Item cannot be deleted.");
  }
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
