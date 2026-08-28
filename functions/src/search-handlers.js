import { HttpsError } from "firebase-functions/v2/https";
import { TransientEmbeddingError } from "./gemini-embedder.js";
import { TransientSearchIndexError } from "./firestore-search-repository.js";
import { InvalidSearchQueryError, SearchMembershipError } from "./search-module.js";

export function createSearchHandlers({ searchModule, logger }) {
  return {
    async refreshItemIndex(event) {
      try {
        await searchModule.refreshItemIndex({
          householdId: event.params.householdId,
          itemId: event.params.itemId,
        });
      } catch (error) {
        if (
          error instanceof TransientEmbeddingError ||
          error instanceof TransientSearchIndexError
        ) {
          throw error;
        }
        logger.error("Item indexing failed permanently.", error);
      }
    },

    async searchInventory(request) {
      const memberId = request.auth?.uid;
      if (memberId === undefined) {
        throw new HttpsError("unauthenticated", "Sign in to Search your Household.");
      }
      try {
        return await searchModule.searchInventory({
          memberId,
          query: request.data?.query,
        });
      } catch (error) {
        if (error instanceof InvalidSearchQueryError) {
          throw new HttpsError("invalid-argument", error.message);
        }
        if (error instanceof SearchMembershipError) {
          throw new HttpsError("permission-denied", error.message);
        }
        logger.error("Search failed.", error);
        throw new HttpsError("internal", "Search is temporarily unavailable.");
      }
    },
  };
}
