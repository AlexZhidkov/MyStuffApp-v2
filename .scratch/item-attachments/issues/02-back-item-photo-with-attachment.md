# 02: Back the existing Item Photo with an Item Attachment

**What to build:** Preserve the current one-photo Member experience while making its image an Item Attachment and retaining only a denormalized Item Photo projection on the Item.

**Blocked by:** 01: Expand persistence with Item Attachments.

**Status:** ready-for-agent

- [ ] Creating an Item with one photo creates one Item Attachment and projects that attachment's identity, display reference, and thumbnail reference onto the Item.
- [ ] Viewing Item lists and details continues to use the Item Photo projection without issuing an attachment query per Item.
- [ ] Replacing the current Item Photo creates a new immutable attachment-backed image and removes the superseded logical attachment from the Member-visible model.
- [ ] Removing the only Item Photo removes its attachment and clears the Item Photo projection.
- [ ] New attachment-backed images use the nested Storage layout, while existing legacy Storage references remain readable.
- [ ] Current camera-first creation, Edit, thumbnail, background-transfer, and Description Generation behaviour remains externally unchanged and has end-to-end regression coverage.
