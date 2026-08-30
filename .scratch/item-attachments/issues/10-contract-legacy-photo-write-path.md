# 10: Contract the legacy one-photo write path

**What to build:** Remove obsolete singular-photo writing and orchestration after every Item journey uses Item Attachments, while retaining the efficient Item Photo projection.

**Blocked by:** 03: Prepare the existing Item Photo backfill; 06: Add attachments while editing an Item; 07: Designate and delete attachments from the carousel; 08: Surface attachment upload failure and session-only retry; 09: Generate Descriptions from Item Photo attachments.

**Status:** implemented

- [x] Legacy singular-photo create, replace, remove, upload, and local-draft APIs no longer act as canonical attachment storage paths.
- [x] The Item retains only the denormalized Item Photo attachment identity, display reference, and thumbnail reference required by compact browsing, caches, and Description Generation.
- [x] New clients no longer create legacy flat Storage objects, while migrated references to existing flat objects remain readable and deletable.
- [x] Persisted controller work and local Item caches either migrate safely or invalidate explicitly rather than decoding obsolete photo state incorrectly.
- [x] Firestore and Storage rules accept the final attachment schema and reject malformed or cross-Household attachment access without adding old-client coexistence logic.
- [x] Search continues to ignore attachment records and file contents.
- [x] Unit, UI, background-work, cache, Firestore-emulator, and Storage-emulator regression suites pass across creation, editing, browsing, designation, deletion, migration, failure, and Description Generation.
- [x] The release checklist requires completion of the metadata backfill and a coordinated Member upgrade before enabling the contracted client.

## Comments

- Contracted `InventoryGateway` and Firebase write orchestration to create, update, and remove Item Attachments; new photo uploads use nested immutable attachment paths only.
- Kept the Item Photo projection as the compact browsing/Description Generation read model, with explicit cache and persisted-work invalidation for obsolete formats.
- Restricted new Firestore/Storage writes to the attachment schema while preserving read/delete access for migrated flat Storage references.
- Added controller, gateway, background-work, cache, Firestore-emulator, and Storage-emulator regression coverage and documented the backfill/Member-upgrade release gate.
