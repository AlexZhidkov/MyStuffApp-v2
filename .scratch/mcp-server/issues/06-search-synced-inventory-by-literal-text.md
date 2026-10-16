# 06: Search synced Inventory by literal text

**What to build:** Let an authorized MCP client Search synced Item names, Tags, and Descriptions with Android-equivalent literal matching and ordering. Return complete paginated results with the agreed content fields, explicitly identifying literal-only fallback while conceptual Search is unavailable.

**Blocked by:** 01: Connect Gemini CLI and read Item details.

**Status:** ready-for-agent

- [ ] Literal Search follows Android's query normalization, case- and diacritic-insensitive matching, blank-query behavior, and field/match ordering. Nonblank queries that are ineligible for conceptual Search still receive their literal results.
- [ ] Names, Tags, and Descriptions participate in matching. Household root, Item Paths, web URLs, Photos, and other Item Attachments are not Search inputs.
- [ ] Each result contains stable Item ID, complete Item Path, name, and Description only. Tags remain searchable without appearing in returned content, and duplicate names remain distinguishable by ID.
- [ ] Literal-only fallback is explicitly marked in the MCP response. Conceptual unavailability leaves the broader literal result set available; authorization failure or unavailable Inventory is an error rather than a successful empty result or conceptual-only failure.
- [ ] Search returns at most 20 Items per page and an explicit continuation marker when more remain. A fixed Inventory with more than 20 matching Items can be read completely in the agreed order without silent truncation or duplicate results.
- [ ] Every page validates current App Connection authorization and the approved Household. Altered requests, foreign-Household identifiers, and continuation markers reused with a different Search context cannot cross the Household boundary or bypass the page limit.
- [ ] Automated parity fixtures compare representative literal results with Android, including normalization, short queries, ordering, excluded inputs, duplicate names, pagination, and errors. Demonstrate paginated literal Search through Gemini CLI.
- [ ] Existing Android Search behavior and operational limits remain unchanged; this MCP capability adds no application rate limits, per-Member daily quotas, or application spending caps.
