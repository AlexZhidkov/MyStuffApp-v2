# 13 — Verify the two-Member prototype end to end

**What to build:** A privately distributable prototype whose complete two-Member Household journey is verified through automated correctness checks and a facilitated target-device session.

**Blocked by:** 07 — Members edit Item details and Tags; 08 — Members find Items through Household search; 09 — Members move an Item subtree; 10 — Members permanently delete Items and subtrees; 11 — Household Owner removes a Member; 12 — Members rename and the Owner deletes the Household.

**Status:** ready-for-agent

- [ ] Automated checks pass for Firebase authorization, one-Household membership, invitation acceptance and expiry, tree invariants, Item validation, movement, deletion, photo cleanup, and search ranking.
- [ ] Two connected Members see each other's Household and Inventory changes without manual refresh.
- [ ] Firebase default behavior is used for offline caching, offline writes, and concurrent changes without custom guarantees.
- [ ] The private/internal Android build installs and runs on a Google Pixel 8 Pro running Android 17.
- [ ] A facilitated session builds an Inventory at least three levels deep and adds approximately 20 real Items through the camera-first flow.
- [ ] Participants can browse, interpret a deep Item Path, search for several named Items, edit an Item, and move a subtree.
- [ ] The session demonstrates connected synchronization and both childless and subtree deletion.
- [ ] Save progress, success, failure, and in-place retry behavior are exercised without requiring durable form recovery.
- [ ] Observed confusion, incorrect assumptions, friction, and participant comments are recorded for the next specification revision.
