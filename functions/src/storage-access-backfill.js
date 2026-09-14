const HOUSEHOLDS = "households";
const MEMBERSHIPS = "memberships";
const ACCOUNT_DELETION_JOBS = "accountDeletionJobs";
const HOUSEHOLD_DELETION_JOBS = "householdDeletionJobs";

/**
 * Rebuilds the Household fields consumed by Storage Security Rules.
 *
 * Storage rules cannot safely look up membership and all deletion-job documents
 * within their two-document budget. This backfill gives them one Household
 * authorization projection while preserving any deletion already in progress.
 */
export function createStorageAccessBackfill({ database }) {
  if (database === undefined || database === null) {
    throw new TypeError("An Admin Firestore database is required.");
  }

  return {
    async run({ dryRun = false, householdId } = {}) {
      const report = emptyReport(dryRun);
      const [households, accountJobs, householdJobs] = await Promise.all([
        targetHouseholds(database, householdId),
        database.collection(ACCOUNT_DELETION_JOBS).get(),
        database.collection(HOUSEHOLD_DELETION_JOBS).get(),
      ]);
      const accountDeletionMemberIds = new Set(accountJobs.docs.map((job) => job.id));
      const householdDeletionIds = new Set(householdJobs.docs.map((job) => job.id));

      for (const household of households.docs) {
        report.examined += 1;
        try {
          const memberships = await database.collection(MEMBERSHIPS)
            .where("householdId", "==", household.id)
            .get();
          const storageMemberIds = Object.fromEntries(
            memberships.docs
              .filter((membership) => !accountDeletionMemberIds.has(membership.id))
              .map((membership) => [membership.id, true]),
          );
          const storageAccessRevoked = householdDeletionIds.has(household.id) ||
            accountDeletionMemberIds.has(household.data()?.ownerMemberId) ||
            household.data()?.storageAccessRevoked === true;
          const state = { storageMemberIds, storageAccessRevoked };
          report.updates.push({ householdId: household.id, ...state });
          if (!dryRun) {
            await household.ref.update(state);
          }
        } catch (error) {
          report.failures.push({
            householdId: household.id,
            message: error instanceof Error ? error.message : String(error),
          });
        }
      }
      return report;
    },
  };
}

async function targetHouseholds(database, householdId) {
  if (householdId === undefined) return database.collection(HOUSEHOLDS).get();
  const household = await database.doc(`${HOUSEHOLDS}/${householdId}`).get();
  return household.exists ? { docs: [household] } : { docs: [] };
}

function emptyReport(dryRun) {
  return {
    dryRun,
    examined: 0,
    updates: [],
    failures: [],
  };
}
