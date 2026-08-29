# 01: Expand persistence with Item Attachments

**What to build:** Add the attachment domain and persistence seam beside the existing Item Photo path so later journeys can move onto Item Attachments without breaking the current application.

**Blocked by:** None (can start immediately).

**Status:** implemented

- [x] A non-root Item can own any number of immutable Item Attachment records with stable identities, creation order, content type, and one display-file location; attachment records have no caption, purpose, or page grouping.
- [x] Item Attachment records live beneath their owning Item, while new attachment files use nested Household, Item, and attachment Storage locations.
- [x] Household authorization permits every Member to read and change attachments belonging to their Household and rejects cross-Household access.
- [x] The Household cannot own attachments, and attachment records cannot become Child Items or Search records.
- [x] The seam supports optimized WebP images initially without making the persistent attachment identity image-specific, preserving a path to future PDF support.
- [x] Existing Item Photo creation, editing, browsing, caching, and Description Generation remain unchanged and covered by regression tests.

## Comments

- Added the Item Attachment domain model, ordered Firebase persistence seam, immutable create/delete operations, nested WebP Storage path helper, and Firestore/Storage authorization coverage.
- Existing Item Photo behavior remains on its legacy path; backing it with attachments is covered by issue 02.
