# 07 — Members edit Item details and Tags

**What to build:** A Member can complete and revise a non-root Item's name, photo, description, and Tags with consistent validation and save feedback.

**Blocked by:** 06 — Members add optional Item photos camera-first.

**Status:** ready-for-agent

- [ ] The Item form supports a required name, one optional photo, an optional description, and optional Tags, with no Category or quantity.
- [ ] Descriptions are limited to 2,000 characters.
- [ ] An Item accepts at most 20 Tags, each trimmed to 1–40 Unicode characters.
- [ ] Tags compare case- and diacritic-insensitively, preserve entered capitalization for display, and cannot be duplicated after normalization.
- [ ] Existing Household Tags can be suggested without preventing a Member from creating a new Tag.
- [ ] A Member can edit every supported field on a non-root Item, including replacing or removing its photo.
- [ ] Replacing or removing a photo also removes the superseded stored photo.
- [ ] Item creation and updates record timestamps and display-name snapshots for the creating and last-updating Members.
- [ ] The form communicates save-in-progress, success, and failure states and allows a failed save to be retried while the form remains open.
- [ ] Automated checks cover field limits, Tag normalization and suggestions, attribution metadata, photo replacement, and failed-save retry.
