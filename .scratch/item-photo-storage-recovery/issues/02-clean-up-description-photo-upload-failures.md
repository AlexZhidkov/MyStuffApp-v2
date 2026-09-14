# 02: Make Description Generation clean up failed Photo uploads

**What to build:** When a Member chooses **Save & generate description** with a replacement Item Photo, use the established Item Attachment failure lifecycle so a failed transfer preserves the saved Item and Member-written Description without publishing a Photo that other Members cannot load.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] Description Generation waits for the designated Item Photo's full-size upload and a successful stored-photo read before invoking Gemini.
- [ ] If that upload or stored-photo read fails after the Item is saved, the matching Item Photo projection, broken attachment record, and any partially stored variants are removed promptly.
- [ ] The saved Item and its Member-written Description remain available after Photo cleanup.
- [ ] The originating process retains the failed local Photo draft with the established session-only **Retry** and **Remove** actions; Retry republishes and transfers it, while Remove discards its local source.
- [ ] The Member sees the established **Item saved, but couldn't upload its photo.** outcome exactly once, and Gemini is not invoked for the failed attempt.
- [ ] Cleanup is idempotent and does not remove a newer replacement Photo or another attachment when delayed or duplicate work completes.
- [ ] Description Generation from an already stored Item Photo and ordinary attachment uploads retain their current behavior.
- [ ] Regression tests reproduce an upload denial after metadata publication and prove there is no remotely visible Photo reference to a missing full-size object when the workflow settles.
