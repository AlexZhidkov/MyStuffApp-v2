import { HttpsError } from "firebase-functions/v2/https";
import {
  InvalidDeletionConfirmationError,
} from "./deletion-module.js";
import {
  DeletionPermissionError,
  InvalidDeletionStateError,
} from "./deletion-repository.js";
import { normalizeEmail } from "./household-access-module.js";

const RECENT_AUTH_SECONDS = 5 * 60;

export function createDeletionHandlers({ deletion, authentication, logger, now = Date.now }) {
  async function authenticatedAccountInput(request, requireRecentAuthentication = false) {
    const memberId = request.auth?.uid;
    const email = normalizeEmail(request.auth?.token?.email);
    if (memberId === undefined || email === null || request.auth?.token?.email_verified !== true) {
      throw new HttpsError("unauthenticated", "Sign in with a verified Google Account.");
    }
    if (requireRecentAuthentication && !isRecentAuthentication(request.auth.token, now())) {
      throw new HttpsError("unauthenticated", "Sign in with Google again before deleting data.");
    }
    return { memberId, email };
  }

  async function translate(action, publicMessage) {
    try {
      return await action();
    } catch (error) {
      if (error instanceof DeletionPermissionError) {
        throw new HttpsError("permission-denied", error.message);
      }
      if (error instanceof InvalidDeletionStateError) {
        throw new HttpsError("failed-precondition", error.message);
      }
      if (error instanceof InvalidDeletionConfirmationError) {
        throw new HttpsError("failed-precondition", error.message);
      }
      if (error instanceof HttpsError) throw error;
      logger.error(publicMessage, error);
      throw new HttpsError("internal", publicMessage);
    }
  }

  return {
    async previewAccountDeletion(request) {
      const input = await authenticatedAccountInput(request);
      return translate(() => deletion.previewAccount(input), "Account deletion could not be prepared.");
    },

    async requestAccountDeletion(request) {
      const input = await authenticatedAccountInput(request, true);
      return translate(
        () => deletion.requestAccount({
          ...input,
          confirmationHouseholdName: request.data?.householdName,
        }),
        "Account deletion could not be started.",
      );
    },

    async previewHouseholdDeletion(request) {
      const { memberId } = await authenticatedAccountInput(request);
      return translate(
        () => deletion.previewHousehold({ memberId, householdId: request.data?.householdId }),
        "Household deletion could not be prepared.",
      );
    },

    async requestHouseholdDeletion(request) {
      const { memberId } = await authenticatedAccountInput(request, true);
      return translate(
        () => deletion.requestHousehold({
          memberId,
          householdId: request.data?.householdId,
          confirmationHouseholdName: request.data?.householdName,
        }),
        "Household deletion could not be started.",
      );
    },

    async cleanupAccountDeletion(event) {
      if (!event.data?.exists) return;
      try {
        await deletion.cleanupAccount(event.data.data());
      } catch (error) {
        logger.error("Account deletion cleanup failed.", error);
        throw error;
      }
    },

    async cleanupHouseholdDeletion(event) {
      if (!event.data?.exists) return;
      try {
        await deletion.cleanupHousehold(event.data.data());
      } catch (error) {
        logger.error("Household deletion cleanup failed.", error);
        throw error;
      }
    },

    async manuallyDeleteAccount(request, response) {
      if (request.method !== "POST") {
        response.status(405).json({ error: "POST required." });
        return;
      }
      if (request.body?.resume === true) {
        const memberId = request.body?.memberId;
        if (typeof memberId !== "string" || memberId === "") {
          response.status(400).json({ error: "A deletion-job Member ID is required to resume." });
          return;
        }
        try {
          response.status(200).json(await deletion.resumeAccount(memberId));
        } catch (error) {
          logger.error("Manual account deletion resume failed.", error);
          response.status(500).json({ error: "Account deletion could not be resumed." });
        }
        return;
      }

      const email = normalizeEmail(request.body?.email);
      if (email === null) {
        response.status(400).json({ error: "A verified account email is required." });
        return;
      }
      try {
        const user = await authentication.getUserByEmail(email);
        const plan = await deletion.previewAccount({ memberId: user.uid, email });
        if (request.body?.execute !== true) {
          response.status(200).json({ execute: false, memberId: user.uid, ...plan });
          return;
        }
        response.status(202).json(await deletion.requestAccount({
          memberId: user.uid,
          email,
          confirmationHouseholdName: plan.householdName,
        }));
      } catch (error) {
        logger.error("Manual account deletion failed.", error);
        response.status(500).json({ error: "Account deletion could not be processed." });
      }
    },
  };
}

export function isRecentAuthentication(token, nowMilliseconds) {
  const authenticatedAt = token?.auth_time;
  return typeof authenticatedAt === "number" &&
    nowMilliseconds / 1000 - authenticatedAt <= RECENT_AUTH_SECONDS;
}
