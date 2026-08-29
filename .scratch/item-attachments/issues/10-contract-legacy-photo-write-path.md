# 10: Contract the legacy one-photo write path

**What to build:** Remove obsolete singular-photo writing and orchestration after every Item journey uses Item Attachments, while retaining the efficient Item Photo projection.

**Blocked by:** 03: Prepare the existing Item Photo backfill; 06: Add attachments while editing an Item; 07: Designate and delete attachments from the carousel; 08: Surface attachment upload failure and session-only retry; 09: Generate Descriptions from Item Photo attachments.

**Status:** ready-for-agent

- [ ] Legacy singular-photo create, replace, remove, upload, and local-draft APIs no longer act as canonical attachment storage paths.
- [ ] The Item retains only the denormalized Item Photo attachment identity, display reference, and thumbnail reference required by compact browsing, caches, and Description Generation.
- [ ] New clients no longer create legacy flat Storage objects, while migrated references to existing flat objects remain readable and deletable.
- [ ] Persisted controller work and local Item caches either migrate safely or invalidate explicitly rather than decoding obsolete photo state incorrectly.
- [ ] Firestore and Storage rules accept the final attachment schema and reject malformed or cross-Household attachment access without adding old-client coexistence logic.
- [ ] Search continues to ignore attachment records and file contents.
- [ ] Unit, UI, background-work, cache, Firestore-emulator, and Storage-emulator regression suites pass across creation, editing, browsing, designation, deletion, migration, failure, and Description Generation.
- [ ] The release checklist requires completion of the metadata backfill and a coordinated Member upgrade before enabling the contracted client.
