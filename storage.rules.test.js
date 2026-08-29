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
    await setDoc(doc(context.firestore(), "households/household-1/items/item-1"), {
      householdId: "household-1",
      parentItemId: "household-1",
    });
  });
});

after(async () => {
  await testEnvironment.cleanup();
});

test("only a Household Member can transfer a captured immutable photo revision", async () => {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "memberships/member-2"), {
      householdId: "household-2",
      role: "owner",
    });
  });
  const revision = "11111111-1111-1111-1111-111111111111";
  const fullPath = `households/household-1/items/item-1-${revision}.webp`;
  const thumbnailPath = `households/household-1/items/item-1-${revision}-thumb.webp`;
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

test("a Household Member can access legacy unversioned Item photo variants", async () => {
  const memberStorage = testEnvironment.authenticatedContext("member-1").storage();
  const webPMetadata = { contentType: "image/webp" };

  await assertSucceeds(uploadBytes(
    ref(memberStorage, "households/household-1/items/item-1.webp"),
    new Uint8Array([1]),
    webPMetadata,
  ));
  await assertSucceeds(uploadBytes(
    ref(memberStorage, "households/household-1/items/item-1-thumb.webp"),
    new Uint8Array([1]),
    webPMetadata,
  ));
});

test("Item photo storage rejects legacy formats names MIME types and oversized variants", async () => {
  const memberStorage = testEnvironment.authenticatedContext("member-1").storage();
  const revision = "11111111-1111-1111-1111-111111111111";

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
    ref(memberStorage, `households/household-1/items/item-1-${revision}.webp`),
    new Uint8Array([1]),
    { contentType: "image/jpeg" },
  ));
  await assertFails(uploadBytes(
    ref(memberStorage, `households/household-1/items/item-1-${revision}.webp`),
    new Uint8Array(2 * 1024 * 1024 + 1),
    { contentType: "image/webp" },
  ));
  await assertFails(uploadBytes(
    ref(memberStorage, `households/household-1/items/item-1-${revision}-thumb.webp`),
    new Uint8Array(256 * 1024 + 1),
    { contentType: "image/webp" },
  ));
});

test("a Household Member can transfer an Item Attachment display image in its nested location", async () => {
  const memberStorage = testEnvironment.authenticatedContext("member-1").storage();
  const attachmentPath =
    "households/household-1/items/item-1/attachments/attachment-1.webp";
  const thumbnailPath =
    "households/household-1/items/item-1/attachments/attachment-1-thumb.webp";

  await assertSucceeds(uploadBytes(
    ref(memberStorage, attachmentPath),
    new Uint8Array([1]),
    { contentType: "image/webp" },
  ));
  await assertSucceeds(uploadBytes(
    ref(memberStorage, thumbnailPath),
    new Uint8Array([1]),
    { contentType: "image/webp" },
  ));
  await assertSucceeds(getBytes(ref(memberStorage, attachmentPath)));
  await assertSucceeds(getBytes(ref(memberStorage, thumbnailPath)));
  await assertSucceeds(deleteObject(ref(memberStorage, attachmentPath)));
  await assertSucceeds(deleteObject(ref(memberStorage, thumbnailPath)));
});

test("Item Attachment storage rejects cross-Household, root Item, and non-WebP locations", async () => {
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "memberships/member-2"), {
      householdId: "household-2",
      role: "owner",
    });
    await setDoc(doc(context.firestore(), "households/household-2/items/item-2"), {
      householdId: "household-2",
      parentItemId: "household-2",
    });
    await setDoc(doc(context.firestore(), "households/household-1/items/malformed-root"), {
      householdId: "household-1",
      parentItemId: null,
    });
  });
  const memberStorage = testEnvironment.authenticatedContext("member-1").storage();
  const otherMemberStorage = testEnvironment.authenticatedContext("member-2").storage();
  const webPMetadata = { contentType: "image/webp" };

  await assertFails(uploadBytes(
    ref(memberStorage, "households/household-2/items/item-2/attachments/attachment-1.webp"),
    new Uint8Array([1]),
    webPMetadata,
  ));
  await assertFails(uploadBytes(
    ref(otherMemberStorage, "households/household-1/items/item-1/attachments/attachment-1.webp"),
    new Uint8Array([1]),
    webPMetadata,
  ));
  await assertFails(uploadBytes(
    ref(memberStorage, "households/household-1/items/household-1/attachments/attachment-1.webp"),
    new Uint8Array([1]),
    webPMetadata,
  ));
  await assertFails(uploadBytes(
    ref(memberStorage, "households/household-1/items/malformed-root/attachments/attachment-1.webp"),
    new Uint8Array([1]),
    webPMetadata,
  ));
  await assertFails(uploadBytes(
    ref(memberStorage, "households/household-1/items/item-1/attachments/attachment-1.pdf"),
    new Uint8Array([1]),
    { contentType: "application/pdf" },
  ));
});
