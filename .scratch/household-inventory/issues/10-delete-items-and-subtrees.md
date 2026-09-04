# 10 — Members permanently delete childless Items

**What to build:** A Member can permanently delete a childless Item after confirmation, while Items with Child Items cannot be deleted.

**Blocked by:** 06 — Members add optional Item photos camera-first; 08 — Members find Items through Household search.

**Status:** implemented

- [x] A Member can permanently delete a childless non-root Item after confirmation.
- [x] Deleting an Item with Child Items is not allowed.
- [x] Deleting an Item also permanently removes every associated stored photo.
- [x] Deleted Items disappear from browsing and search results.
- [x] The Household root cannot be deleted through Item actions.
- [x] No trash, undo, restore, or Parent Item history is offered.
- [x] Automated checks cover childless deletion, root protection, search removal, and photo cleanup.

## Comments

- Implemented confirmed childless Item deletion across the Android Inventory, authenticated callable, and Firebase persistence boundaries.
- Item Attachment records and stored photos are removed by a durable retrying cleanup job; Search indexing removes deleted Items from conceptual results.
- Direct Item document deletion remains denied to clients, and root/Child Item safeguards are enforced in both the client model and backend transaction.
