# 07: Add conceptual Search with Android-equivalent merging

**What to build:** Extend MCP Search so eligible queries combine precise literal matches with conceptual matches using the existing Android Search contract. Preserve broader literal results with an explicit fallback indicator when conceptual retrieval fails, without changing Android's behavior or conceptual candidate limit.

**Blocked by:** 06: Search synced Inventory by literal text.

**Status:** implemented

- [x] Conceptual eligibility uses the existing normalized-query requirement of at least three Unicode letters or digits. Ineligible queries retain literal behavior without making conceptual-provider requests.
- [x] After conceptual success, return precise literal matches first in this order: exact Item name, Item-name prefix, exact Tag. Append conceptual results in existing retrieval order, deduplicating by stable Item ID and excluding the Household root and absent Items.
- [x] Retain the existing maximum of ten conceptual candidates for the entire Search result set. Requesting subsequent pages neither adds further conceptual batches nor increases that limit; precise literal matches can still make the combined result exceed 20 Items.
- [x] Broad literal substrings and Description-only matches are retained during fallback but are not automatically appended after conceptual success unless included by conceptual retrieval, matching Android's existing merge contract.
- [x] Provider or conceptual-index unavailability returns authorized broader literal results with the explicit literal-only fallback indicator. Authorization failures and unavailable Inventory data remain errors even if conceptual retrieval also fails.
- [x] Combined and fallback results retain the agreed content fields, complete Item Paths, 20-Item page maximum, explicit continuation, and approved-Household authorization on every page. Conceptual candidates cannot leak content from another Household.
- [x] Automated fixtures compare Android and MCP eligibility, precise ranking, conceptual ordering, deduplication, missing candidates, empty conceptual success, and fallback. Cover combined results and fallback results spanning multiple pages with deterministic provider fakes.
- [x] A live smoke check demonstrates a representative conceptual match such as watch finding Clock, and a controlled provider failure demonstrates authorized literal fallback. Existing Android Search behavior and operational limits remain unchanged.

## Comments

### 18 October 2026 — implementation and local conceptual smoke

MCP Search now combines Android-equivalent precise literal matches with the
existing Household-scoped conceptual retrieval, sharing the ten-candidate limit.
Continuation markers retain the first batch or fallback state; later pages
recheck current App Connection authorization and the full Inventory without
another provider request. Provider/index failures keep broader literal results
with `literalOnlyFallback: true`; authorization and Inventory errors remain
errors, including failures occurring during inference. MCP binds the existing
Gemini secret; Android application code and operational limits are unchanged.

The user confirmed authenticated MCP HTTP with Firestore and deterministic
provider fakes, Android's `InventoryUiState.searchResults`, and the installed
Gemini CLI as test seams. Review baseline: `936ed0054685f1ac159623604b3a68a49b64f2b9`.
Both Android and MCP pass 16 shared conceptual fixtures. The installed CLI
found Clock for watch through real local Firestore vector retrieval with a
provider fake, read combined pages of 20/20/2, and demonstrated controlled
provider failure with authorized fallback pages of 20/20/1. Household isolation,
continuation isolation, and access-loss rejection passed.

The inherited Search spec explicitly requires authorization for live calls.
Real Gemini verification is prepared as an opt-in smoke using only synthetic
Inventory in a local Firestore emulator; approval has been requested. The
remaining acceptance checkbox stays open until that check is authorized and
passes. No deployment or production Inventory access has occurred.
Reproduction details: [`docs/mcp-server.md`](../../../docs/mcp-server.md).

All 160 backend tests pass with full emulators and installed CLI checks enabled,
with no skips. All 247 Android tests, 50 rules tests, and Kotlin compilation pass.
Changed JavaScript passes syntax checks; no JavaScript typechecking task is configured.

Independent review: Standards reports zero findings after simplifying the shared
ranking pipeline. Spec reports no implementation defects or scope creep and one
acceptance gap: the live Gemini smoke still awaits authorization. The final post-review
HTTP regression run passes all 103 tests with real Firestore and the installed
CLI enabled, with no skips.


### 18 October 2026 — approved live-provider smoke passed; issue completed

The user explicitly approved the synthetic live-provider smoke. The installed
Gemini CLI found Clock for `watch` using real `gemini-embedding-2` embeddings and
real Firestore vector retrieval in the local emulator. Three paid embedding
requests processed only synthetic Clock text and the `watch`/`duplicate` queries.
The existing Gemini credential stayed in memory; no deployment or production
Inventory access occurred.

The same check passed combined pagination of 20/20/2 Items, controlled provider
failure with authorized literal fallback in pages of 20/20/1, Household content
isolation, and rejection after loss of Household Access. The focused live smoke
passed with no failures or skips. This closes the remaining acceptance checkbox
and the initial Spec review's only gap. The earlier 160 backend, 247 Android,
50 rules, and final 103-test MCP HTTP results remain valid; this follow-up changes
only completion records.

Final independent reviews report zero Standards findings and zero Spec findings;
the original live-provider acceptance gap is closed.
