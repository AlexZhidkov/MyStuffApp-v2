# 06: Search synced Inventory by literal text

**What to build:** Let an authorized MCP client Search synced Item names, Tags, and Descriptions with Android-equivalent literal matching and ordering. Return complete paginated results with the agreed content fields, explicitly identifying literal-only fallback while conceptual Search is unavailable.

**Blocked by:** 01: Connect Gemini CLI and read Item details.

**Status:** implemented

- [x] Literal Search follows Android's query normalization, case- and diacritic-insensitive matching, blank-query behavior, and field/match ordering. Nonblank queries that are ineligible for conceptual Search still receive their literal results.
- [x] Names, Tags, and Descriptions participate in matching. Household root, Item Paths, web URLs, Photos, and other Item Attachments are not Search inputs.
- [x] Each result contains stable Item ID, complete Item Path, name, and Description only. Tags remain searchable without appearing in returned content, and duplicate names remain distinguishable by ID.
- [x] Literal-only fallback is explicitly marked in the MCP response. Conceptual unavailability leaves the broader literal result set available; authorization failure or unavailable Inventory is an error rather than a successful empty result or conceptual-only failure.
- [x] Search returns at most 20 Items per page and an explicit continuation marker when more remain. A fixed Inventory with more than 20 matching Items can be read completely in the agreed order without silent truncation or duplicate results.
- [x] Every page validates current App Connection authorization and the approved Household. Altered requests, foreign-Household identifiers, and continuation markers reused with a different Search context cannot cross the Household boundary or bypass the page limit.
- [x] Automated parity fixtures compare representative literal results with Android, including normalization, short queries, ordering, excluded inputs, duplicate names, pagination, and errors. Demonstrate paginated literal Search through Gemini CLI.
- [x] Existing Android Search behavior and operational limits remain unchanged; this MCP capability adds no application rate limits, per-Member daily quotas, or application spending caps.

## Comments

### 18 October 2026 — literal Search implemented; Android fixture consumer pending

Added `search_inventory` with Android-equivalent literal normalization and
field/match ordering. Responses contain complete Item Paths, names,
Descriptions, stable IDs, `literalOnlyFallback: true`, and `nextCursor`.
Pages contain at most 20 Items. Server-held markers bind the normalized query,
App Connection, and approved Household. Every page rechecks current
authorization and validates the full Inventory in one Firestore transaction;
unavailable Inventory and authorization failures never become empty fallback.

The user confirmed the authenticated MCP HTTP, Firestore, and installed Gemini
CLI test seams and starting review commit
`dd8c0e5d8003d9a70ca23d41d4b7412e5aadb98a`. Shared TSV fixtures cover
normalization, short queries, literal ordering, excluded inputs, duplicate
names, and complete pagination through authenticated HTTP. The installed active
Gemini CLI 0.64.0 discovered Search and its transport read all 41 synced fixture
matches in pages of 20, 20, and 1. Query/connection/Household isolation,
unavailable Inventory, and access loss also passed with real Firestore
transactions. This used synthetic emulator Inventory and isolated temporary
credentials, without deployment or live Member data.

All 130 backend tests passed on Node 22 with all emulators and installed CLI
checks enabled, with no skips. All 50 rules tests and the existing 210 Android
unit tests passed. Kotlin compilation and changed-JavaScript syntax checks
passed; no JavaScript type-checking task is configured.

Standards review found zero actionable findings. Spec review found no
implementation defects and one known acceptance gap: the shared fixtures still
need a test consumer at Android's existing `InventoryUiState.searchResults`
seam. The TDD skill requires confirmation before writing a test at an additional
seam; that confirmation has been requested and remains pending. Keep this issue
open until the Android consumer runs successfully. Reproduction details are in
[`docs/mcp-server.md`](../../../docs/mcp-server.md).

### 18 October 2026 — Android parity confirmed; issue completed

The user confirmed the additional Android `InventoryUiState.searchResults`
test seam. `LiteralSearchParityTest` now runs all 21 shared TSV cases against
Android's existing Search implementation. Both Android and authenticated MCP
HTTP pass the same expected results, including the complete 21-result paginated
case. Android application code and Search behavior remain unchanged.

The full Android suite now passes all 231 tests, with no failures or skips;
Kotlin compilation and the repeated MCP HTTP fixture check also pass. The full
backend suite's 130 tests, 50 rules tests, and installed Gemini CLI demonstration
remain valid: this follow-up adds only the Android test and completion records.

The final independent Standards and Spec reviews both report zero findings;
the original Android parity coverage gap is closed.
