# 06: Add attachments while editing an Item

**What to build:** Let a Member add several camera or photo-picker attachments from Edit without disturbing the Item's existing attachments.

**Blocked by:** 05: Create an Item with multiple attachments.

**Status:** ready-for-agent

- [ ] Edit exposes the same repeated camera capture, multi-select photo picker, optional arbitrary crop, and image optimization behaviour as Item creation.
- [ ] Saving appends every new attachment while preserving all existing attachments and their oldest-first order.
- [ ] When the Item has no Item Photo, its first successfully added attachment is projected as the Item Photo; otherwise the existing designation is preserved.
- [ ] Ordinary changes to the Item's name, Description, Tags, web URL, or Parent Item do not rewrite attachment records.
- [ ] Cancelling Edit or abandoning an unsaved selection does not create shared attachment records or leave retained source files.
- [ ] Adding attachments remains confined to Create and Edit; the carousel does not expose an add action.
