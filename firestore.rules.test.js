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

async function seedOtherHousehold() {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    const database = context.firestore();
    await setDoc(doc(database, "memberships/member-3"), {
      householdId: "household-2",
      role: "owner",
    });
    await setDoc(doc(database, "households/household-2"), {
      name: "Other Home",
      ownerMemberId: "member-3",
      rootItemId: "household-2",
      createdAt: serverTimestamp(),
    });
    await setDoc(
      doc(database, "households/household-2/items/household-2"),
      rootItemData("household-2", "Other Home"),
    );
    await setDoc(
      doc(database, "households/household-2/items/item-2"),
      childItemData("Saw", "household-2", { householdId: "household-2" }),
    );
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

async function createInvitation(database, invitationId, email = "sam@example.com") {
  const invitationReference = doc(database, `invitations/${invitationId}`);
  const draftReference = doc(database, `invitationDrafts/${invitationId}`);
  await setDoc(draftReference, {
    householdId: "household-1",
    intendedEmail: email,
    createdAt: serverTimestamp(),
    replacesInvitationId: null,
  });
  const createdAt = (await getDoc(draftReference)).data().createdAt;
  const batch = writeBatch(database);
  batch.set(invitationReference, invitationData({ createdAt }));
  batch.delete(draftReference);
  await batch.commit();
}

async function createInvitationDraft(database, invitationId, oldInvitationId = null) {
  const reference = doc(database, `invitationDrafts/${invitationId}`);
  await setDoc(reference, {
    householdId: "household-1",
    intendedEmail: "sam@example.com",
    createdAt: serverTimestamp(),
    replacesInvitationId: oldInvitationId,
  });
  return (await getDoc(reference)).data().createdAt;
}

function pendingInvitationFromDraft(createdAt, replacesInvitationId = null) {
  return invitationData({
    createdAt,
    replacesInvitationId,
    status: "pending",
  });
}

async function replaceInvitation(database, oldInvitationId, newInvitationId) {
  const replacementReference = doc(database, `invitations/${newInvitationId}`);
  const draftReference = doc(database, `invitationDrafts/${newInvitationId}`);
  const createdAt = await createInvitationDraft(
    database,
    newInvitationId,
    oldInvitationId,
  );
  const batch = writeBatch(database);
  batch.update(doc(database, `invitations/${oldInvitationId}`), {
    status: "replaced",
    replacedByInvitationId: newInvitationId,
  });
  batch.set(
    replacementReference,
    pendingInvitationFromDraft(createdAt, oldInvitationId),
  );
  batch.delete(draftReference);
  await batch.commit();
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
    webUrl: null,
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

function childItemData(name, parentItemId, overrides = {}) {
  const data = {
    householdId: "household-1",
    name,
    parentItemId,
    photoUrl: null,
    webUrl: null,
    description: null,
    tags: [],
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    createdById: "member-1",
    createdByDisplayName: "Alex",
    updatedById: "member-1",
    updatedByDisplayName: "Alex",
    ...overrides,
  };
  return data;
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

test("Member can create duplicate Child Item names beneath an existing Parent Item", async () => {
  await seedHousehold();
  const database = testEnvironment.authenticatedContext("member-1").firestore();

  await assertSucceeds(setDoc(
    doc(database, "households/household-1/items/item-1"),
    childItemData("Box", "household-1"),
  ));
  await assertSucceeds(setDoc(
    doc(database, "households/household-1/items/item-2"),
    childItemData("Box", "household-1"),
  ));
});

test("Member can create a Child Item with an optional photo URL", async () => {
  await seedHousehold();
  const database = testEnvironment.authenticatedContext("member-1").firestore();

  await assertSucceeds(setDoc(
    doc(database, "households/household-1/items/item-1"),
    childItemData("Drill", "household-1", {
      photoUrl: "https://photos.example/item-1.jpg",
    }),
  ));
});

test("Child Item accepts an optional HTTP web URL and rejects other schemes", async () => {
  await seedHousehold();
  const database = testEnvironment.authenticatedContext("member-1").firestore();

  await assertSucceeds(setDoc(
    doc(database, "households/household-1/items/item-1"),
    childItemData("Drill", "household-1", {
      webUrl: "https://example.com/products/drill",
    }),
  ));
  await assertFails(setDoc(
    doc(database, "households/household-1/items/item-2"),
    childItemData("Saw", "household-1", {
      webUrl: "javascript:alert(1)",
    }),
  ));
});

test("Child Item requires a trimmed one-to-one-hundred-character name", async () => {
  await seedHousehold();
  const database = testEnvironment.authenticatedContext("member-1").firestore();
  const reference = doc(database, "households/household-1/items/item-1");

  await assertFails(setDoc(reference, childItemData(" Box", "household-1")));
  await assertFails(setDoc(reference, childItemData(" ", "household-1")));
  await assertSucceeds(setDoc(
    reference,
    childItemData("🏠".repeat(100), "household-1"),
  ));

  await assertFails(setDoc(
    doc(database, "households/household-1/items/item-2"),
    childItemData("🏠".repeat(101), "household-1"),
  ));
});

test("Child Item must name an existing Parent Item in the same Household", async () => {
  await seedHousehold();
  const database = testEnvironment.authenticatedContext("member-1").firestore();

  await assertFails(setDoc(
    doc(database, "households/household-1/items/lost"),
    childItemData("Lost", "missing-parent"),
  ));
  await assertFails(setDoc(
    doc(database, "households/household-1/items/household-1"),
    childItemData("Another root", "household-1"),
  ));
});

test("non-Member cannot create a Child Item", async () => {
  await seedHousehold();
  const database = testEnvironment.authenticatedContext("member-2").firestore();

  await assertFails(setDoc(
    doc(database, "households/household-1/items/item-1"),
    childItemData("Box", "household-1"),
  ));
});

test("Household Member can create a Child Item", async () => {
  await seedHousehold();
  await seedHouseholdMember();
  const database = testEnvironment.authenticatedContext("member-2").firestore();

  await assertSucceeds(setDoc(
    doc(database, "households/household-1/items/item-1"),
    childItemData("Box", "household-1", {
      createdById: "member-2",
      createdByDisplayName: "Sam",
      updatedById: "member-2",
      updatedByDisplayName: "Sam",
    }),
  ));
});

test("Child Item accepts bounded descriptions and trimmed Tags", async () => {
  await seedHousehold();
  const database = testEnvironment.authenticatedContext("member-1").firestore();
  const validReference = doc(database, "households/household-1/items/item-1");

  await assertSucceeds(setDoc(
    validReference,
    childItemData("Drill", "household-1", {
      description: "🏠".repeat(2000),
      tags: ["Power Tools", "DIY"],
    }),
  ));
  await assertFails(setDoc(
    doc(database, "households/household-1/items/item-2"),
    childItemData("Saw", "household-1", { description: "x".repeat(2001) }),
  ));
  await assertFails(setDoc(
    doc(database, "households/household-1/items/item-3"),
    childItemData("Saw", "household-1", { tags: [" Power Tools"] }),
  ));
  await assertFails(setDoc(
    doc(database, "households/household-1/items/item-4"),
    childItemData("Saw", "household-1", {
      tags: Array.from({ length: 21 }, (_, index) => `Tag ${index}`),
    }),
  ));
});

test("Household Member can update Child Item details with fresh attribution", async () => {
  await seedHousehold();
  await seedHouseholdMember();
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(
      doc(context.firestore(), "households/household-1/items/item-1"),
      childItemData("Drill", "household-1"),
    );
  });
  const database = testEnvironment.authenticatedContext("member-2").firestore();
  const reference = doc(database, "households/household-1/items/item-1");

  await assertSucceeds(updateDoc(reference, {
    name: "Hammer Drill",
    description: "18V cordless",
    tags: ["Power Tools"],
    photoUrl: null,
    photoThumbnailUrl: null,
    updatedAt: serverTimestamp(),
    updatedById: "member-2",
    updatedByDisplayName: "Sam",
  }));

  const updated = (await getDoc(reference)).data();
  assert.equal(updated.createdById, "member-1");
  assert.equal(updated.updatedById, "member-2");
});

test("Household Member can perform the full Save and generated Description patch", async () => {
  await seedHousehold();
  await seedHouseholdMember();
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(
      doc(context.firestore(), "households/household-1/items/item-1"),
      childItemData("Drill", "household-1"),
    );
  });
  const database = testEnvironment.authenticatedContext("member-2").firestore();
  const reference = doc(database, "households/household-1/items/item-1");

  await assertSucceeds(updateDoc(reference, {
    name: "Hammer Drill",
    photoUrl: "gs://mystuff/households/household-1/items/item-1-revision.webp",
    photoThumbnailUrl:
      "gs://mystuff/households/household-1/items/item-1-revision-thumb.webp",
    description: "Member facts",
    tags: ["Power Tools"],
    webUrl: "https://example.com/hammer-drill",
    updatedAt: serverTimestamp(),
    updatedById: "member-2",
    updatedByDisplayName: "Sam",
  }));
  await assertSucceeds(updateDoc(reference, {
    description: "Generated replacement",
    updatedAt: serverTimestamp(),
    updatedById: "member-2",
    updatedByDisplayName: "Sam",
  }));

  const updated = (await getDoc(reference)).data();
  assert.equal(updated.name, "Hammer Drill");
  assert.equal(updated.description, "Generated replacement");
  assert.equal(updated.createdById, "member-1");
  assert.equal(updated.updatedById, "member-2");
});

