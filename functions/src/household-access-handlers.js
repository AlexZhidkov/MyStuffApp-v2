import { HttpsError } from "firebase-functions/v2/https";
import {
  AccountDeletionPendingError,
  HouseholdDeletionPendingError,
  HouseholdAccessPermissionError,
  InvalidGoogleEmailError,
  InvalidHouseholdAccessError,
  UnknownHouseholdAccessError,
} from "./household-access-module.js";

export function createHouseholdAccessHandlers({ householdAccess, logger }) {
  return {
    async claimHouseholdAccess(request) {
      const auth = verifiedGoogleAuth(request);
      try {
        return await householdAccess.claimMatchingAccess({
          memberId: auth.uid,
          authenticatedEmail: auth.email,
          displayName: auth.name,
        });
      } catch (error) {
        if (
          error instanceof AccountDeletionPendingError ||
          error instanceof HouseholdDeletionPendingError
        ) {
          throw new HttpsError("failed-precondition", error.message);
        }
        if (error instanceof InvalidGoogleEmailError) {
          throw new HttpsError("permission-denied", error.message);
        }
        if (error instanceof InvalidHouseholdAccessError) {
          throw new HttpsError("failed-precondition", error.message);
        }
        logger.error("Household Access claiming failed.", error);
        throw new HttpsError("internal", "Household Access could not be claimed.");
      }
    },

    async removeHouseholdAccess(request) {
      const auth = verifiedGoogleAuth(request);
      try {
        return await householdAccess.removeAccess({
          memberId: auth.uid,
          householdId: request.data?.householdId,
          email: request.data?.email,
        });
      } catch (error) {
        if (
          error instanceof AccountDeletionPendingError ||
          error instanceof HouseholdDeletionPendingError
        ) {
          throw new HttpsError("failed-precondition", error.message);
        }
        if (error instanceof HouseholdAccessPermissionError) {
          throw new HttpsError("permission-denied", error.message);
        }
        if (error instanceof UnknownHouseholdAccessError) {
          throw new HttpsError("not-found", error.message);
        }
        if (error instanceof InvalidGoogleEmailError) {
          throw new HttpsError("failed-precondition", error.message);
        }
        logger.error("Household Access removal failed.", error);
        throw new HttpsError("internal", "Household Access could not be removed.");
      }
    },
  };
}

function verifiedGoogleAuth(request) {
  const token = request.auth?.token;
  if (request.auth?.uid === undefined) {
    throw new HttpsError("unauthenticated", "Sign in with Google first.");
  }
  if (
    token?.firebase?.sign_in_provider !== "google.com" ||
    typeof token.email !== "string" ||
    token.email_verified !== true
  ) {
    throw new HttpsError("permission-denied", "A verified Google Account is required.");
  }
  return {
    uid: request.auth.uid,
    email: token.email,
    name: typeof token.name === "string" && token.name.trim() !== ""
      ? token.name
      : token.email,
  };
}
