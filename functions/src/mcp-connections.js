import { randomUUID } from "node:crypto";
import { isActiveHouseholdMember } from "./member-authorization.js";

const INACTIVITY_MS = 90 * 24 * 60 * 60 * 1000;

// These records outlive membership and deletion-job cleanup. A restored Member
// or Household can approve a new connection, but cannot revive former approval.
export function invalidateMemberConnections(database, transaction, memberId) {
  transaction.set(database.doc(`mcpMemberEpochs/${memberId}`), { generation: randomUUID() });
}

export function invalidateHouseholdConnections(database, transaction, householdId) {
  transaction.set(database.doc(`mcpHouseholdEpochs/${householdId}`), { generation: randomUUID() });
}

export async function readConnectionGenerations(database, transaction, memberId, householdId) {
  const [member, household] = await Promise.all([
    transaction.get(database.doc(`mcpMemberEpochs/${memberId}`)),
    transaction.get(database.doc(`mcpHouseholdEpochs/${householdId}`)),
  ]);
  return { memberGeneration: member.data()?.generation ?? null,
    householdGeneration: household.data()?.generation ?? null };
}

// This check is shared by token exchange, renewal, HTTP authorization, and
// Inventory snapshot reads. Renewal itself does not count as Inventory use.
export async function readMcpConnection(database, transaction, connectionId, now) {
  const connection = (await transaction.get(database.doc(`appConnections/${connectionId}`))).data();
  const reader = { doc: (path) => ({ get: () => transaction.get(database.doc(path)) }) };
  if (!connection || connection.revokedAt !== null ||
      !Number.isFinite(connection.lastUsedAt) || now - connection.lastUsedAt >= INACTIVITY_MS ||
      !await isActiveHouseholdMember(reader, connection.memberId, connection.householdId)) {
    return null;
  }
  const generations = await readConnectionGenerations(
    database, transaction, connection.memberId, connection.householdId,
  );
  // Issue 01 approvals predate generations; missing fields represent the
  // original generation only, never a later restored authorization.
  if ((connection.memberGeneration ?? null) !== generations.memberGeneration ||
      (connection.householdGeneration ?? null) !== generations.householdGeneration) return null;
  return connection;
}
