# 09: Generate Descriptions from Item Photo attachments

**What to build:** Preserve Description Generation after the Item Photo becomes an attachment, including when its display image is still uploading.

**Blocked by:** 07: Designate and delete attachments from the carousel; 08: Surface attachment upload failure and session-only retry.

**Status:** ready-for-agent

- [ ] Description Generation submits only the designated Item Photo's display image and never submits other Item Attachments.
- [ ] A first attachment that automatically became the Item Photo is eligible even when it depicts a receipt or instructions.
- [ ] When the selected Item Photo upload is pending, the generation workflow queues behind that upload instead of requiring a separate remote-readiness model.
- [ ] Exactly one workflow owns or depends upon the display-image upload so concurrent workers cannot race to upload and clean the same local source.
- [ ] Item Photo thumbnail generation proceeds independently and does not gate Description Generation.
- [ ] Existing Description replacement, attribution, optimistic Item state, and stage-specific failure behaviour remain unchanged.
- [ ] Tests cover existing stored Item Photos, newly captured Item Photos, pending uploads, upload failure, and the exclusion of every non-designated attachment.
