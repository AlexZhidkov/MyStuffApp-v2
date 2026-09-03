import { FieldValue, Timestamp } from "firebase-admin/firestore";

export function createInvitationAcceptanceModule({
  database,
  clock = firebaseClock,
}) {
  return {
    async acceptInvitation({ invitationId, memberId, authenticatedEmail }) {
      if (typeof invitationId !== "string" || invitationId.trim() === "") {
        throw new UnknownInvitationError();
      }
      const normalizedMemberEmail = normalizeEmail(authenticatedEmail);
      if (normalizedMemberEmail === null) throw new InvitationEmailMismatchError();

      const invitationReference = database.doc(`invitations/${invitationId}`);
      const membershipReference = database.doc(`memberships/${memberId}`);

      return database.runTransaction(async (transaction) => {
        const invitationSnapshot = await transaction.get(invitationReference);
        if (!invitationSnapshot.exists) throw new UnknownInvitationError();

        const invitation = invitationSnapshot.data();
        const householdId = invitation?.householdId;
        const intendedEmail = normalizeEmail(invitation?.intendedEmail);
        if (typeof householdId !== "string" || intendedEmail === null) {
          throw new UnknownInvitationError();
        }
        if (normalizedMemberEmail !== intendedEmail) {
          throw new InvitationEmailMismatchError();
        }
        if (invitation.status !== "pending") {
          throw new InvitationStateError(invitation.status);
        }
        const expiresAt = expiryMillis(invitation.expiresAt);
        if (!Number.isFinite(expiresAt)) throw new UnknownInvitationError();
        if (expiresAt <= clock.now().getTime()) {
          throw new InvitationExpiredError();
        }

        const membershipSnapshot = await transaction.get(membershipReference);
        if (membershipSnapshot.exists) throw new AlreadyHouseholdMemberError();

        const householdReference = database.doc(`households/${householdId}`);
        const householdSnapshot = await transaction.get(householdReference);
        if (!householdSnapshot.exists) throw new UnknownInvitationError();

        transaction.create(membershipReference, {
          householdId,
          role: "member",
        });
        transaction.update(invitationReference, {
          status: "accepted",
          acceptedByMemberId: memberId,
          acceptedAt: clock.serverTimestamp(),
        });
        return { householdId };
      });
    },
  };
}

function normalizeEmail(value) {
  if (typeof value !== "string") return null;
  const email = value.trim().toLowerCase();
  return email === "" ? null : email;
}

function expiryMillis(value) {
  if (value instanceof Date) return value.getTime();
  if (value !== null && typeof value?.toMillis === "function") return value.toMillis();
  return Number.NaN;
}

const firebaseClock = {
  now: () => Timestamp.now().toDate(),
  serverTimestamp: () => FieldValue.serverTimestamp(),
};

export class UnknownInvitationError extends Error {
  constructor() {
    super("This invitation link is not recognized.");
    this.name = "UnknownInvitationError";
  }
}

export class InvitationEmailMismatchError extends Error {
  constructor() {
    super("This invitation was sent to a different Google Account.");
    this.name = "InvitationEmailMismatchError";
  }
}

export class InvitationExpiredError extends Error {
  constructor() {
    super("This invitation link has expired.");
    this.name = "InvitationExpiredError";
  }
}

export class InvitationStateError extends Error {
  constructor(status) {
    const messages = {
      revoked: "This invitation link has been revoked.",
      replaced: "This invitation link has been replaced.",
      accepted: "This invitation link has already been accepted.",
      expired: "This invitation link has expired.",
    };
    super(messages[status] ?? "This invitation link is no longer valid.");
    this.name = "InvitationStateError";
    this.status = status;
  }
}

export class AlreadyHouseholdMemberError extends Error {
  constructor() {
    super("You already belong to a Household.");
    this.name = "AlreadyHouseholdMemberError";
  }
}
