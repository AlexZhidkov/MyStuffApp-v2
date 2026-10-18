import { createHash, randomBytes } from "node:crypto";
import { readMcpConnection } from "./mcp-connections.js";
import { createFirestoreSearchRepository } from "./firestore-search-repository.js";
import { CONCEPTUAL_RESULT_LIMIT } from "./search-module.js";
import { isDocumentId } from "./mcp-inventory.js";

// Match Android's trimUnicodeWhitespace and tagKey (Locale.ROOT case folding).
const normalize = (value) => value.toUpperCase().toLowerCase().normalize("NFD").replace(/\p{M}+/gu, "");
const trimQuery = (query) => query.replace(
  /^[\u0009-\u000D\u001C-\u001F\p{Z}]+|[\u0009-\u000D\u001C-\u001F\p{Z}]+$/gu, "",
);
const normalizeQuery = (query) => normalize(trimQuery(query));
const PAGE_SIZE = 20;
const hash = (value) => createHash("sha256").update(value).digest("hex");

function matchPriority(value, query) {
  const candidate = normalize(value);
  if (candidate === query) return 0;
  if (candidate.startsWith(query)) return 1;
  if (candidate.includes(query)) return 2;
  return null;
}

function literalRank(item, query) {
  const name = matchPriority(item.name, query);
  if (name !== null) return name;
  const tags = item.tags.map((tag) => matchPriority(tag, query)).filter((rank) => rank !== null);
  if (tags.length) return 3 + Math.min(...tags);
  const description = item.description === null ? null : matchPriority(item.description, query);
  return description === null ? null : 6 + description;
}

function preciseRank(item, query) {
  const name = normalize(item.name);
  if (name === query) return 0;
  if (name.startsWith(query)) return 1;
  return item.tags.some((tag) => normalize(tag) === query) ? 2 : null;
}

// Read and validate the full tree at one snapshot, as Android does before Search.
// Paths are output only; they never participate in literal matching.
async function readInventory(database, transaction, householdId) {
  const snapshot = await transaction.get(database.collection(`households/${householdId}/items`));
  const deletions = await transaction.get(database.collection(`households/${householdId}/itemDeletionJobs`));
  if (deletions.docs.length) throw new Error("Item or Inventory data is unavailable.");
  const items = new Map(snapshot.docs.map((document) => {
    const item = document.data();
    if (item.householdId !== householdId || typeof item.name !== "string" ||
        (item.description !== null && typeof item.description !== "string") ||
        !Array.isArray(item.tags) || item.tags.some((tag) => typeof tag !== "string")) {
      throw new Error("Item or Inventory data is unavailable.");
    }
    return [document.id, { ...item, id: document.id }];
  }));
  if (!items.has(householdId) || items.get(householdId).parentItemId !== null) {
    throw new Error("Item or Inventory data is unavailable.");
  }
  return [...items.values()].map((item) => {
    const path = [];
    const visited = new Set();
    let current = item;
    while (true) {
      if (visited.has(current.id)) throw new Error("The Item Path is unavailable.");
      visited.add(current.id);
      path.unshift(current.name);
      if (current.id === householdId) break;
      if (!isDocumentId(current.parentItemId) || !items.has(current.parentItemId)) {
        throw new Error("The Item Path is unavailable.");
      }
      current = items.get(current.parentItemId);
    }
    return { ...item, path };
  });
}

export async function searchMcpInventory(database, connectionId, { query, cursor }, now = Date.now, embedder) {
  if (typeof query !== "string") throw new Error("Invalid Search query.");
  if (cursor !== undefined && (typeof cursor !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(cursor))) {
    throw new Error("Invalid continuation marker.");
  }
  const normalizedQuery = normalizeQuery(query);
  const queryHash = hash(normalizedQuery);
  let conceptualIds = null;
  if (cursor === undefined && embedder && (query.match(/[\p{L}\p{Nd}]/gu) ?? []).length >= 3) {
    const householdId = await database.runTransaction(async (transaction) => {
      const connection = await readMcpConnection(database, transaction, connectionId, now());
      if (!connection) throw new Error("App Connection is unavailable.");
      await readInventory(database, transaction, connection.householdId);
      return connection.householdId;
    });
    // External inference must not run inside a retryable Firestore transaction.
    try {
      const vector = await embedder.embedQuery(`task: search result | query: ${trimQuery(query)}`);
      conceptualIds = (await createFirestoreSearchRepository(database)
        .findNearest(householdId, vector, CONCEPTUAL_RESULT_LIMIT)).slice(0, CONCEPTUAL_RESULT_LIMIT);
    } catch {
      // Only provider/index failures become fallback. Authorization and Inventory
      // reads remain outside this catch and are checked again before returning.
    }
  }
  return database.runTransaction(async (transaction) => {
    const connection = await readMcpConnection(database, transaction, connectionId, now());
    if (!connection) throw new Error("App Connection is unavailable.");
    let offset = 0;
    let pageConceptualIds = conceptualIds;
    if (cursor !== undefined) {
      const marker = (await transaction.get(database.doc(`mcpSearchCursors/${hash(cursor)}`))).data();
      if (!marker || marker.connectionId !== connectionId || marker.householdId !== connection.householdId ||
          marker.queryHash !== queryHash || !Number.isSafeInteger(marker.offset) || marker.offset <= 0) {
        throw new Error("Invalid continuation marker.");
      }
      offset = marker.offset;
      pageConceptualIds = marker.conceptualIds ?? null;
    }
    const inventory = await readInventory(database, transaction, connection.householdId);
    const searchable = inventory.filter((item) => item.id !== connection.householdId);
    const rankItem = pageConceptualIds === null ? literalRank : preciseRank;
    let results = normalizedQuery === "" ? [] : searchable
      .map((item) => ({ item, rank: rankItem(item, normalizedQuery) }))
      .filter(({ rank }) => rank !== null)
      .sort((a, b) => a.rank - b.rank);
    if (pageConceptualIds !== null) {
      const byId = new Map(searchable.map((item) => [item.id, item]));
      const ids = [...new Set([...results.map(({ item }) => item.id), ...pageConceptualIds])];
      results = ids.filter((id) => byId.has(id)).map((id) => ({ item: byId.get(id) }));
    }
    let nextCursor = null;
    if (offset + PAGE_SIZE < results.length) {
      nextCursor = randomBytes(32).toString("base64url");
      transaction.create(database.doc(`mcpSearchCursors/${hash(nextCursor)}`), {
        connectionId, householdId: connection.householdId, queryHash, offset: offset + PAGE_SIZE,
        conceptualIds: pageConceptualIds,
      });
    }
    return {
      literalOnlyFallback: pageConceptualIds === null,
      items: results.slice(offset, offset + PAGE_SIZE)
        .map(({ item: { id, path, name, description } }) => ({ id, path, name, description })),
      nextCursor,
    };
  });
}
