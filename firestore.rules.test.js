const { readFileSync } = require("node:fs");
const assert = require("node:assert/strict");
const { after, before, beforeEach, test } = require("node:test");
const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const {
  collection,
  doc,
  getDoc,
  getDocs,
  query,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  where,
  writeBatch,
} = require("firebase/firestore");

let testEnvironment;

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    projectId: "mystuff-ai-app",
    firestore: {
      rules: readFileSync("firestore.rules", "utf8"),
    },
  });
});

beforeEach(async () => {
  await testEnvironment.clearFirestore();
});

after(async () => {
  await testEnvironment.cleanup();
});

async function seedHousehold() {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    const database = context.firestore();
    await householdCreationBatch(database, "Our Home").commit();
  });
}

async function seedHouseholdMember() {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "memberships/member-2"), {
      householdId: "household-1",
      role: "member",
    });
  });
}

async function seedInvitation(
  invitationId = "invitation-1",
  data = invitationData(),
) {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), `invitations/${invitationId}`), data);
  });
}

function invitationData({
  email = "sam@example.com",
  createdAt = Timestamp.now(),
  expiresAt = Timestamp.fromMillis(createdAt.toMillis() + 7 * 24 * 60 * 60 * 1000),
  status = "pending",
  replacesInvitationId = null,
  replacedByInvitationId = null,
} = {}) {
  return {
    householdId: "household-1",
    intendedEmail: email,
    createdAt,
    expiresAt,
    status,
    replacesInvitationId,
    replacedByInvitationId,
  };
}

function invitationReplacementBatch(database, oldInvitationId, newInvitationId) {
  const batch = writeBatch(database);
  batch.update(doc(database, `invitations/${oldInvitationId}`), {
    status: "replaced",
    replacedByInvitationId: newInvitationId,
  });
  batch.set(
    doc(database, `invitations/${newInvitationId}`),
    invitationData({ replacesInvitationId: oldInvitationId }),
  );
  return batch;
}

function householdCreationBatch(database, name) {
  const batch = writeBatch(database);
  batch.set(doc(database, "memberships/member-1"), {
    householdId: "household-1",
    role: "owner",
  });
  batch.set(doc(database, "households/household-1"), {
    name,
    ownerMemberId: "member-1",
    rootItemId: "household-1",
    createdAt: serverTimestamp(),
  });
  batch.set(doc(database, "households/household-1/items/household-1"),
    rootItemData("household-1", name));
  return batch;
}

function rootItemData(householdId, name) {
  return {
    householdId,
    name,
    parentItemId: null,
    photoUrl: null,
    description: null,
    tags: [],
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    createdById: "member-1",
    createdByDisplayName: "Alex",
    updatedById: "member-1",
    updatedByDisplayName: "Alex",
  };
}

test("current Member can access the Household and its root Item", async () => {
  await seedHousehold();
  const database = testEnvironment.authenticatedContext("member-1").firestore();

  await assertSucceeds(getDoc(doc(database, "households/household-1")));
  await assertSucceeds(
    getDoc(doc(database, "households/household-1/items/household-1")),
  );
});

test("authenticated non-Member cannot access the Household or its root Item", async () => {
  await seedHousehold();
  const database = testEnvironment.authenticatedContext("member-2").firestore();

  await assertFails(getDoc(doc(database, "households/household-1")));
  await assertFails(
    getDoc(doc(database, "households/household-1/items/household-1")),
  );
});

test("Member can atomically create one Household and its single root Item", async () => {
  const database = testEnvironment.authenticatedContext("member-1").firestore();

  await assertSucceeds(householdCreationBatch(database, "Our Home").commit());
});

test("Member cannot replace their membership to create another Household", async () => {
  await seedHousehold();
  const database = testEnvironment.authenticatedContext("member-1").firestore();
  const batch = writeBatch(database);
  batch.set(doc(database, "memberships/member-1"), {
    householdId: "household-2",
    role: "owner",
  });
  batch.set(doc(database, "households/household-2"), {
    name: "Other Home",
    ownerMemberId: "member-1",
    rootItemId: "household-2",
    createdAt: serverTimestamp(),
  });

  await assertFails(batch.commit());
});

test("Household cannot gain another parentless Item", async () => {
  await seedHousehold();
  const database = testEnvironment.authenticatedContext("member-1").firestore();
  const batch = writeBatch(database);
  batch.set(doc(database, "households/household-1/items/another-root"),
    rootItemData("household-1", "Another Root"));

  await assertFails(batch.commit());
});

for (const invalidName of ["   ", " Our Home", "Our Home ", "　Our Home　"]) {
  test(`Household name must already be trimmed: ${JSON.stringify(invalidName)}`, async () => {
    const database = testEnvironment.authenticatedContext("member-1").firestore();

    await assertFails(householdCreationBatch(database, invalidName).commit());
  });
}

test("Household name accepts 100 Unicode characters and rejects 101", async () => {
  const database = testEnvironment.authenticatedContext("member-1").firestore();

  await assertSucceeds(householdCreationBatch(database, "🏠".repeat(100)).commit());

  await testEnvironment.clearFirestore();
  await assertFails(householdCreationBatch(database, "🏠".repeat(101)).commit());
});

