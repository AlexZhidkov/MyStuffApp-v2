import { FieldValue } from "firebase-admin/firestore";

const SEARCH_INDEX_COLLECTION = "searchIndex";
const SEARCH_EMBEDDING_FIELD = "searchEmbedding";
const TRANSIENT_FIRESTORE_CODES = new Set([
  4,
  8,
  10,
  13,
  14,
  "deadline-exceeded",
  "resource-exhausted",
  "aborted",
  "internal",
  "unavailable",
]);

export function createFirestoreSearchRepository(database) {
  function indexCollection(householdId) {
    return database.collection(`households/${householdId}/${SEARCH_INDEX_COLLECTION}`);
  }

  return {
    async findHouseholdIdForMember(memberId) {
      return classifyTransientFailure(async () => {
        const snapshot = await database.doc(`memberships/${memberId}`).get();
        if (!snapshot.exists) return null;
        const householdId = snapshot.get("householdId");
        return typeof householdId === "string" ? householdId : null;
      });
    },

    async findNearest(householdId, queryVector, limit) {
      return classifyTransientFailure(async () => {
        const snapshot = await indexCollection(householdId)
          .findNearest(
            SEARCH_EMBEDDING_FIELD,
            FieldValue.vector(queryVector),
            { limit, distanceMeasure: "COSINE" },
          )
          .get();
        return snapshot.docs.map((document) => document.id);
      });
    },

    async getItem(householdId, itemId) {
      return classifyTransientFailure(async () => {
        const snapshot = await database
          .doc(`households/${householdId}/items/${itemId}`)
          .get();
        if (!snapshot.exists) return null;
        const data = snapshot.data();
        return {
          id: snapshot.id,
          name: data.name,
          parentItemId: data.parentItemId,
          tags: data.tags,
          description: data.description,
        };
      });
    },

    async getSearchRecord(householdId, itemId) {
      return classifyTransientFailure(async () => {
        const snapshot = await indexCollection(householdId).doc(itemId).get();
        if (!snapshot.exists) return null;
        return snapshot.data();
      });
    },

    async putSearchRecord(householdId, itemId, record) {
      await classifyTransientFailure(() => {
        return indexCollection(householdId).doc(itemId).set({
          itemId,
          [SEARCH_EMBEDDING_FIELD]: FieldValue.vector(record.embedding),
          sourceHash: record.sourceHash,
          modelVersion: record.modelVersion,
        });
      });
    },

    async deleteSearchRecord(householdId, itemId) {
      await classifyTransientFailure(() => {
        return indexCollection(householdId).doc(itemId).delete();
      });
    },
  };
}

async function classifyTransientFailure(operation) {
  try {
    return await operation();
  } catch (error) {
    if (TRANSIENT_FIRESTORE_CODES.has(error?.code)) {
      throw new TransientSearchIndexError(error);
    }
    throw error;
  }
}

export class TransientSearchIndexError extends Error {
  constructor(cause) {
    super("Firestore Search indexing failed transiently.", { cause });
    this.name = "TransientSearchIndexError";
  }
}
