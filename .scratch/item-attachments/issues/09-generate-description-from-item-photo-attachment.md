# 09: Generate Descriptions from Item Photo attachments

**What to build:** Preserve Description Generation after the Item Photo becomes an attachment, including when its display image is still uploading.

**Blocked by:** 07: Designate and delete attachments from the carousel; 08: Surface attachment upload failure and session-only retry.

**Status:** implemented

- [x] Description Generation submits only the designated Item Photo's display image and never submits other Item Attachments.
- [x] A first attachment that automatically became the Item Photo is eligible even when it depicts a receipt or instructions.
- [x] When the selected Item Photo upload is pending, the generation workflow queues behind that upload instead of requiring a separate remote-readiness model.
- [x] Exactly one workflow owns or depends upon the display-image upload so concurrent workers cannot race to upload and clean the same local source.
- [x] Item Photo thumbnail generation proceeds independently and does not gate Description Generation.
- [x] Existing Description replacement, attribution, optimistic Item state, and stage-specific failure behaviour remain unchanged.
- [x] Tests cover existing stored Item Photos, newly captured Item Photos, pending uploads, upload failure, and the exclusion of every non-designated attachment.

## Comments

- Description Generation now joins the unique display-upload work for the selected Item Photo's exact Storage location; legacy and already-uploaded locations continue directly.
- Display uploads complete before their thumbnail follow-up, so Description Generation never waits on thumbnail work. The follow-up owns cleanup of both local sources, while terminal attachment failures still leave the existing session-only retry path intact.
- Added regression coverage for exact upload-work identification and display-before-thumbnail ordering.
