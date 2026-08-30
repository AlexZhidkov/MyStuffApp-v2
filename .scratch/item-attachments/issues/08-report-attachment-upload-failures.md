# 08: Surface attachment upload failure and session-only retry

**What to build:** Keep Item saving responsive when attachment uploads run in the background, while making each terminal upload failure visible and manually recoverable for the remainder of the originating app process.

**Blocked by:** 06: Add attachments while editing an Item; 07: Designate and delete attachments from the carousel.

**Status:** implemented

- [x] Saving closes promptly and publishes each attachment record and any Item Photo projection before its display-image upload finishes, so other Members may temporarily observe a loading or broken image.
- [x] Attachment uploads run independently, and one failed upload does not block the Item save or successful sibling attachments.
- [x] Upload failures are terminal and are never retried automatically.
- [x] On failure, the originating Member is informed and the broken shared attachment record, projection where applicable, and any partially stored data are removed immediately.
- [x] The originating process retains a device-local failed draft with **Retry** and **Remove**; Retry republishes and uploads the attachment, while Remove discards its local source.
- [x] The failed draft and its manual retry opportunity do not survive process exit or device restart.
- [x] Another Member may remove a pending shared attachment but cannot retry it because that device does not own the local source.

## Comments

- Made each attachment transfer terminal and independent, with grouped full/thumbnail work for the designated Item Photo and no automatic WorkManager backoff retry.
- Added failure metadata and Firebase cleanup for partial Storage data, attachment records, and matching Item Photo projections.
- Added an in-memory process-local failure registry, originating-Member filtering, Retry/Remove controller actions, and visible UI controls.
- Added background-work, gateway, controller, and registry regression coverage.
