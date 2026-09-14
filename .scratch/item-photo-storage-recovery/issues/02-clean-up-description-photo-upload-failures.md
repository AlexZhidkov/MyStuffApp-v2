# 02: Make Description Generation clean up failed Photo uploads

**What to build:** When a Member chooses **Save & generate description** with a replacement Item Photo, use the established Item Attachment failure lifecycle so a failed transfer preserves the saved Item and Member-written Description without publishing a Photo that other Members cannot load.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [x] Description Generation waits for the designated Item Photo's full-size upload and a successful stored-photo read before invoking Gemini.
- [x] If that upload or stored-photo read fails after the Item is saved, the matching Item Photo projection, broken attachment record, and any partially stored variants are removed promptly.
- [x] The saved Item and its Member-written Description remain available after Photo cleanup.
- [x] The originating process retains the failed local Photo draft with the established session-only **Retry** and **Remove** actions; Retry republishes and transfers it, while Remove discards its local source.
- [x] The Member sees the established **Item saved, but couldn't upload its photo.** outcome exactly once, and Gemini is not invoked for the failed attempt.
- [x] Cleanup is idempotent and does not remove a newer replacement Photo or another attachment when delayed or duplicate work completes.
- [x] Description Generation from an already stored Item Photo and ordinary attachment uploads retain their current behavior.
- [x] Regression tests cover metadata-published replacement failures, cleanup dispatch, source retention until stored-photo read succeeds, and delayed completion preserving the retry draft.

## Comments

Implemented the Description Generation photo-failure lifecycle using the existing Item Attachment cleanup handler and session-only failure registry. Replacement work now cleans the conditional Item Photo projection, attachment record, and both Storage variants on full-upload/read failure; retries safely republish only when no newer Item Photo is present.

Verification: focused Android unit tests, `./gradlew lintDebug`, `./gradlew test`, Firebase rules tests, and emulator-backed Functions tests passed.
