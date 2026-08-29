import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { getApps, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { createItemPhotoBackfill } from "../src/item-photo-backfill.js";

const emulatorAvailable =
  process.env.FIRESTORE_EMULATOR_HOST !== undefined &&
  process.env.FIREBASE_STORAGE_EMULATOR_HOST !== undefined;
const householdId = "backfill-test-household";
const rootItemPath = `households/${householdId}/items/${householdId}`;
const legacyItemPath = `households/${householdId}/items/legacy-item`;
const immutableItemPath = `households/${householdId}/items/immutable-item`;
const existingItemPath = `households/${householdId}/items/already-migrated-item`;
const noPhotoItemPath = `households/${householdId}/items/no-photo-item`;
const malformedItemPath = `households/${householdId}/items/malformed-item`;
const storagePaths = [
  "households/backfill-test-household/items/legacy-item.webp",
  "households/backfill-test-household/items/legacy-item-thumb.webp",
  "households/backfill-test-household/items/legacy-item-old-revision.webp",
  "households/backfill-test-household/items/immutable-item/attachments/revision-2.webp",
  "households/backfill-test-household/items/immutable-item/attachments/revision-2-thumb.webp",
  "households/backfill-test-household/items/immutable-item/attachments/revision-1.webp",
];
let database;
let bucket;

before(async () => {
  if (!emulatorAvailable) return;
  if (getApps().length === 0) initializeApp({ projectId: "demo-mystuff-search" });
  database = getFirestore();
  bucket = getStorage().bucket("demo-mystuff-search.appspot.com");
  await database.recursiveDelete(database.doc(`households/${householdId}`));
  await seedBackfillData();
  await Promise.all(
    storagePaths.map((path) => bucket.file(path).save(Buffer.from(`original:${path}`))),
  );
});

after(async () => {
  if (!emulatorAvailable) return;
  await Promise.all(
    storagePaths.map((path) => bucket.file(path).delete({ ignoreNotFound: true })),
  );
  await database.recursiveDelete(database.doc(`households/${householdId}`));
});

test(
  "dry-run reports legacy and immutable Item Photos without changing Firestore",
  { skip: !emulatorAvailable },
  async () => {
    const backfill = createItemPhotoBackfill({ database });

    const report = await backfill.run({ dryRun: true, householdId });

    assert.equal(report.dryRun, true);
    assert.deepEqual(
      report.creations
        .map(({ itemId, displayUrl, thumbnailUrl }) => ({
          itemId,
          displayUrl,
          thumbnailUrl,
        }))
        .sort(byItemId),
      [
        {
          itemId: "immutable-item",
          displayUrl:
            "gs://mystuff/households/backfill-test-household/items/immutable-item/attachments/revision-2.webp",
          thumbnailUrl:
            "gs://mystuff/households/backfill-test-household/items/immutable-item/attachments/revision-2-thumb.webp",
        },
        {
          itemId: "legacy-item",
          displayUrl: "gs://mystuff/households/backfill-test-household/items/legacy-item.webp",
          thumbnailUrl:
            "gs://mystuff/households/backfill-test-household/items/legacy-item-thumb.webp",
        },
      ],
    );
    assert.deepEqual(
      report.skips
        .map(({ itemId, reason }) => ({ itemId, reason }))
        .sort(byItemId),
      [
        { itemId: "already-migrated-item", reason: "already-migrated" },
        { itemId: householdId, reason: "root-item" },
        { itemId: "no-photo-item", reason: "no-item-photo" },
      ],
    );
    assert.deepEqual(
      report.malformed
        .map(({ itemId, reason }) => ({ itemId, reason }))
        .sort(byItemId),
      [{ itemId: "malformed-item", reason: "incomplete-item-photo" }],
    );
    assert.equal(
      (await database.doc(legacyItemPath).get()).data().photoAttachmentId,
      null,
    );
    assert.equal(
      (await database.collection(`${legacyItemPath}/attachments`).get()).empty,
      true,
    );
    assert.deepEqual(await storageContents(), originalStorageContents());
  },
);

test(
  "backfill creates metadata for the projected pair and is idempotent",
  { skip: !emulatorAvailable },
  async () => {
    const backfill = createItemPhotoBackfill({ database });

    const firstReport = await backfill.run({ householdId });
    assert.equal(firstReport.creations.length, 2);

    const legacyItem = await database.doc(legacyItemPath).get();
    const legacyAttachmentId = legacyItem.data().photoAttachmentId;
    assert.equal(typeof legacyAttachmentId, "string");
    assert.equal(legacyItem.data().photoUrl, "gs://mystuff/households/backfill-test-household/items/legacy-item.webp");
    assert.equal(
      legacyItem.data().photoThumbnailUrl,
      "gs://mystuff/households/backfill-test-household/items/legacy-item-thumb.webp",
    );
    assert.deepEqual(
      (await database.doc(`${legacyItemPath}/attachments/${legacyAttachmentId}`).get()).data(),
      {
        createdAt: Timestamp.fromMillis(1_700_000_000_000),
        contentType: "image/webp",
        displayUrl: "gs://mystuff/households/backfill-test-household/items/legacy-item.webp",
      },
    );

    const immutableItem = await database.doc(immutableItemPath).get();
    const immutableAttachmentId = immutableItem.data().photoAttachmentId;
    assert.deepEqual(
      (await database.doc(`${immutableItemPath}/attachments/${immutableAttachmentId}`).get()).data(),
      {
        createdAt: Timestamp.fromMillis(1_700_000_000_001),
        contentType: "image/webp",
        displayUrl:
          "gs://mystuff/households/backfill-test-household/items/immutable-item/attachments/revision-2.webp",
      },
    );

    const secondReport = await backfill.run({ householdId });
    assert.equal(secondReport.creations.length, 0);
    assert.deepEqual(
      secondReport.skips
        .filter(({ reason }) => reason === "already-migrated")
        .map(({ itemId }) => itemId),
      ["already-migrated-item", "immutable-item", "legacy-item"],
    );
    assert.equal(
      (await database.collection(`${legacyItemPath}/attachments`).get()).size,
      1,
    );
    assert.equal(
      (await database.collection(`${immutableItemPath}/attachments`).get()).size,
      1,
    );
    assert.deepEqual(await storageContents(), originalStorageContents());
  },
);

async function seedBackfillData() {
  const createdAt = Timestamp.fromMillis(1_700_000_000_000);
  await database.doc(`households/${householdId}`).set({
    name: "Backfill Test Household",
    rootItemId: householdId,
  });
  await database.doc(rootItemPath).set(itemData({ parentItemId: null }));
  await database.doc(legacyItemPath).set(itemData({
    parentItemId: householdId,
    photoUrl:
      "gs://mystuff/households/backfill-test-household/items/legacy-item.webp",
    photoThumbnailUrl:
      "gs://mystuff/households/backfill-test-household/items/legacy-item-thumb.webp",
    createdAt,
  }));
  await database.doc(immutableItemPath).set(itemData({
    parentItemId: householdId,
    photoUrl:
      "gs://mystuff/households/backfill-test-household/items/immutable-item/attachments/revision-2.webp",
    photoThumbnailUrl:
      "gs://mystuff/households/backfill-test-household/items/immutable-item/attachments/revision-2-thumb.webp",
    createdAt: Timestamp.fromMillis(1_700_000_000_001),
  }));
  await database.doc(existingItemPath).set(itemData({
    parentItemId: householdId,
    photoAttachmentId: "existing-attachment",
    photoUrl: "gs://mystuff/already-migrated.webp",
    photoThumbnailUrl: "gs://mystuff/already-migrated-thumb.webp",
  }));
  await database.doc(`${existingItemPath}/attachments/existing-attachment`).set({
    createdAt,
    contentType: "image/webp",
    displayUrl: "gs://mystuff/already-migrated.webp",
  });
  await database.doc(noPhotoItemPath).set(itemData({ parentItemId: householdId }));
  await database.doc(malformedItemPath).set(itemData({
    parentItemId: householdId,
    photoUrl: "gs://mystuff/missing-thumb.webp",
    photoThumbnailUrl: null,
  }));
}

function itemData({
  parentItemId,
  photoAttachmentId = null,
  photoUrl = null,
  photoThumbnailUrl = null,
  createdAt = Timestamp.fromMillis(1_700_000_000_100),
}) {
  return {
    householdId,
    name: "Backfill Item",
    parentItemId,
    photoAttachmentId,
    photoUrl,
    photoThumbnailUrl,
    description: null,
    tags: [],
    createdAt,
  };
}

function byItemId(left, right) {
  return left.itemId.localeCompare(right.itemId);
}

async function storageContents() {
  const downloads = await Promise.all(
    storagePaths.map((path) => bucket.file(path).download()),
  );
  return downloads.map(([contents]) => contents.toString());
}

function originalStorageContents() {
  return storagePaths.map((path) => `original:${path}`);
}
