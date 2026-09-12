export async function activeHouseholdIdForMember(database, memberId) {
  const [membership, accountDeletion] = await Promise.all([
    database.doc(`memberships/${memberId}`).get(),
    database.doc(`accountDeletionJobs/${memberId}`).get(),
  ]);
  if (!membership.exists || accountDeletion.exists) return null;
  const householdId = membership.data()?.householdId;
  if (typeof householdId !== "string") return null;
  const household = await database.doc(`households/${householdId}`).get();
  if (!household.exists) return null;
  const ownerMemberId = household.data()?.ownerMemberId;
  if (typeof ownerMemberId !== "string") return null;
  const [householdDeletion, ownerDeletion] = await Promise.all([
    database.doc(`householdDeletionJobs/${householdId}`).get(),
    database.doc(`accountDeletionJobs/${ownerMemberId}`).get(),
  ]);
  return householdDeletion.exists || ownerDeletion.exists ? null : householdId;
}

export async function isActiveHouseholdMember(database, memberId, householdId) {
  return await activeHouseholdIdForMember(database, memberId) === householdId;
}
