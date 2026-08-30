# 06: Add attachments while editing an Item

**What to build:** Let a Member add several camera or photo-picker attachments from Edit without disturbing the Item's existing attachments.

**Blocked by:** 05: Create an Item with multiple attachments.

**Status:** implemented

- [x] Edit exposes the same repeated camera capture, multi-select photo picker, optional arbitrary crop, and image optimization behaviour as Item creation.
- [x] Saving appends every new attachment while preserving all existing attachments and their oldest-first order.
- [x] When the Item has no Item Photo, its first successfully added attachment is projected as the Item Photo; otherwise the existing designation is preserved.
- [x] Ordinary changes to the Item's name, Description, Tags, web URL, or Parent Item do not rewrite attachment records.
- [x] Cancelling Edit or abandoning an unsaved selection does not create shared attachment records or leave retained source files.
- [x] Adding attachments remains confined to Create and Edit; the carousel does not expose an add action.

## Comments

- Added a separate Edit attachment-addition flow that reuses camera capture, multi-selection, arbitrary-ratio cropping, and attachment-specific image optimization.
- Edit saves append immutable attachment records, continue known creation-order sequences, preserve an existing Item Photo, or project the first new attachment when no Item Photo exists.
- Cancellation, camera back, retake, skip, and replacement transitions clean up app-owned temporary photo files without deleting picker-owned sources.
