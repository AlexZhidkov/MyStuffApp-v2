# 02 — Generate a Description from a replacement Item Photo

**What to build:** A Member can choose a replacement Item Photo, edit any Item fields, and use **Save & generate description** immediately. The captured replacement receives an immutable stored revision, its full-size photo uploads before Gemini runs, and its thumbnail continues uploading independently.

**Blocked by:** 01 — Generate a Description from an existing Item Photo.

**Status:** ready-for-agent

- [ ] **Save & generate description** is enabled when the edit draft contains an unsaved replacement Item Photo and disabled again if that photo is removed.
- [ ] The background request captures the visible replacement photo and unsaved Description together with every other editable draft field.
- [ ] A single immutable photo revision is allocated before optimistic presentation and supplies the full and thumbnail locations used by the captured Item.
- [ ] The background workflow unconditionally saves the complete captured Item with the new revision locations before transferring photo bytes.
- [ ] The full-size replacement upload must succeed before the stored full photo is loaded and sent inline to Gemini.
- [ ] The thumbnail upload proceeds independently and neither blocks nor is blocked by Description Generation.
- [ ] Successful full upload cleans up its local source without racing Description Generation, because generation reads the stored revision rather than the upload source.
- [ ] Retryable full-photo upload and load failures use WorkManager retry with exponential backoff.
- [ ] A non-retryable full-photo upload or load failure stops generation, preserves the saved Member-written Description, and exposes the permanent photo-failure outcome required by ticket 03.
- [ ] A thumbnail failure follows the existing independent photo-transfer behavior and does not become a Description Generation failure.
- [ ] Existing-photo Description Generation from ticket 01 continues to bypass replacement upload while preserving identical prompt, validation, overwrite, and attribution behavior.
- [ ] Platform-neutral workflow tests verify full-save-before-upload, full-upload-before-generation, stored inline photo input, independent thumbnail outcomes, source cleanup, retries, and permanent photo failure.
- [ ] Controller tests verify replacement-photo eligibility, immediate close, and optimistic presentation of the new immutable photo revision.
- [ ] Storage and Firestore emulator tests authorize the new immutable revision and captured Item update for a Household Member while denying cross-Household access.

