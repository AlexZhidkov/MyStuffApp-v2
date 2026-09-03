import assert from "node:assert/strict";
import { test } from "node:test";
import { createInvitationAcceptanceHandlers } from "../src/invitation-acceptance-handlers.js";
import {
  AlreadyHouseholdMemberError,
  InvitationEmailMismatchError,
  InvitationExpiredError,
  InvitationStateError,
  UnknownInvitationError,
} from "../src/invitation-acceptance-module.js";

test("acceptance requires an authenticated Google Account", async () => {
  const handlers = handlersFor(async () => ({ householdId: "household-1" }));

  await assert.rejects(
    handlers.acceptHouseholdInvitation({ auth: null, data: {} }),
    { code: "unauthenticated" },
  );
  await assert.rejects(
    handlers.acceptHouseholdInvitation({
      auth: { uid: "member-2", token: { email: "sam@example.com" } },
      data: { invitationId: "invitation-1" },
    }),
    { code: "permission-denied" },
  );
  await assert.rejects(
    handlers.acceptHouseholdInvitation({
      auth: {
        uid: "member-2",
        token: {
          email: "sam@example.com",
          email_verified: false,
          firebase: { sign_in_provider: "google.com" },
        },
      },
      data: { invitationId: "invitation-1" },
    }),
    { code: "permission-denied" },
  );
});

test("acceptance delegates the link and authenticated Google identity", async () => {
  let request;
  const handlers = handlersFor(async (value) => {
    request = value;
    return { householdId: "household-1" };
  });

  const result = await handlers.acceptHouseholdInvitation(googleRequest());

  assert.deepEqual(result, { householdId: "household-1" });
  assert.deepEqual(request, {
    invitationId: "invitation-1",
    memberId: "member-2",
    authenticatedEmail: "sam@example.com",
  });
});

test("acceptance returns clear outcomes for every rejected invitation", async () => {
  const cases = [
    [new UnknownInvitationError(), "not-found"],
    [new InvitationEmailMismatchError(), "permission-denied"],
    [new InvitationExpiredError(), "deadline-exceeded"],
    [new InvitationStateError("expired"), "deadline-exceeded"],
    [new InvitationStateError("revoked"), "failed-precondition"],
    [new InvitationStateError("replaced"), "failed-precondition"],
    [new InvitationStateError("accepted"), "already-exists"],
    [new AlreadyHouseholdMemberError(), "already-exists"],
  ];

  for (const [failure, code] of cases) {
    const handlers = handlersFor(async () => { throw failure; });
    await assert.rejects(
      handlers.acceptHouseholdInvitation(googleRequest()),
      (error) => error.code === code && error.message === failure.message,
    );
  }
});

function handlersFor(acceptInvitation) {
  return createInvitationAcceptanceHandlers({
    acceptance: { acceptInvitation },
    logger: { error() {} },
  });
}

function googleRequest() {
  return {
    auth: {
      uid: "member-2",
      token: {
        email: "sam@example.com",
        email_verified: true,
        firebase: { sign_in_provider: "google.com" },
      },
    },
    data: { invitationId: "invitation-1" },
  };
}
