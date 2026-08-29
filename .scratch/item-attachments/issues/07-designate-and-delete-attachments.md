# 07: Designate and delete attachments from the carousel

**What to build:** Let a Member choose which image represents an Item and permanently remove attachments from the full-screen carousel.

**Blocked by:** 04: Open Item Attachments in a full-screen carousel.

**Status:** ready-for-agent

- [ ] A carousel action designates the current attachment as the Item Photo and updates the Item Photo projection immediately.
- [ ] Designation generates and uploads the attachment's thumbnail in background work; the display image remains usable locally while that work is pending.
- [ ] Only the currently designated Item Photo retains a stored thumbnail, and changing the designation cleans up the previous thumbnail without deleting its display image.
- [ ] Deleting an attachment requires a simple confirmation that states deletion is permanent, then removes its record, stored data, and cached data.
- [ ] Deleting the Item Photo automatically promotes the oldest remaining attachment and begins generating its thumbnail; deleting the last attachment clears the projection.
- [ ] Deleting an Item, subtree, or Household also removes every owned attachment record and stored attachment file.
- [ ] Concurrent additions or designations receive no special conflict resolution beyond the application's existing last-write behaviour.
