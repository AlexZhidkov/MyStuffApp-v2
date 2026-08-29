import { createHash } from "node:crypto";

const HOUSEHOLDS = "households";
const ITEMS = "items";
const ATTACHMENTS = "attachments";
const PHOTO_ATTACHMENT_ID = "photoAttachmentId";
const PHOTO_URL = "photoUrl";
const PHOTO_THUMBNAIL_URL = "photoThumbnailUrl";
const CREATED_AT = "createdAt";
const CONTENT_TYPE = "contentType";
const DISPLAY_URL = "displayUrl";
const IMAGE_CONTENT_TYPE = "image/webp";

/**
 * Creates the one-time metadata backfill for legacy Item Photo projections.
 *
 * This module deliberately has no Storage dependency. The two projected
 * locations are copied into Firestore metadata only; neither blob is read,
 * copied, or rewritten.
 */
export function createItemPhotoBackfill({ database }) {
  if (database === undefined || database === null) {
    throw new TypeError("An Admin Firestore database is required.");
  }

  return {
    async run({ dryRun = false, householdId } = {}) {
      const report = emptyReport(dryRun);
      const households = householdId === undefined
        ? await database.collection(HOUSEHOLDS).get()
        : { docs: [{ id: householdId }] };

      for (const household of households.docs) {
        const items = await householdItems(database, household.id).get();
        for (const item of items.docs) {
          report.scannedItems += 1;
          await processItem({
            database,
            householdId: household.id,
            item,
            dryRun,
            report,
          });
        }
      }
      return report;
    },
  };
}

async function processItem({ database, householdId, item, dryRun, report }) {
  const classification = await classifyItem({ database, householdId, item });
  if (classification.kind === "skip") {
    report.skips.push(reportEntry(householdId, item.id, classification.reason));
    return;
  }
  if (classification.kind === "malformed") {
    report.malformed.push(reportEntry(householdId, item.id, classification.reason));
    return;
  }

  const planned = creationEntry(householdId, item.id, classification);
  if (dryRun) {
    report.creations.push(planned);
    return;
  }

  try {
    const result = await migrateItem({
      database,
      householdId,
      itemId: item.id,
      attachmentId: planned.attachmentId,
    });
    if (result.kind === "created") {
      report.creations.push({
        ...planned,
        displayUrl: result.displayUrl,
        thumbnailUrl: result.thumbnailUrl,
      });
    } else if (result.kind === "skip") {
      report.skips.push(reportEntry(householdId, item.id, result.reason));
    } else {
      report.malformed.push(reportEntry(householdId, item.id, result.reason));
    }
  } catch (error) {
    report.failures.push({
      householdId,
      itemId: item.id,
      message: error instanceof Error ? error.message : String(error),
    });
  }
}

async function classifyItem({ database, householdId, item }) {
  const data = item.data() ?? {};
  if (item.id === householdId) return { kind: "skip", reason: "root-item" };
  if (data.householdId !== householdId || typeof data.parentItemId !== "string") {
    return { kind: "malformed", reason: "invalid-item-ownership" };
  }

  const attachmentId = data[PHOTO_ATTACHMENT_ID];
  const displayUrl = data[PHOTO_URL];
  const thumbnailUrl = data[PHOTO_THUMBNAIL_URL];
  const hasDisplayUrl = displayUrl !== null && displayUrl !== undefined;
  const hasThumbnailUrl = thumbnailUrl !== null && thumbnailUrl !== undefined;

  if (attachmentId !== null && attachmentId !== undefined && typeof attachmentId !== "string") {
    return { kind: "malformed", reason: "invalid-photo-attachment-id" };
  }
  if (!hasDisplayUrl && !hasThumbnailUrl && attachmentId == null) {
    return { kind: "skip", reason: "no-item-photo" };
  }
  if (
    typeof displayUrl !== "string" ||
    displayUrl.length === 0 ||
    typeof thumbnailUrl !== "string" ||
    thumbnailUrl.length === 0
  ) {
    return { kind: "malformed", reason: "incomplete-item-photo" };
  }
  if (attachmentId === null || attachmentId === undefined) {
    if (!isFirestoreTimestamp(data[CREATED_AT])) {
      return { kind: "malformed", reason: "missing-created-at" };
    }
    return {
      kind: "candidate",
      createdAt: data[CREATED_AT],
      displayUrl,
      thumbnailUrl,
    };
  }
  if (attachmentId.length === 0) {
    return { kind: "malformed", reason: "invalid-photo-attachment-id" };
  }

  const attachment = await item.ref.collection(ATTACHMENTS).doc(attachmentId).get();
  if (!attachment.exists) {
    return { kind: "malformed", reason: "missing-photo-attachment" };
  }
  if (attachment.data()?.[DISPLAY_URL] !== displayUrl) {
    return { kind: "malformed", reason: "photo-attachment-does-not-match" };
  }
  return { kind: "skip", reason: "already-migrated" };
}

async function migrateItem({ database, householdId, itemId, attachmentId }) {
  const itemRef = householdItems(database, householdId).doc(itemId);
  const attachmentRef = itemRef.collection(ATTACHMENTS).doc(attachmentId);

  return database.runTransaction(async (transaction) => {
    const currentItem = await transaction.get(itemRef);
    if (!currentItem.exists) return { kind: "skip", reason: "item-deleted" };

    const classification = await classifyItem({
      database,
      householdId,
      item: currentItem,
    });
    if (classification.kind === "skip") return classification;
    if (classification.kind === "malformed") return classification;

    const currentAttachment = await transaction.get(attachmentRef);
    if (currentAttachment.exists) {
      return { kind: "malformed", reason: "attachment-id-collision" };
    }

    transaction.create(attachmentRef, {
      [CREATED_AT]: classification.createdAt,
      [CONTENT_TYPE]: IMAGE_CONTENT_TYPE,
      [DISPLAY_URL]: classification.displayUrl,
    });
    transaction.update(itemRef, { [PHOTO_ATTACHMENT_ID]: attachmentId });
    return {
      kind: "created",
      displayUrl: classification.displayUrl,
      thumbnailUrl: classification.thumbnailUrl,
    };
  });
}

function creationEntry(householdId, itemId, classification) {
  return {
    householdId,
    itemId,
    attachmentId: legacyAttachmentId(itemId),
    displayUrl: classification.displayUrl,
    thumbnailUrl: classification.thumbnailUrl,
  };
}

function legacyAttachmentId(itemId) {
  const digest = createHash("sha256").update(itemId, "utf8").digest("hex").slice(0, 24);
  return `legacy-item-photo-${digest}`;
}

function emptyReport(dryRun) {
  return {
    dryRun,
    scannedItems: 0,
    creations: [],
    skips: [],
    malformed: [],
    failures: [],
  };
}

function householdItems(database, householdId) {
  return database.collection(HOUSEHOLDS).doc(householdId).collection(ITEMS);
}

function reportEntry(householdId, itemId, reason) {
  return { householdId, itemId, reason };
}

function isFirestoreTimestamp(value) {
  return value !== null &&
    typeof value === "object" &&
    typeof value.toMillis === "function" &&
    typeof value.seconds === "number";
}
