# 08 — Members find Items through Household search

**What to build:** A Member can search Item names, Tags, and descriptions together and open results with enough path context to locate each Item.

**Blocked by:** 07 — Members edit Item details and Tags.

**Status:** ready-for-agent

- [ ] One Household search input matches non-root Item names, Tags, and descriptions using case- and diacritic-insensitive substring matching.
- [ ] Search does not apply fuzzy or typo-tolerant matching.
- [ ] Results prioritize matching fields in the order name, Tag, then description.
- [ ] Within a field priority, exact matches rank before prefix matches and prefix matches rank before other substring matches.
- [ ] Each result shows the Item name, its photo thumbnail when available, and its Item Path.
- [ ] Compact results may collapse middle path segments, but the complete Item Path remains available on demand.
- [ ] The Household root never appears in search results.
- [ ] Opening a result shows that Item's details and immediate Child Items.
- [ ] From Search, **Add item** defaults to the Parent Item of the currently opened result, or to the Household root when no result is open.
- [ ] Automated checks cover normalization, field priority, match ranking, root exclusion, and path presentation.
