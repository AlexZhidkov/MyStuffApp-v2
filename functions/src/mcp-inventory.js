import { createHash, randomBytes } from "node:crypto";
import { readMcpConnection } from "./mcp-connections.js";

const CHILD_PAGE_SIZE = 20;
const cursorId = (cursor) => createHash("sha256").update(cursor).digest("hex");

export function isDocumentId(value) {
  return typeof value === "string" && value.length > 0 &&
    Buffer.byteLength(value, "utf8") <= 1500 &&
    !value.includes("/") && value !== "." && value !== ".." &&
    !/^__.*__$/.test(value);
}

// Read the whole ancestry at one Firestore snapshot, so a concurrent Move cannot
// produce an Item Path assembled from two different versions of the Inventory.
export async function readMcpItem(database, connectionId, itemId, now = Date.now) {
  if (!isDocumentId(itemId)) throw new Error("Invalid Item ID.");
  return database.runTransaction(async (transaction) => {
    const connection = await readMcpConnection(database, transaction, connectionId, now());
    if (!connection) throw new Error("App Connection is unavailable.");
    return readItemInTransaction(database, transaction, connection.householdId, itemId);
  });
}

async function readItemInTransaction(database, transaction, householdId, itemId) {
  const visited = new Set();
  const path = [];
  let currentId = itemId;
  let item;
  while (true) {
    if (!isDocumentId(currentId) || visited.has(currentId)) {
      throw new Error("The Item Path is unavailable.");
    }
    visited.add(currentId);
    const snapshot = await transaction.get(
      database.doc(`households/${householdId}/items/${currentId}`),
    );
    const deletion = await transaction.get(
      database.doc(`households/${householdId}/itemDeletionJobs/${currentId}`),
    );
    const data = snapshot.data();
    if (!snapshot.exists || deletion.exists ||
        data.householdId !== householdId || typeof data.name !== "string") {
      throw new Error("Item or Inventory data is unavailable.");
    }
    if (item === undefined) {
      if (data.description !== null && typeof data.description !== "string") {
        throw new Error("Item or Inventory data is unavailable.");
      }
      item = { id: itemId, name: data.name, description: data.description };
    }
    path.unshift(data.name);
    if (currentId === householdId) {
      if (data.parentItemId !== null) throw new Error("The Item Path is unavailable.");
      return { id: item.id, path, name: item.name, description: item.description };
    }
    currentId = data.parentItemId;
  }
}

// Firestore's default document-ID order is also Android's snapshot order.
// Inventory.childrenOf uses each sibling's index for missing positions and ties.
export async function listMcpChildren(database, connectionId, { parentItemId, cursor }, now = Date.now) {
  if (parentItemId !== undefined && !isDocumentId(parentItemId)) throw new Error("Invalid Parent Item ID.");
  if (cursor !== undefined && (typeof cursor !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(cursor))) {
    throw new Error("Invalid continuation marker.");
  }
  return database.runTransaction(async (transaction) => {
    const connection = await readMcpConnection(database, transaction, connectionId, now());
    if (!connection) throw new Error("App Connection is unavailable.");
    const { householdId } = connection;
    const parentId = parentItemId ?? householdId;
    let offset = 0;
    if (cursor !== undefined) {
      const marker = (await transaction.get(database.doc(`mcpChildCursors/${cursorId(cursor)}`))).data();
      if (!marker || marker.connectionId !== connectionId || marker.householdId !== householdId ||
          marker.parentItemId !== parentId || !Number.isSafeInteger(marker.offset) || marker.offset <= 0) {
        throw new Error("Invalid continuation marker.");
      }
      offset = marker.offset;
    }
    await readItemInTransaction(database, transaction, householdId, parentId);
    const snapshot = await transaction.get(
      database.collection(`households/${householdId}/items`).where("parentItemId", "==", parentId),
    );
    const children = snapshot.docs.map((document, index) => {
      const data = document.data();
      if (data.householdId !== householdId || typeof data.name !== "string" ||
          (data.displayOrder != null && !Number.isInteger(data.displayOrder))) {
        throw new Error("Item or Inventory data is unavailable.");
      }
      return { id: document.id, name: data.name, order: data.displayOrder ?? index, index };
    }).sort((a, b) => a.order - b.order || a.index - b.index);
    const page = children.slice(offset, offset + CHILD_PAGE_SIZE);
    for (const child of page) {
      const deletion = await transaction.get(database.doc(`households/${householdId}/itemDeletionJobs/${child.id}`));
      if (deletion.exists) throw new Error("Item or Inventory data is unavailable.");
    }
    let nextCursor = null;
    if (offset + CHILD_PAGE_SIZE < children.length) {
      nextCursor = randomBytes(32).toString("base64url");
      transaction.create(database.doc(`mcpChildCursors/${cursorId(nextCursor)}`), {
        connectionId, householdId, parentItemId: parentId, offset: offset + CHILD_PAGE_SIZE,
      });
    }
    return { parentItemId: parentId, items: page.map(({ id, name }) => ({ id, name })), nextCursor };
  });
}
