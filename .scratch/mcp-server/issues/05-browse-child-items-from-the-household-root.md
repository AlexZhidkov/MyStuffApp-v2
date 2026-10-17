# 05: Browse Child Items from the Household root

**What to build:** Let an authorized MCP client begin at the approved Household root and browse direct Child Items in MyStuff's existing shared order. Clients can follow stable Item IDs into descendants and continue listings beyond 20 Items without Search or recursive flattening.

**Blocked by:** 01: Connect Gemini CLI and read Item details.

**Status:** implemented

- [x] A client can start listing at the approved Household root without knowing its Item ID or finding it through Search.
- [x] Each listing returns names and stable IDs of direct Child Items only, in the same shared order as Android, including existing handling of missing or tied order values. Listing a Child Item's children explores the next level rather than flattening descendants.
- [x] Every page contains at most 20 Items and includes an explicit continuation marker when more remain. A fixed Inventory with more than 20 children can be read completely without silently missing or duplicating Items across pages.
- [x] Continuation markers preserve the approved Household and listing context. Foreign Item IDs, altered markers, or markers reused for a different Parent Item cannot expose unauthorized data or bypass the page limit.
- [x] Every page checks current App Connection validity, Household Access, and deletion states. Revocation or access loss between pages stops continuation.
- [x] Empty Child Item lists succeed; missing Parent Items, authorization failures, and unavailable Inventory produce errors. Responses exclude Tags, web URLs, Photos, and other Item Attachments.
- [x] Automated tests cover root browsing, nested browsing, duplicate names, shared-order parity, page boundaries, and two-Household isolation. Demonstrate a complete listing beyond 20 Items through Gemini CLI using synced data.

## Comments

### 17 October 2026 — implemented and verified locally

Added `list_children`: omit `parentItemId` to start at the approved Household
root, or supply a stable Child Item ID to explore one level deeper. Responses
contain only Parent Item ID, Child Item names/IDs, and `nextCursor`; empty lists
succeed. Ordering follows Android's ID-ordered snapshot, missing/null-position
fallback to sibling index, and original-index tie breaking.

Pages contain at most 20 Items. Opaque continuation markers bind server-held
offsets to the App Connection, Household, and Parent Item. Altered markers,
foreign IDs, other connections, and different Parents fail. Each page checks
live connection/membership/deletion state in the same transaction as its
Inventory read. A fixed Inventory lists completely; concurrent changes require
restarting the listing.

The user confirmed the authenticated MCP HTTP and Firestore/Gemini CLI test
seams and starting review commit `24dca877760b7bd0ae4df20a90298b7445fd5daa`.
All 45 targeted HTTP tests passed with Firestore transactions and the installed
Gemini CLI enabled. Coverage includes root/nested/empty lists, duplicate names,
shared order, exact page boundaries, two-Household marker isolation, revocation,
access loss, deletion states, and unavailable/corrupt Inventory.

The actual installed Gemini CLI executable discovered the local server, and
its OAuth provider and HTTP transport read all 41 root children from synced
Firestore-emulator data in pages of 20, 20, and 1. Nested browsing and rejection
after Household Access removal also passed. This used synthetic Inventory,
isolated CLI settings, and temporary credentials; no production deployment or
live Member Inventory was used. Details and reproduction steps are in
[`docs/mcp-server.md`](../../../docs/mcp-server.md).

Final validation: all 102 backend tests passed with Auth, Firestore, Storage,
Functions, and installed Gemini CLI checks enabled, with no skips. All 50
Firestore/Storage rules tests passed. `./gradlew test` confirmed Kotlin compilation
and all 210 Android unit tests remain passing (up-to-date). Changed JavaScript
files passed syntax checks; no JavaScript type-checking task is configured.
Independent Standards and Spec reviews found zero actionable findings on both
axes against the confirmed starting commit.
