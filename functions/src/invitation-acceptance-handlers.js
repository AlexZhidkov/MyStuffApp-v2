import { HttpsError } from "firebase-functions/v2/https";
import {
  AlreadyHouseholdMemberError,
  InvitationEmailMismatchError,
  InvitationExpiredError,
  InvitationStateError,
  UnknownInvitationError,
} from "./invitation-acceptance-module.js";

export function createInvitationAcceptanceHandlers({ acceptance, logger }) {
  return {
    async acceptHouseholdInvitation(request) {
      const memberId = request.auth?.uid;
      const authenticatedEmail = request.auth?.token?.email;
      const emailVerified = request.auth?.token?.email_verified;
      const provider = request.auth?.token?.firebase?.sign_in_provider;
      if (memberId === undefined) {
        throw new HttpsError("unauthenticated", "Sign in with Google to accept this invitation.");
      }
      if (
        provider !== "google.com" ||
        typeof authenticatedEmail !== "string" ||
        emailVerified !== true
      ) {
        throw new HttpsError(
          "permission-denied",
          "This invitation requires a Google Account.",
        );
      }

      try {
        return await acceptance.acceptInvitation({
          invitationId: request.data?.invitationId,
          memberId,
          authenticatedEmail,
        });
      } catch (error) {
        if (error instanceof UnknownInvitationError) {
          throw new HttpsError("not-found", error.message);
        }
        if (error instanceof InvitationEmailMismatchError) {
          throw new HttpsError("permission-denied", error.message);
        }
        if (error instanceof InvitationExpiredError) {
          throw new HttpsError("deadline-exceeded", error.message);
        }
        if (error instanceof AlreadyHouseholdMemberError) {
          throw new HttpsError("already-exists", error.message);
        }
        if (error instanceof InvitationStateError) {
          const code = error.status === "accepted"
            ? "already-exists"
            : error.status === "expired"
              ? "deadline-exceeded"
              : "failed-precondition";
          throw new HttpsError(code, error.message);
        }
        logger.error("Household invitation acceptance failed.", error);
        throw new HttpsError("internal", "The invitation could not be accepted.");
      }
    },
  };
}