test("Household Member cannot run Description Generation writes across Households", async () => {
  await seedHousehold();
  await seedHouseholdMember();
  await seedOtherHousehold();
  const database = testEnvironment.authenticatedContext("member-2").firestore();
  const reference = doc(database, "households/household-2/items/item-2");

  await assertFails(updateDoc(reference, {
    name: "Circular Saw",
    photoUrl: "gs://mystuff/households/household-2/items/item-2.webp",
    photoThumbnailUrl: null,
    description: "Member facts",
    tags: ["Power Tools"],
    webUrl: null,
    updatedAt: serverTimestamp(),
    updatedById: "member-2",
    updatedByDisplayName: "Sam",
  }));
  await assertFails(updateDoc(reference, {
    description: "Generated replacement",
    updatedAt: serverTimestamp(),
    updatedById: "member-2",
    updatedByDisplayName: "Sam",
  }));
});

test("Child Item update cannot change creation attribution Parent Item or root fields", async () => {
  await seedHousehold();
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(
      doc(context.firestore(), "households/household-1/items/item-1"),
      childItemData("Drill", "household-1"),
    );
  });
  const database = testEnvironment.authenticatedContext("member-1").firestore();
  const childReference = doc(database, "households/household-1/items/item-1");

  await assertFails(updateDoc(childReference, { createdById: "member-2" }));
  await assertFails(updateDoc(childReference, { parentItemId: "item-1" }));
  await assertFails(updateDoc(
    doc(database, "households/household-1/items/household-1"),
    { description: "Root description" },
  ));
});

