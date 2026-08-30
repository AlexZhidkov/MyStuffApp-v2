# 07: Designate and delete attachments from the carousel

**What to build:** Let a Member choose which image represents an Item and permanently remove attachments from the full-screen carousel.

**Blocked by:** 04: Open Item Attachments in a full-screen carousel.

**Status:** ready-for-agent

- [x] A carousel action designates the current attachment as the Item Photo and updates the Item Photo projection immediately.
- [x] Designation generates and uploads the attachment's thumbnail in background work; the display image remains usable locally while that work is pending.
- [x] Only the currently designated Item Photo retains a stored thumbnail, and changing the designation cleans up the previous thumbnail without deleting its display image.
- [x] Deleting an attachment requires a simple confirmation that states deletion is permanent, then removes its record, stored data, and cached data.
- [x] Deleting the Item Photo automatically promotes the oldest remaining attachment and begins generating its thumbnail; deleting the last attachment clears the projection.
- [ ] Deleting an Item, subtree, or Household also removes every owned attachment record and stored attachment file.
- [x] Concurrent additions or designations receive no special conflict resolution beyond the application's existing last-write behaviour.

## Comments

- Added carousel designation and permanent deletion actions with immediate local state updates, oldest-remaining promotion, and Item Photo projection persistence.
- Added retryable background thumbnail generation from an attachment display image, cleanup of superseded thumbnails, stored-file deletion, and display-cache invalidation.
- Added controller, gateway, transfer-task, and cache regression coverage.
- The Item/subtree/Household cleanup checkbox remains pending because this repository has no Item or Household deletion API or UI; the related implementation is tracked separately in `.scratch/household-inventory/issues/10-delete-items-and-subtrees.md`.
