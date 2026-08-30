import { HttpsError } from "firebase-functions/v2/https";
import {
  InvalidItemMoveError,
  ItemMoveMembershipError,
} from "./item-move-module.js";

export function createItemMoveHandlers({ itemMoveModule, logger }) {
  return {
    async moveInventoryItem(request) {
      const memberId = request.auth?.uid;
      if (memberId === undefined) {
        throw new HttpsError("unauthenticated", "Sign in to move an Item.");
      }
      try {
        return await itemMoveModule.moveItem({
          householdId: request.data?.householdId,
          itemId: request.data?.itemId,
          newParentItemId: request.data?.newParentItemId,
          updatedById: memberId,
          updatedByDisplayName:
            request.auth.token?.name ?? request.auth.token?.email ?? "Household Member",
        });
      } catch (error) {
        if (error instanceof ItemMoveMembershipError) {
          throw new HttpsError("permission-denied", error.message);
        }
        if (error instanceof InvalidItemMoveError) {
          throw new HttpsError("failed-precondition", error.message);
        }
        logger.error("Item move failed.", error);
        throw new HttpsError("internal", "The Item could not be moved.");
      }
    },
  };
}
