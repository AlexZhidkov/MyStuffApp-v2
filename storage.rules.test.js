const { readFileSync } = require("node:fs");
const { after, before, beforeEach, test } = require("node:test");
const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const { doc, setDoc } = require("firebase/firestore");
const { deleteObject, getBytes, ref, uploadBytes } = require("firebase/storage");

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

test("only a Household Member can access the two expected Item WebP variants", async () => {
  const fullPath = "households/household-1/items/item-1.webp";
  const thumbnailPath = "households/household-1/items/item-1-thumb.webp";
  const webPMetadata = { contentType: "image/webp" };
  const memberStorage = testEnvironment.authenticatedContext("member-1").storage();
  const nonMemberStorage = testEnvironment.authenticatedContext("member-2").storage();

  await assertSucceeds(uploadBytes(
    ref(memberStorage, fullPath),
    new Uint8Array([1]),
    webPMetadata,
  ));
  await assertSucceeds(uploadBytes(
    ref(memberStorage, thumbnailPath),
    new Uint8Array([1]),
    webPMetadata,
  ));
  await assertSucceeds(uploadBytes(
    ref(memberStorage, fullPath),
    new Uint8Array([2]),
    webPMetadata,
  ));
  await assertSucceeds(uploadBytes(
    ref(memberStorage, thumbnailPath),
    new Uint8Array([2]),
    webPMetadata,
  ));
  await assertSucceeds(getBytes(ref(memberStorage, fullPath)));
  await assertSucceeds(getBytes(ref(memberStorage, thumbnailPath)));
  await assertFails(getBytes(ref(nonMemberStorage, fullPath)));
  await assertFails(uploadBytes(
    ref(nonMemberStorage, fullPath),
    new Uint8Array([1]),
    webPMetadata,
  ));
  await assertSucceeds(deleteObject(ref(memberStorage, fullPath)));
  await assertSucceeds(deleteObject(ref(memberStorage, thumbnailPath)));
});

test("Item photo storage rejects legacy formats names MIME types and oversized variants", async () => {
  const memberStorage = testEnvironment.authenticatedContext("member-1").storage();

  await assertFails(uploadBytes(
    ref(memberStorage, "households/household-1/items/item-1/photo.jpg"),
    new Uint8Array([1]),
    { contentType: "image/jpeg" },
  ));
  await assertFails(uploadBytes(
    ref(memberStorage, "households/household-1/items/item-1.png"),
    new Uint8Array([1]),
    { contentType: "image/png" },
  ));
  await assertFails(uploadBytes(
    ref(memberStorage, "households/household-1/items/item-1.webp"),
    new Uint8Array([1]),
    { contentType: "image/jpeg" },
  ));
  await assertFails(uploadBytes(
    ref(memberStorage, "households/household-1/items/item-1.webp"),
    new Uint8Array(2 * 1024 * 1024 + 1),
    { contentType: "image/webp" },
  ));
  await assertFails(uploadBytes(
    ref(memberStorage, "households/household-1/items/item-1-thumb.webp"),
    new Uint8Array(256 * 1024 + 1),
    { contentType: "image/webp" },
  ));
});
