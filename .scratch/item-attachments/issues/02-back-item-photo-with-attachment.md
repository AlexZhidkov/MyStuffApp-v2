# 02: Back the existing Item Photo with an Item Attachment

**What to build:** Preserve the current one-photo Member experience while making its image an Item Attachment and retaining only a denormalized Item Photo projection on the Item.

**Blocked by:** 01: Expand persistence with Item Attachments.

**Status:** implemented

- [x] Creating an Item with one photo creates one Item Attachment and projects that attachment's identity, display reference, and thumbnail reference onto the Item.
- [x] Viewing Item lists and details continues to use the Item Photo projection without issuing an attachment query per Item.
- [x] Replacing the current Item Photo creates a new immutable attachment-backed image and removes the superseded logical attachment from the Member-visible model.
- [x] Removing the only Item Photo removes its attachment and clears the Item Photo projection.
- [x] New attachment-backed images use the nested Storage layout, while existing legacy Storage references remain readable.
- [x] Current camera-first creation, Edit, thumbnail, background-transfer, and Description Generation behaviour remains externally unchanged and has end-to-end regression coverage.

## Comments

- Added the optional `photoAttachmentId` Item projection and wired direct camera creation, edit replacement/removal, and Description Generation replacement work through immutable Item Attachments.
- New full and thumbnail image transfers use nested `households/{household}/items/{item}/attachments/{attachment}` Storage paths; legacy projected references remain readable and removable.
- Updated cache serialization and Firestore rules, with gateway, background-transfer, cache, and Firebase emulator regression coverage.
