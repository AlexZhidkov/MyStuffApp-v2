import { HttpsError } from "firebase-functions/v2/https";
import { isDocumentId } from "./mcp-inventory.js";

// Management uses the Member's Firebase sign-in, never an external application's
// Inventory credentials. Former Household Access is not needed to revoke access.
export function createAppConnectionHandlers({ database, now = Date.now, logger = console }) {
  function memberId(request) {
    if (!isDocumentId(request.auth?.uid)) {
      throw new HttpsError("unauthenticated", "Sign in to MyStuff first.");
    }
    return request.auth.uid;
  }

  return {
    async revokeAppConnection(request) {
      const member = memberId(request);
      const connectionId = request.data?.connectionId;
      if (!isDocumentId(connectionId)) {
        throw new HttpsError("invalid-argument", "Choose an App Connection.");
      }
      try {
        await database.runTransaction(async (transaction) => {
          const reference = database.doc(`appConnections/${connectionId}`);
          const connection = (await transaction.get(reference)).data();
          if (!connection || connection.memberId !== member) {
            throw new HttpsError("not-found", "App Connection is unavailable.");
          }
          if (connection.revokedAt === null) transaction.update(reference, { revokedAt: now() });
        });
        return { revoked: true };
      } catch (error) {
        if (error instanceof HttpsError) throw error;
        logger.error("App Connection revocation failed.", { code: error.code });
        throw new HttpsError("internal", "App Connection could not be revoked.");
      }
    },

    async listAppConnections(request) {
      const member = memberId(request);
      try {
        const snapshot = await database.collection("appConnections").where("memberId", "==", member).get();
        const connections = await Promise.all(snapshot.docs.map(async (document) => {
          const connection = document.data();
          // Issue 01 approvals predate the stored approval name.
          const householdName = connection.householdName ??
            (await database.doc(`households/${connection.householdId}`).get()).data()?.name ??
            "Unavailable Household";
          return { id: document.id, clientName: connection.clientName,
            householdId: connection.householdId, householdName,
            createdAt: connection.createdAt, lastUsedAt: connection.lastUsedAt,
            revokedAt: connection.revokedAt };
        }));
        connections.sort((left, right) => right.createdAt - left.createdAt || left.id.localeCompare(right.id));
        return { connections };
      } catch (error) {
        logger.error("App Connection listing failed.", { code: error.code });
        throw new HttpsError("internal", "Connected apps could not be loaded.");
      }
    },
  };
}
