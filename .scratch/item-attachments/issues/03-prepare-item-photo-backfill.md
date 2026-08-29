# 03: Prepare the existing Item Photo backfill

**What to build:** Provide a safe one-time migration that gives every existing Item Photo an Item Attachment record without copying its stored image data.

**Blocked by:** 02: Back the existing Item Photo with an Item Attachment.

**Status:** implemented

- [x] The migration finds non-root Items whose projected Item Photo has no corresponding attachment and creates exactly one attachment referencing the currently projected display image and thumbnail.
- [x] Superseded or orphaned Storage revisions are not inferred as live attachments; only the pair referenced by the Item is migrated.
- [x] Re-running the migration produces no duplicate attachments or projection changes.
- [x] A dry-run reports intended creations, skips, and malformed Items without mutating data.
- [x] Emulator-backed verification covers legacy and immutable-revision Storage references without copying either blob.
- [x] The rollout notes require Members to upgrade before the new client is enabled and do not introduce dual-client compatibility or version enforcement.

## Comments

- Added the Admin Firestore metadata backfill and CLI with Household scoping and dry-run reporting.
- Each Item migration atomically creates one attachment record and adds its `photoAttachmentId`; Storage files are never read or copied.
- Added Firestore emulator coverage for legacy and immutable-revision projected locations, malformed and already-migrated Items, dry-run safety, and idempotent reruns.
- Added [Item Photo metadata backfill rollout notes](../../../docs/item-photo-backfill.md).
