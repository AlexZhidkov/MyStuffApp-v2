# 05 — Members build and browse a text-only Inventory

**What to build:** A Member can add named Child Items, build a deep tree, browse immediate children, and understand each Item's place through its Item Path.

**Blocked by:** 02 — Member creates and reopens a Household.

**Status:** implemented

- [x] Home shows the Household name and the immediate Child Items of its root.
- [x] From Home, **Add item** uses the Household root as the Parent Item.
- [x] From an Item detail screen, **Add item** uses the displayed Item as the Parent Item.
- [x] The add Item form shows the Parent Item's complete Item Path without asking the Member to select or confirm it.
- [x] Every non-root Item has an immutable identity, a required current Parent Item, and a name trimmed to 1–100 Unicode characters.
- [x] Duplicate Item names are allowed, including beneath the same Parent Item.
- [x] A Member can browse from the Household root through arbitrarily deep Child Items without a product-defined depth or ordering guarantee.
- [x] Item details distinguish the selected Item from its immediate Child Items and provide the complete Item Path on demand.
- [x] The root cannot be created as a Child Item, moved, or deleted through Item actions.
- [x] Automated checks cover name validation, duplicate names, parent derivation, tree connectivity, root invariants, and deep Item Paths.

## Comments

### 2026-10-17 — Acceptance follow-up

The creation and browsing flows were already implemented. Item details retained a
compact ancestor breadcrumb but offered no way to view the complete Item Path.
Added **View Item Path** to show the Household root, every ancestor, and the
selected Item in a scrollable dialog, including when opened from Search.

Search creation continues to use the opened result's Parent Item, as explicitly
required by EDT-07 in the feature spec. Home and ordinary Item details retain
their existing Parent Item derivation.

Validation: Kotlin compilation, the focused Inventory and InventoryController
tests, all 210 Android unit tests, and all 50 Firestore/Storage rules tests pass.
The Functions emulator suite also passes: 74 passed, with the optional installed
Gemini CLI transport check skipped because `GEMINI_CLI_BUNDLE` is unset.