test("only the Household Owner can create a pending invitation", async () => {
  await seedHousehold();
  await seedHouseholdMember();
  const ownerDatabase = testEnvironment.authenticatedContext("member-1").firestore();
  const memberDatabase = testEnvironment.authenticatedContext("member-2").firestore();

  await assertSucceeds(setDoc(
    doc(ownerDatabase, "invitations/invitation-1"),
    invitationData(),
  ));
  await assertFails(setDoc(
    doc(memberDatabase, "invitations/invitation-2"),
    invitationData(),
  ));
});

test("only the Household Owner can revoke a pending invitation", async () => {
  await seedHousehold();
  await seedHouseholdMember();
  await seedInvitation();
  const ownerDatabase = testEnvironment.authenticatedContext("member-1").firestore();
  const memberDatabase = testEnvironment.authenticatedContext("member-2").firestore();

  await assertFails(updateDoc(
    doc(memberDatabase, "invitations/invitation-1"),
    { status: "revoked" },
  ));
  await assertSucceeds(updateDoc(
    doc(ownerDatabase, "invitations/invitation-1"),
    { status: "revoked" },
  ));
});

test("replacement atomically invalidates the previous invitation link", async () => {
  await seedHousehold();
  await seedInvitation();
  const database = testEnvironment.authenticatedContext("member-1").firestore();

  await assertSucceeds(
    invitationReplacementBatch(
      database,
      "invitation-1",
      "invitation-2",
    ).commit(),
  );

  const previous = await getDoc(doc(database, "invitations/invitation-1"));
  const replacement = await getDoc(doc(database, "invitations/invitation-2"));
  assert.equal(previous.data().status, "replaced");
  assert.equal(previous.data().replacedByInvitationId, "invitation-2");
  assert.equal(replacement.data().status, "pending");
  assert.equal(replacement.data().replacesInvitationId, "invitation-1");
});

test("an invitation expiry is exactly seven days after creation", async () => {
  await seedHousehold();
  const database = testEnvironment.authenticatedContext("member-1").firestore();
  const createdAt = Timestamp.now();

  await assertFails(setDoc(
    doc(database, "invitations/invitation-1"),
    invitationData({
      createdAt,
      expiresAt: Timestamp.fromMillis(createdAt.toMillis() + 6 * 24 * 60 * 60 * 1000),
    }),
  ));
});

test("an invitation cannot extend validity with a future creation time", async () => {
  await seedHousehold();
  const database = testEnvironment.authenticatedContext("member-1").firestore();
  const createdAt = Timestamp.fromMillis(Date.now() + 4 * 60 * 1000);

  await assertFails(setDoc(
    doc(database, "invitations/invitation-1"),
    invitationData({ createdAt }),
  ));
});

test("an expired invitation can no longer be revoked or replaced", async () => {
  await seedHousehold();
  const createdAt = Timestamp.fromMillis(Date.now() - 8 * 24 * 60 * 60 * 1000);
  await seedInvitation("invitation-1", invitationData({ createdAt }));
  const database = testEnvironment.authenticatedContext("member-1").firestore();

  await assertFails(updateDoc(
    doc(database, "invitations/invitation-1"),
    { status: "revoked" },
  ));
  await assertFails(
    invitationReplacementBatch(
      database,
      "invitation-1",
      "invitation-2",
    ).commit(),
  );
});

test("only the Household Owner can record that a pending invitation expired", async () => {
  await seedHousehold();
  await seedHouseholdMember();
  const createdAt = Timestamp.fromMillis(Date.now() - 8 * 24 * 60 * 60 * 1000);
  await seedInvitation("invitation-1", invitationData({ createdAt }));
  const ownerDatabase = testEnvironment.authenticatedContext("member-1").firestore();
  const memberDatabase = testEnvironment.authenticatedContext("member-2").firestore();

  await assertFails(updateDoc(
    doc(memberDatabase, "invitations/invitation-1"),
    { status: "expired" },
  ));
  await assertSucceeds(updateDoc(
    doc(ownerDatabase, "invitations/invitation-1"),
    { status: "expired" },
  ));
});

test("a non-Owner cannot replace a pending invitation", async () => {
  await seedHousehold();
  await seedHouseholdMember();
  await seedInvitation();
  const database = testEnvironment.authenticatedContext("member-2").firestore();

  await assertFails(
    invitationReplacementBatch(
      database,
      "invitation-1",
      "invitation-2",
    ).commit(),
  );
});

test("only the Household Owner can list Household invitations", async () => {
  await seedHousehold();
  await seedHouseholdMember();
  await seedInvitation();
  const ownerDatabase = testEnvironment.authenticatedContext("member-1").firestore();
  const memberDatabase = testEnvironment.authenticatedContext("member-2").firestore();
  const householdInvitations = (database) => query(
    collection(database, "invitations"),
    where("householdId", "==", "household-1"),
  );

  await assertSucceeds(getDocs(householdInvitations(ownerDatabase)));
  await assertFails(getDocs(householdInvitations(memberDatabase)));
});
