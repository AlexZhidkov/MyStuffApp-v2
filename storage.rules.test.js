const { readFileSync } = require("node:fs");
const { after, before, beforeEach, test } = require("node:test");
const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const { doc, setDoc } = require("firebase/firestore");
const { getBytes, ref, uploadBytes } = require("firebase/storage");

let testEnvironment;

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    projectId: "mystuff-ai-app",
    firestore: {
      rules: readFileSync("firestore.rules", "utf8"),
    },
    storage: {
      rules: readFileSync("storage.rules", "utf8"),
    },
  });
});

beforeEach(async () => {
  await testEnvironment.clearFirestore();
  await testEnvironment.clearStorage();
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "memberships/member-1"), {
      householdId: "household-1",
      role: "owner",
    });
  });
});

after(async () => {
  await testEnvironment.cleanup();
});

test("only a Household Member can store an Item JPEG in that Household", async () => {
  const path = "households/household-1/items/item-1/photo.jpg";
  const jpegMetadata = { contentType: "image/jpeg" };
  const memberStorage = testEnvironment.authenticatedContext("member-1").storage();
  const nonMemberStorage = testEnvironment.authenticatedContext("member-2").storage();

  await assertSucceeds(uploadBytes(ref(memberStorage, path), new Uint8Array([1]), jpegMetadata));
  await assertSucceeds(getBytes(ref(memberStorage, path)));
  await assertFails(getBytes(ref(nonMemberStorage, path)));
  await assertFails(uploadBytes(ref(nonMemberStorage, path), new Uint8Array([1]), jpegMetadata));
  await assertFails(uploadBytes(
    ref(memberStorage, "households/household-1/items/item-2/photo.png"),
    new Uint8Array([1]),
    { contentType: "image/png" },
  ));
});
