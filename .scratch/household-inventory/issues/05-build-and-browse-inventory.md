# 05 — Members build and browse a text-only Inventory

**What to build:** A Member can add named Child Items, build a deep tree, browse immediate children, and understand each Item's place through its Item Path.

**Blocked by:** 02 — Member creates and reopens a Household.

**Status:** implemented

- [x] Home shows the Household name and the immediate Child Items of its root.
- [x] From Home, **Add item** defaults the Parent Item to the Household root.
- [x] From an Item detail screen, **Add item** defaults the Parent Item to the displayed Item.
- [x] A Member can change the proposed Parent Item before saving a new Item.
- [x] Every non-root Item has an immutable identity, a required current Parent Item, and a name trimmed to 1–100 Unicode characters.
- [x] Duplicate Item names are allowed, including beneath the same Parent Item.
- [x] A Member can browse from the Household root through arbitrarily deep Child Items without a product-defined depth or ordering guarantee.
- [x] Item details distinguish the selected Item from its immediate Child Items and provide the complete Item Path on demand.
- [x] The root cannot be created as a Child Item, moved, or deleted through Item actions.
- [x] Automated checks cover name validation, duplicate names, parent derivation, tree connectivity, root invariants, and deep Item Paths.
