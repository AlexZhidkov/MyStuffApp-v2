# 07: Add conceptual Search with Android-equivalent merging

**What to build:** Extend MCP Search so eligible queries combine precise literal matches with conceptual matches using the existing Android Search contract. Preserve broader literal results with an explicit fallback indicator when conceptual retrieval fails, without changing Android's behavior or conceptual candidate limit.

**Blocked by:** 06: Search synced Inventory by literal text.

**Status:** ready-for-agent

- [ ] Conceptual eligibility uses the existing normalized-query requirement of at least three Unicode letters or digits. Ineligible queries retain literal behavior without making conceptual-provider requests.
- [ ] After conceptual success, return precise literal matches first in this order: exact Item name, Item-name prefix, exact Tag. Append conceptual results in existing retrieval order, deduplicating by stable Item ID and excluding the Household root and absent Items.
- [ ] Retain the existing maximum of ten conceptual candidates for the entire Search result set. Requesting subsequent pages neither adds further conceptual batches nor increases that limit; precise literal matches can still make the combined result exceed 20 Items.
- [ ] Broad literal substrings and Description-only matches are retained during fallback but are not automatically appended after conceptual success unless included by conceptual retrieval, matching Android's existing merge contract.
- [ ] Provider or conceptual-index unavailability returns authorized broader literal results with the explicit literal-only fallback indicator. Authorization failures and unavailable Inventory data remain errors even if conceptual retrieval also fails.
- [ ] Combined and fallback results retain the agreed content fields, complete Item Paths, 20-Item page maximum, explicit continuation, and approved-Household authorization on every page. Conceptual candidates cannot leak content from another Household.
- [ ] Automated fixtures compare Android and MCP eligibility, precise ranking, conceptual ordering, deduplication, missing candidates, empty conceptual success, and fallback. Cover combined results and fallback results spanning multiple pages with deterministic provider fakes.
- [ ] A live smoke check demonstrates a representative conceptual match such as watch finding Clock, and a controlled provider failure demonstrates authorized literal fallback. Existing Android Search behavior and operational limits remain unchanged.
