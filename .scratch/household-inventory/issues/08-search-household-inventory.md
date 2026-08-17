# 08 — Members find Items through Household search

**What to build:** A Member can search Item names, Tags, and descriptions together and open results with enough path context to locate each Item.

**Blocked by:** 07 — Members edit Item details and Tags.

**Status:** implemented

- [x] One Household search input matches non-root Item names, Tags, and descriptions using case- and diacritic-insensitive substring matching.
- [x] Search does not apply fuzzy or typo-tolerant matching.
- [x] Results prioritize matching fields in the order name, Tag, then description.
- [x] Within a field priority, exact matches rank before prefix matches and prefix matches rank before other substring matches.
- [x] Each result shows the Item name, its photo thumbnail when available, and its Item Path.
- [x] Compact results may collapse middle path segments, but the complete Item Path remains available on demand.
- [x] The Household root never appears in search results.
- [x] Opening a result shows that Item's details and immediate Child Items.
- [x] From Search, **Add item** defaults to the Parent Item of the currently opened result, or to the Household root when no result is open.
- [x] Automated checks cover normalization, field priority, match ranking, root exclusion, and path presentation.
