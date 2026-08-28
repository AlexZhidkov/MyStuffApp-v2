import { createHash } from "node:crypto";

const CONCEPTUAL_RESULT_LIMIT = 10;
const EMBEDDING_MODEL_VERSION = "gemini-embedding-2:768";

export function createSearchModule({ repository, embedder }) {
  return {
    async refreshItemIndex({ householdId, itemId }) {
      const item = await repository.getItem(householdId, itemId);
      if (item === null || item.parentItemId === null || itemId === householdId) {
        await repository.deleteSearchRecord(householdId, itemId);
        return;
      }
      const text = itemEmbeddingText(item);
      const sourceHash = hash(text);
      const currentRecord = await repository.getSearchRecord(householdId, itemId);
      if (
        currentRecord?.sourceHash === sourceHash &&
        currentRecord?.modelVersion === EMBEDDING_MODEL_VERSION
      ) {
        return;
      }
      const embedding = await embedder.embedItem(text);
      const latestItem = await repository.getItem(householdId, itemId);
      if (
        latestItem === null ||
        latestItem.parentItemId === null ||
        itemId === householdId
      ) {
        await repository.deleteSearchRecord(householdId, itemId);
        return;
      }
      if (hash(itemEmbeddingText(latestItem)) !== sourceHash) return;
      await repository.putSearchRecord(householdId, itemId, {
        embedding,
        sourceHash,
        modelVersion: EMBEDDING_MODEL_VERSION,
      });
    },

    async searchInventory({ memberId, query }) {
      const householdId = await repository.findHouseholdIdForMember(memberId);
      if (householdId === null) {
        throw new SearchMembershipError();
      }
      if (typeof query !== "string") {
        throw new InvalidSearchQueryError();
      }
      const normalizedQuery = query.trim();
      if ((normalizedQuery.match(/[\p{L}\p{N}]/gu) ?? []).length < 3) {
        throw new InvalidSearchQueryError();
      }
      const queryVector = await embedder.embedQuery(
        `task: search result | query: ${normalizedQuery}`,
      );
      const itemIds = await repository.findNearest(
        householdId,
        queryVector,
        CONCEPTUAL_RESULT_LIMIT,
      );
      return { itemIds };
    },
  };
}

function itemEmbeddingText(item) {
  const tags = item.tags.join(", ");
  const description = item.description ?? "";
  return `title: ${item.name} | text: tags: ${tags} | description: ${description}`;
}

function hash(value) {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

export class SearchMembershipError extends Error {
  constructor() {
    super("The Member does not belong to a Household.");
    this.name = "SearchMembershipError";
  }
}

export class InvalidSearchQueryError extends Error {
  constructor() {
    super("Search queries require at least three letters or digits.");
    this.name = "InvalidSearchQueryError";
  }
}
