# Storage access projection backfill

Storage Security Rules authorize a transfer from the Household document and,
for nested Item Attachments, the Item document. The Household document therefore
needs `storageMemberIds` and `storageAccessRevoked` before the corrected rules
are enabled. New Household creation and access lifecycle operations maintain
these fields automatically.

Run the dry run against the intended Firebase project first:

```bash
npm install --prefix functions
npm run backfill:storage-access --prefix functions -- --dry-run
npm run backfill:storage-access --prefix functions
```

Use `--household-id <id>` to stage one Household. The dry-run report lists the
complete replacement projection for each Household and preserves access
revocation for an existing Household Deletion marker, an Owner Account Deletion
marker, or a currently set `storageAccessRevoked` value. Review `updates` and
`failures`; the write run is idempotent and should have no failures before
deploying `storage.rules`.
