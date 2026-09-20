# 01: Make Child Item reordering reliable for Photo-heavy sibling lists

**What to build:** Make Child Item reordering persist reliably when a Parent Item has many children with Item Photos. A Member must be able to drag a Child Item to a new position without receiving `PERMISSION_DENIED`, and the resulting order must remain correct after the Inventory reloads. Keep order-only persistence within Firestore security-rule access limits by writing only changed positions and avoiding validation reads for unchanged Item Photo fields.

**Blocked by:** None (can start immediately).

**Status:** implemented

- [x] Reordering succeeds and survives an Inventory reload when the Parent Item has nine Child Items and eight of them have Item Photos.
- [x] A one-position reorder persists only the Child Items whose positions changed; a longer drag and a newer reorder queued during an in-progress save still persist the latest complete order.
- [x] An order-only update does not read or revalidate unchanged Item Attachment records, while an update that changes the Item Photo projection still validates its Item Attachment.
- [x] Firestore authorization continues to reject non-Members, invalid positions, and unauthorized Item changes.
- [x] Automated regression coverage exercises the Photo-heavy sibling list that previously returned `PERMISSION_DENIED`.
