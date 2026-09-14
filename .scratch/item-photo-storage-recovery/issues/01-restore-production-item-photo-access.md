# 01: Restore production Item Photo access within the Storage rules budget

**What to build:** Restore production Item Photo and Item Attachment transfers for authorized Household Members without weakening Household isolation or the immediate access revocation promised by Account Deletion and Household deletion.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] An active Household Member can upload, read, and delete the full-size and thumbnail objects for an Item Photo and every supported Item Attachment path against real Firebase Storage.
- [ ] A Member outside the Household remains unable to read or mutate those objects.
- [ ] Account Deletion and Household deletion continue to revoke Storage access as soon as their durable deletion job requires, before asynchronous cleanup completes.
- [ ] Every allowed and denied Storage authorization path performs no more than two distinct cross-service Firestore document accesses per evaluation, including paths for new attachments and retained legacy Item Photos.
- [ ] The authorization design has regression coverage that can detect the production Firestore-access budget; passing the Firebase emulator suite alone is not accepted as evidence.
- [ ] Existing Storage and Firestore authorization tests continue to cover active membership, removed membership, cross-Household access, malformed paths, and deletion in progress.
- [ ] The corrected rules and any supporting lifecycle changes are verified in the configured Firebase environment with an authorized Photo upload/read/delete smoke test and a denied cross-Household check.
