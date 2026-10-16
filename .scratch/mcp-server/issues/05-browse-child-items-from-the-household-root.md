# 05: Browse Child Items from the Household root

**What to build:** Let an authorized MCP client begin at the approved Household root and browse direct Child Items in MyStuff's existing shared order. Clients can follow stable Item IDs into descendants and continue listings beyond 20 Items without Search or recursive flattening.

**Blocked by:** 01: Connect Gemini CLI and read Item details.

**Status:** ready-for-agent

- [ ] A client can start listing at the approved Household root without knowing its Item ID or finding it through Search.
- [ ] Each listing returns names and stable IDs of direct Child Items only, in the same shared order as Android, including existing handling of missing or tied order values. Listing a Child Item's children explores the next level rather than flattening descendants.
- [ ] Every page contains at most 20 Items and includes an explicit continuation marker when more remain. A fixed Inventory with more than 20 children can be read completely without silently missing or duplicating Items across pages.
- [ ] Continuation markers preserve the approved Household and listing context. Foreign Item IDs, altered markers, or markers reused for a different Parent Item cannot expose unauthorized data or bypass the page limit.
- [ ] Every page checks current App Connection validity, Household Access, and deletion states. Revocation or access loss between pages stops continuation.
- [ ] Empty Child Item lists succeed; missing Parent Items, authorization failures, and unavailable Inventory produce errors. Responses exclude Tags, web URLs, Photos, and other Item Attachments.
- [ ] Automated tests cover root browsing, nested browsing, duplicate names, shared-order parity, page boundaries, and two-Household isolation. Demonstrate a complete listing beyond 20 Items through Gemini CLI using synced data.
