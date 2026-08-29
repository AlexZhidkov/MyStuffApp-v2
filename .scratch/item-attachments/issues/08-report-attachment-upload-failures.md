# 08: Surface attachment upload failure and session-only retry

**What to build:** Keep Item saving responsive when attachment uploads run in the background, while making each terminal upload failure visible and manually recoverable for the remainder of the originating app process.

**Blocked by:** 06: Add attachments while editing an Item; 07: Designate and delete attachments from the carousel.

**Status:** ready-for-agent

- [ ] Saving closes promptly and publishes each attachment record and any Item Photo projection before its display-image upload finishes, so other Members may temporarily observe a loading or broken image.
- [ ] Attachment uploads run independently, and one failed upload does not block the Item save or successful sibling attachments.
- [ ] Upload failures are terminal and are never retried automatically.
- [ ] On failure, the originating Member is informed and the broken shared attachment record, projection where applicable, and any partially stored data are removed immediately.
- [ ] The originating process retains a device-local failed draft with **Retry** and **Remove**; Retry republishes and uploads the attachment, while Remove discards its local source.
- [ ] The failed draft and its manual retry opportunity do not survive process exit or device restart.
- [ ] Another Member may remove a pending shared attachment but cannot retry it because that device does not own the local source.
