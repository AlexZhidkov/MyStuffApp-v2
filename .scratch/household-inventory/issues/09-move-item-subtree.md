# 09 — Members move an Item subtree

**What to build:** A Member can choose a valid new Parent Item and move an Item with all its descendants without breaking the Inventory tree.

**Blocked by:** 05 — Members build and browse a text-only Inventory.

**Status:** ready-for-agent

- [ ] A Member can start **Move** from any non-root Item and select another Item in the same Household as its new Parent Item.
- [ ] The Household root is not movable.
- [ ] The moved Item cannot select itself or any of its descendants as its Parent Item.
- [ ] A move changes only the moved Item's Parent Item and preserves its identity, fields, and complete descendant subtree.
- [ ] The Item Paths of the moved Item and all descendants reflect the new ancestry.
- [ ] A move is rejected if the Item or selected Parent Item no longer exists or no longer belongs to the same Household.
- [ ] Firebase authorization permits current Members to move Items and rejects non-Members.
- [ ] Automated checks cover successful subtree movement, root protection, cycle prevention, missing Items, and cross-Household rejection.
