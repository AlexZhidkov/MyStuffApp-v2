export function createDeletionModule({ repository, authentication, bucket }) {
  const cleanupAccount = async (job) => {
    if (job.deletesHousehold && typeof job.householdId === "string") {
      await cleanupHouseholdData({ repository, bucket }, job.householdId);
    } else {
      await repository.removeMemberData(job);
    }
    await deleteAuthenticationUser(authentication, job.memberId);
    await repository.completeAccount(job.memberId);
  };

  return {
    async previewAccount(input) {
      return publicAccountPlan(await repository.accountPlan(input));
    },

    async previewHousehold(input) {
      return publicHouseholdPlan(await repository.householdPlan(input));
    },

    async requestAccount(input) {
      const plan = await repository.accountPlan(input);
      if (
        plan.deletesHousehold &&
        input.confirmationHouseholdName !== plan.householdName
      ) {
        throw new InvalidDeletionConfirmationError();
      }
      await repository.enqueueAccount(plan);
      return publicAccountPlan(plan);
    },

    async requestHousehold(input) {
      const plan = await repository.householdPlan(input);
      if (input.confirmationHouseholdName !== plan.householdName) {
        throw new InvalidDeletionConfirmationError();
      }
      await repository.enqueueHousehold(plan);
      return publicHouseholdPlan(plan);
    },

    cleanupAccount,

    async cleanupHousehold(job) {
      await cleanupHouseholdData({ repository, bucket }, job.householdId);
      await repository.completeHousehold(job.householdId);
    },

    async resumeAccount(memberId) {
      const job = await repository.accountJob(memberId);
      if (job === null) return { resumed: false };
      await cleanupAccount(job);
      return { resumed: true };
    },
  };
}

export class InvalidDeletionConfirmationError extends Error {
  constructor() {
    super("Type the Household name exactly before deleting it.");
    this.name = "InvalidDeletionConfirmationError";
  }
}

export function publicAccountPlan(plan) {
  return {
    deletesHousehold: plan.deletesHousehold,
    householdName: plan.householdName,
    memberCount: plan.memberCount,
    itemCount: plan.itemCount,
  };
}

export function publicHouseholdPlan(plan) {
  return {
    householdName: plan.householdName,
    memberCount: plan.memberCount,
    itemCount: plan.itemCount,
  };
}

async function cleanupHouseholdData({ repository, bucket }, householdId) {
  await repository.removeHouseholdData(householdId);
  await bucket.deleteFiles({ prefix: `households/${householdId}/` });
}

async function deleteAuthenticationUser(authentication, memberId) {
  try {
    await authentication.deleteUser(memberId);
  } catch (error) {
    if (error?.code !== "auth/user-not-found") throw error;
  }
}
