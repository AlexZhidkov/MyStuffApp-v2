# 01 — Give each Item photo an immutable revision

**What to build:** A Member who adds or replaces an Item photo gets a new immutable photo revision whose full image and thumbnail upload independently and remain distinguishable from every previous revision.

**Blocked by:** None — can start immediately.

**Status:** implemented

- [x] Adding an Item with a photo assigns one random UUID revision shared by its full image and thumbnail locations.
- [x] Replacing an Item photo assigns a new revision, and connected Household Members observe locations that cannot resolve to the previous image data.
- [x] The Item document references the exact versioned locations targeted by the independent background uploads and retries.
- [x] Existing Items with unversioned photo locations continue to load without migration.
- [x] Replacing a photo does not queue deletion of superseded cloud objects; those objects remain available for manual cleanup.
- [x] Explicitly removing a photo clears its Item references and queues deletion of the current full image and thumbnail, including for legacy locations.
- [x] Firebase Storage authorization accepts the versioned WebP variants while preserving the existing Household membership, MIME-type, and upload-size restrictions.
- [x] Automated checks cover creation, replacement, independent upload and retry, legacy compatibility, explicit removal, and Storage authorization.
