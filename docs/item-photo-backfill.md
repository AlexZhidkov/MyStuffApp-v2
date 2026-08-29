# Item Photo metadata backfill

The one-time backfill creates an Item Attachment metadata record for each
non-root Item whose legacy Item Photo projection has no `photoAttachmentId`.
It records the projected `photoUrl` as the attachment `displayUrl` and leaves
the projected `photoThumbnailUrl` on the Item. It never reads, copies, or
rewrites Firebase Storage data, so both legacy flat paths and immutable-revision
paths remain unchanged.

Run this after deploying the attachment-backed client and before enabling that
client for Members:

```bash
npm install --prefix functions
npm run backfill:item-photos --prefix functions -- --dry-run
npm run backfill:item-photos --prefix functions
```

Use `--household-id <id>` to stage one Household at a time. Review the dry-run
report's `creations`, `skips`, and `malformed` entries before the write run.
The write run creates the attachment metadata and `photoAttachmentId`
projection in one Firestore transaction per Item; a repeated run is safe and
does not change already migrated Items. Resolve every `malformed` entry and
rerun until the report contains no intended creations or failures.

Members must upgrade to the attachment-backed client before it is enabled for
the Household. This rollout uses no dual-client compatibility period and adds
no client version enforcement.
