# 03: Prepare the existing Item Photo backfill

**What to build:** Provide a safe one-time migration that gives every existing Item Photo an Item Attachment record without copying its stored image data.

**Blocked by:** 02: Back the existing Item Photo with an Item Attachment.

**Status:** ready-for-agent

- [ ] The migration finds non-root Items whose projected Item Photo has no corresponding attachment and creates exactly one attachment referencing the currently projected display image and thumbnail.
- [ ] Superseded or orphaned Storage revisions are not inferred as live attachments; only the pair referenced by the Item is migrated.
- [ ] Re-running the migration produces no duplicate attachments or projection changes.
- [ ] A dry-run reports intended creations, skips, and malformed Items without mutating data.
- [ ] Emulator-backed verification covers legacy and immutable-revision Storage references without copying either blob.
- [ ] The rollout notes require Members to upgrade before the new client is enabled and do not introduce dual-client compatibility or version enforcement.
