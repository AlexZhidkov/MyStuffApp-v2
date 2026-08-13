const { readFileSync } = require("node:fs");
const { after, before, beforeEach, test } = require("node:test");
const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const {
  doc,
  getDoc,
  serverTimestamp,
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
