import assert from "node:assert/strict";
import { test } from "node:test";
import {
  AlreadyHouseholdMemberError,
  InvitationEmailMismatchError,
  InvitationExpiredError,
  InvitationStateError,
  UnknownInvitationError,
  createInvitationAcceptanceModule,
} from "../src/invitation-acceptance-module.js";

const now = new Date("2026-09-03T10:00:00Z");

test("the intended Google Account accepts a pending invitation once", async () => {
  const database = fakeDatabase({
    "invitations/invitation-1": invitation(),
    "households/household-1": { name: "Our Home" },
  });
  const acceptance = createInvitationAcceptanceModule({
    database,
    clock: fixedClock(),
  });

  const result = await acceptance.acceptInvitation({
    invitationId: "invitation-1",
    memberId: "member-2",
    authenticatedEmail: " Sam@Example.com ",
  });

  assert.deepEqual(result, { householdId: "household-1" });
  assert.deepEqual(database.data("memberships/member-2"), {
    householdId: "household-1",
    role: "member",
  });
  assert.deepEqual(database.data("invitations/invitation-1"), {
    ...invitation(),
    status: "accepted",
    acceptedByMemberId: "member-2",
    acceptedAt: "SERVER_TIMESTAMP",
  });
  await assert.rejects(
    acceptance.acceptInvitation({
      invitationId: "invitation-1",
      memberId: "member-2",
      authenticatedEmail: "sam@example.com",
    }),
    InvitationStateError,
  );
});

test("invitation acceptance rejects a different Google email", async () => {
  const acceptance = acceptanceFor({ invitationDocument: invitation() });

  await assert.rejects(
    acceptance.acceptInvitation({
      invitationId: "invitation-1",
      memberId: "member-2",
      authenticatedEmail: "pat@example.com",
    }),
    InvitationEmailMismatchError,
  );
});

test("invitation acceptance rejects expired and non-pending invitations", async () => {
  const expired = acceptanceFor({
    invitationDocument: invitation({ expiresAt: new Date(now.getTime() - 1) }),
  });
  await assert.rejects(
    expired.acceptInvitation(acceptanceRequest()),
    InvitationExpiredError,
  );
  const expiryBoundary = acceptanceFor({
    invitationDocument: invitation({ expiresAt: now }),
  });
  await assert.rejects(
    expiryBoundary.acceptInvitation(acceptanceRequest()),
    InvitationExpiredError,
  );

  for (const status of ["revoked", "replaced", "accepted"]) {
    const acceptance = acceptanceFor({
      invitationDocument: invitation({ status }),
    });
    await assert.rejects(
      acceptance.acceptInvitation(acceptanceRequest()),
      (error) => error instanceof InvitationStateError && error.status === status,
    );
  }
});

test("incomplete invitation data is rejected without creating membership", async () => {
  const database = fakeDatabase({
    "invitations/invitation-1": invitation({ expiresAt: "not-a-timestamp" }),
    "households/household-1": { name: "Our Home" },
  });
  const acceptance = createInvitationAcceptanceModule({ database, clock: fixedClock() });

  await assert.rejects(
    acceptance.acceptInvitation(acceptanceRequest()),
    UnknownInvitationError,
  );
  assert.equal(database.data("memberships/member-2"), undefined);
});

test("unknown invitations and an existing Household membership are rejected", async () => {
  const unknown = acceptanceFor({ invitationDocument: undefined });
  await assert.rejects(
    unknown.acceptInvitation(acceptanceRequest()),
    UnknownInvitationError,
  );

  const database = fakeDatabase({
    "invitations/invitation-1": invitation(),
    "households/household-1": { name: "Our Home" },
    "memberships/member-2": { householdId: "household-2", role: "member" },
  });
  const acceptance = createInvitationAcceptanceModule({ database, clock: fixedClock() });
  await assert.rejects(
    acceptance.acceptInvitation(acceptanceRequest()),
    AlreadyHouseholdMemberError,
  );
  assert.equal(database.data("invitations/invitation-1").status, "pending");
});

function acceptanceFor({ invitationDocument }) {
  const documents = {
    "households/household-1": { name: "Our Home" },
  };
  if (invitationDocument !== undefined) {
    documents["invitations/invitation-1"] = invitationDocument;
  }
  return createInvitationAcceptanceModule({
    database: fakeDatabase(documents),
    clock: fixedClock(),
  });
}

function acceptanceRequest() {
  return {
    invitationId: "invitation-1",
    memberId: "member-2",
    authenticatedEmail: "sam@example.com",
  };
}

function invitation(overrides = {}) {
  return {
    householdId: "household-1",
    intendedEmail: "sam@example.com",
    createdAt: new Date("2026-08-30T10:00:00Z"),
    expiresAt: new Date("2026-09-06T10:00:00Z"),
    status: "pending",
    replacesInvitationId: null,
    replacedByInvitationId: null,
    ...overrides,
  };
}

function fixedClock() {
  return {
    now: () => now,
    serverTimestamp: () => "SERVER_TIMESTAMP",
  };
}

function fakeDatabase(initialDocuments) {
  const documents = new Map(Object.entries(initialDocuments));
  return {
    doc(path) {
      return { path };
    },
    async runTransaction(work) {
      const writes = [];
      const result = await work({
        async get(reference) {
          const data = documents.get(reference.path);
          return {
            exists: data !== undefined,
            id: reference.path.split("/").at(-1),
            data: () => data,
          };
        },
        create(reference, data) {
          writes.push(() => {
            if (documents.has(reference.path)) throw new Error("already exists");
            documents.set(reference.path, data);
          });
        },
        update(reference, data) {
          writes.push(() => documents.set(reference.path, {
            ...documents.get(reference.path),
            ...data,
          }));
        },
      });
      writes.forEach((write) => write());
      return result;
    },
    data(path) {
      return documents.get(path);
    },
  };
}