test("only the Household Owner can create a pending invitation", async () => {
  await seedHousehold();
  await seedHouseholdMember();
  const ownerDatabase = testEnvironment.authenticatedContext("member-1").firestore();
  const memberDatabase = testEnvironment.authenticatedContext("member-2").firestore();

  await assertSucceeds(createInvitation(ownerDatabase, "invitation-1"));
  await assertFails(createInvitation(memberDatabase, "invitation-2"));
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
    replaceInvitation(
      database,
      "invitation-1",
      "invitation-2",
    ),
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
  const createdAt = await createInvitationDraft(database, "invitation-1");
  const invitationReference = doc(database, "invitations/invitation-1");
  const draftReference = doc(database, "invitationDrafts/invitation-1");
  const batch = writeBatch(database);
  batch.set(
    invitationReference,
    invitationData({
      createdAt,
      expiresAt: Timestamp.fromMillis(createdAt.toMillis() + 6 * 24 * 60 * 60 * 1000),
    }),
  );
  batch.delete(draftReference);

  await assertFails(batch.commit());
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
    replaceInvitation(
      database,
      "invitation-1",
      "invitation-2",
    ),
  );
});

test("a non-Owner cannot replace a pending invitation", async () => {
  await seedHousehold();
  await seedHouseholdMember();
  await seedInvitation();
  const database = testEnvironment.authenticatedContext("member-2").firestore();

  await assertFails(
    replaceInvitation(
      database,
      "invitation-1",
      "invitation-2",
    ),
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
