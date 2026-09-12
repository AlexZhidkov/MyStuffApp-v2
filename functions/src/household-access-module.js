import { FieldValue } from "firebase-admin/firestore";

export function createHouseholdAccessModule({ database }) {
  return {
    async claimMatchingAccess({ memberId, authenticatedEmail, displayName }) {
      const email = normalizeEmail(authenticatedEmail);
      if (email === null) throw new InvalidGoogleEmailError();

      return database.runTransaction(async (transaction) => {
        const membershipReference = database.doc(`memberships/${memberId}`);
        const accountDeletionReference = database.doc(`accountDeletionJobs/${memberId}`);
        const [membership, accountDeletion] = await Promise.all([
          transaction.get(membershipReference),
          transaction.get(accountDeletionReference),
        ]);
        if (accountDeletion.exists) throw new AccountDeletionPendingError();
        if (membership.exists) {
          return { householdId: membership.data()?.householdId ?? null };
        }

        const accessQuery = database
          .collectionGroup("access")
          .where("email", "==", email)
          .where("memberId", "==", null);
        const matches = await transaction.get(accessQuery);
        const accessDocument = matches.docs[0];
        if (accessDocument === undefined) return { householdId: null };

        const access = accessDocument.data();
        const householdId = access?.householdId;
        if (typeof householdId !== "string") throw new InvalidHouseholdAccessError();

        const householdReference = database.doc(`households/${householdId}`);
        const household = await transaction.get(householdReference);
        if (!household.exists || !isValidHousehold(household.data())) {
          throw new InvalidHouseholdAccessError();
        }
        const [householdDeletion, ownerDeletion] = await Promise.all([
          transaction.get(database.doc(`householdDeletionJobs/${householdId}`)),
          transaction.get(database.doc(
            `accountDeletionJobs/${household.data().ownerMemberId}`,
          )),
        ]);
        if (householdDeletion.exists || ownerDeletion.exists) {
          throw new HouseholdDeletionPendingError();
        }

        transaction.create(membershipReference, {
          householdId,
          role: "member",
          householdName: household.data().name,
          ownerMemberId: household.data().ownerMemberId,
          ownerEmail: household.data().ownerEmail,
          useTags: household.data().useTags,
        });
        transaction.update(accessDocument.ref, {
          memberId,
          memberDisplayName: displayName,
          memberEmail: email,
          claimedAt: FieldValue.serverTimestamp(),
        });
        return { householdId };
      });
    },

    async removeAccess({ memberId, householdId, email }) {
      const normalizedEmail = normalizeEmail(email);
      if (normalizedEmail === null) throw new InvalidGoogleEmailError();

      return database.runTransaction(async (transaction) => {
        const membershipReference = database.doc(`memberships/${memberId}`);
        const accountDeletionReference = database.doc(`accountDeletionJobs/${memberId}`);
        const householdReference = database.doc(`households/${householdId}`);
        const accessReference = database.doc(
          `households/${householdId}/access/${normalizedEmail}`,
        );
        const householdDeletionReference = database.doc(
          `householdDeletionJobs/${householdId}`,
        );
        const [membership, accountDeletion, householdDeletion, household, access] = await Promise.all([
          transaction.get(membershipReference),
          transaction.get(accountDeletionReference),
          transaction.get(householdDeletionReference),
          transaction.get(householdReference),
          transaction.get(accessReference),
        ]);
        if (accountDeletion.exists) throw new AccountDeletionPendingError();
        if (householdDeletion.exists) throw new HouseholdDeletionPendingError();
        if (
          !membership.exists ||
          membership.data()?.householdId !== householdId ||
          !household.exists ||
          household.data()?.ownerMemberId !== memberId
        ) {
          throw new HouseholdAccessPermissionError();
        }
        if (!access.exists || access.data()?.householdId !== householdId) {
          throw new UnknownHouseholdAccessError();
        }
        if (access.data()?.memberId === memberId) {
          throw new HouseholdAccessPermissionError();
        }

        transaction.delete(accessReference);
        const claimedMemberId = access.data()?.memberId;
        if (typeof claimedMemberId === "string") {
          transaction.delete(database.doc(`memberships/${claimedMemberId}`));
        }
        return { email: normalizedEmail };
      });
    },
  };
}

export function normalizeEmail(value) {
  if (typeof value !== "string") return null;
  const email = value.trim().toLowerCase();
  return email === "" ? null : email;
}

function isValidHousehold(data) {
  return typeof data?.name === "string" &&
    typeof data.ownerMemberId === "string" &&
    typeof data.ownerEmail === "string" &&
    typeof data.useTags === "boolean";
}

export class InvalidGoogleEmailError extends Error {
  constructor() {
    super("A verified Google email address is required.");
    this.name = "InvalidGoogleEmailError";
  }
}

export class InvalidHouseholdAccessError extends Error {
  constructor() {
    super("This Household Access data is incomplete.");
    this.name = "InvalidHouseholdAccessError";
  }
}

export class HouseholdAccessPermissionError extends Error {
  constructor() {
    super("Only the Household Owner can manage Household Access.");
    this.name = "HouseholdAccessPermissionError";
  }
}

export class UnknownHouseholdAccessError extends Error {
  constructor() {
    super("This Household Access no longer exists.");
    this.name = "UnknownHouseholdAccessError";
  }
}

export class AccountDeletionPendingError extends Error {
  constructor() {
    super("Account Deletion is already in progress.");
    this.name = "AccountDeletionPendingError";
  }
}

export class HouseholdDeletionPendingError extends Error {
  constructor() {
    super("Household Deletion is already in progress.");
    this.name = "HouseholdDeletionPendingError";
  }
}
