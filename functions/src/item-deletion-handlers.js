import { HttpsError } from "firebase-functions/v2/https";
import {
  InvalidItemDeletionError,
  ItemDeletionMembershipError,
} from "./item-deletion-module.js";

export function createItemDeletionHandlers({ itemDeletionModule, logger }) {
  return {
    async deleteInventoryItem(request) {
      const memberId = request.auth?.uid;
      if (memberId === undefined) {
        throw new HttpsError("unauthenticated", "Sign in to delete an Item.");
      }
      try {
        return await itemDeletionModule.deleteItem({
          householdId: request.data?.householdId,
          itemId: request.data?.itemId,
          memberId,
        });
      } catch (error) {
        if (error instanceof ItemDeletionMembershipError) {
          throw new HttpsError("permission-denied", error.message);
        }
        if (error instanceof InvalidItemDeletionError) {
          throw new HttpsError("failed-precondition", error.message);
        }
        logger.error("Item deletion failed.", error);
        throw new HttpsError("internal", "The Item could not be deleted.");
      }
    },

    async cleanupDeletedInventoryItem(event) {
      try {
        await itemDeletionModule.cleanupItem({
          householdId: event.params.householdId,
          itemId: event.params.itemId,
        });
      } catch (error) {
        logger.error("Item deletion cleanup failed.", error);
        throw error;
      }
    },
  };
}
