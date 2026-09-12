import { FieldValue } from "firebase-admin/firestore";

const FORMER_MEMBER_ID = "former-member";
const FORMER_MEMBER_DISPLAY_NAME = "Former member";

export function createDeletionRepository({ database }) {
  const accountJobReference = (memberId) =>
    database.doc(`accountDeletionJobs/${memberId}`);
  const householdJobReference = (householdId) =>
    database.doc(`householdDeletionJobs/${householdId}`);

  return {
    async accountPlan({ memberId, email }) {
      const membership = await database.doc(`memberships/${memberId}`).get();
      if (!membership.exists) {
        return {
          memberId,
          email,
          householdId: null,
          householdName: null,
          deletesHousehold: false,
          memberCount: 0,
          itemCount: 0,
        };
      }

      const householdId = membership.data()?.householdId;
      if (typeof householdId !== "string") throw new InvalidDeletionStateError();
      const household = await database.doc(`households/${householdId}`).get();
      if (!household.exists) throw new InvalidDeletionStateError();
      const householdData = household.data();
      const deletesHousehold = householdData?.ownerMemberId === memberId;
      const counts = deletesHousehold
        ? await householdCounts(database, householdId)
        : { memberCount: 0, itemCount: 0 };
      return {
        memberId,
        email,
        householdId,
        householdName: deletesHousehold ? requiredString(householdData?.name) : null,
        deletesHousehold,
        ...counts,
      };
    },

    async householdPlan({ memberId, householdId }) {
      const [membership, household] = await Promise.all([
        database.doc(`memberships/${memberId}`).get(),
        database.doc(`households/${householdId}`).get(),
      ]);
      if (
        !membership.exists ||
        membership.data()?.householdId !== householdId ||
        !household.exists ||
        household.data()?.ownerMemberId !== memberId
      ) {
        throw new DeletionPermissionError();
      }
      return {
        memberId,
        householdId,
        householdName: requiredString(household.data()?.name),
        ...(await householdCounts(database, householdId)),
      };
    },

    async enqueueAccount(plan) {
      const reference = accountJobReference(plan.memberId);
      await database.runTransaction(async (transaction) => {
        const existing = await transaction.get(reference);
        if (existing.exists) return;
        transaction.create(reference, {
          ...plan,
          status: "pending",
          requestedAt: FieldValue.serverTimestamp(),
        });
      });
    },

    async enqueueHousehold(plan) {
      const reference = householdJobReference(plan.householdId);
      await database.runTransaction(async (transaction) => {
        const existing = await transaction.get(reference);
        if (existing.exists) return;
        transaction.create(reference, {
          ...plan,
          status: "pending",
          requestedAt: FieldValue.serverTimestamp(),
        });
      });
    },

    async accountJob(memberId) {
      const document = await accountJobReference(memberId).get();
      return document.exists ? document.data() : null;
    },

    async removeMemberData({ memberId, email, householdId }) {
      const access = await database
        .collectionGroup("access")
        .where("email", "==", email)
        .get();
      await deleteReferences(access.docs.map((document) => document.ref));
      await database.doc(`memberships/${memberId}`).delete();
      if (typeof householdId !== "string") return;

      const items = database.collection(`households/${householdId}/items`);
      const [created, updated] = await Promise.all([
        items.where("createdById", "==", memberId).get(),
        items.where("updatedById", "==", memberId).get(),
      ]);
      const patches = new Map();
      for (const item of created.docs) {
        patches.set(item.ref.path, {
          ref: item.ref,
          data: {
            createdById: FORMER_MEMBER_ID,
            createdByDisplayName: FORMER_MEMBER_DISPLAY_NAME,
          },
        });
      }
      for (const item of updated.docs) {
        const patch = patches.get(item.ref.path) ?? { ref: item.ref, data: {} };
        patch.data.updatedById = FORMER_MEMBER_ID;
        patch.data.updatedByDisplayName = FORMER_MEMBER_DISPLAY_NAME;
        patches.set(item.ref.path, patch);
      }
      await updateDocuments([...patches.values()]);
    },

    async removeHouseholdData(householdId) {
      const memberships = await database
        .collection("memberships")
        .where("householdId", "==", householdId)
        .get();
      await deleteReferences(memberships.docs.map((document) => document.ref));
      await database.recursiveDelete(database.doc(`households/${householdId}`));
    },

    async completeAccount(memberId) {
      await accountJobReference(memberId).delete();
    },

    async completeHousehold(householdId) {
      await householdJobReference(householdId).delete();
    },
  };
}

async function householdCounts(database, householdId) {
  const [memberships, items] = await Promise.all([
    database.collection("memberships").where("householdId", "==", householdId).get(),
    database.collection(`households/${householdId}/items`).get(),
  ]);
  return {
    memberCount: memberships.size,
    itemCount: items.docs.filter((item) =>
      typeof item.data()?.parentItemId === "string"
    ).length,
  };
}

async function deleteReferences(references) {
  for (let offset = 0; offset < references.length; offset += 100) {
    await Promise.all(references.slice(offset, offset + 100).map((reference) =>
      reference.delete(),
    ));
  }
}

async function updateDocuments(patches) {
  for (let offset = 0; offset < patches.length; offset += 100) {
    await Promise.all(patches.slice(offset, offset + 100).map(({ ref, data }) =>
      ref.update(data),
    ));
  }
}

function requiredString(value) {
  if (typeof value !== "string" || value === "") throw new InvalidDeletionStateError();
  return value;
}

export class InvalidDeletionStateError extends Error {
  constructor() {
    super("The account or Household data is incomplete.");
    this.name = "InvalidDeletionStateError";
  }
}

export class DeletionPermissionError extends Error {
  constructor() {
    super("Only the Household Owner can delete this Household.");
    this.name = "DeletionPermissionError";
  }
}
