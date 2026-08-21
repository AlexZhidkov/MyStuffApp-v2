# 01 — Generate a Description from an existing Item Photo

**What to build:** A Member editing an Item with an existing Item Photo can save the visible draft and request Description Generation without waiting. The app immediately closes Edit, presents the captured Item optimistically, persists the complete draft through device-owned background work, sends the stored full photo and captured Description to Gemini, and replaces the Description with a valid generated result.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Edit for an existing non-root Item retains primary **Save** and presents secondary outlined **Save & generate description** beneath it.
- [ ] The generation action is absent from Item creation and is disabled when the current edit draft has no Item Photo.
- [ ] Ordinary **Save** retains its existing wait-for-confirmation, success, and failure behavior.
- [ ] **Save & generate description** applies the existing Item validation and normalization policies before any work is submitted; an invalid draft remains open with its field error.
- [ ] A valid action captures every editable Item field, the existing immutable full-photo location, the current Description, requesting Member attribution, and current device language in one background-work request.
- [ ] Edit closes immediately after the request is handed to WorkManager, without waiting for enqueue completion, Firestore, photo loading, or Gemini.
- [ ] The submitted Item appears optimistically in the current Inventory state with no success message or progress indicator.
- [ ] One deep background-work module hides WorkManager, Firestore, photo loading, Firebase AI Logic, prompt construction, response validation, and result persistence behind a small interface used by the Inventory controller.
- [ ] Work requires network connectivity and returns retry with exponential backoff for retryable connectivity, throttling, or remote-service failures.
- [ ] The background workflow unconditionally saves the complete captured draft before generation, allowing it to overwrite newer edits to any captured field.
- [ ] The worker loads the captured stored full-size Item Photo, decodes it on the device, and sends it inline with the captured Description through Firebase AI Logic's Gemini Developer API backend on the free tier.
- [ ] The bundled model for this slice is `gemini-3.7-flash`; remotely selecting the model is deferred to ticket 04.
- [ ] Gemini is instructed to preserve Member-written facts, add only clearly visible identifying details, avoid unsupported brand or model claims, and produce one concise plain-text paragraph without Markdown or headings.
- [ ] Gemini uses the existing Description's language when detectable and otherwise the device language captured with the request.
- [ ] A response is accepted only after trimming when it is nonblank and no longer than 2,000 Unicode code points; invalid output leaves the saved Member-written Description unchanged.
- [ ] A valid result patches only Description, update timestamp, and requesting Member attribution, and replaces any Description written after the request was submitted.
- [ ] The workflow adds no per-Item uniqueness, cancellation, deduplication, request ID, or stale-result protection; overlapping successful work remains last-write-wins.
- [ ] The workflow exposes distinct permanent Save and Description Generation failure outcomes for later Member-visible delivery without posting a system notification.
- [ ] Controller tests exercise the complete action and optimistic state through the existing controller interface with an in-memory background-work adapter.
- [ ] Platform-neutral workflow tests exercise Save ordering, captured Gemini input, retry classification, output validation, Description overwrite, and attribution through the module interface rather than Worker internals.
- [ ] Firebase emulator tests confirm that the full Item Save and Description-only generated patch are authorized for a Household Member and denied across Households.

