import { isActiveHouseholdMember } from "./member-authorization.js";

export function isDocumentId(value) {
  return typeof value === "string" && value.length > 0 &&
    Buffer.byteLength(value, "utf8") <= 1500 &&
    !value.includes("/") && value !== "." && value !== ".." &&
    !/^__.*__$/.test(value);
}

// Read the whole ancestry at one Firestore snapshot, so a concurrent Move cannot
// produce an Item Path assembled from two different versions of the Inventory.
export async function readMcpItem(database, memberId, householdId, itemId) {
  if (!isDocumentId(itemId)) throw new Error("Invalid Item ID.");
  return database.runTransaction(async (transaction) => {
    const reader = { doc: (path) => ({ get: () => transaction.get(database.doc(path)) }) };
    if (!await isActiveHouseholdMember(reader, memberId, householdId)) {
      throw new Error("Household Access is unavailable.");
    }
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
  });
}
