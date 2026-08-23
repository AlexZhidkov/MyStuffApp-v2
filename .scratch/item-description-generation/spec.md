# Item Description Generation

Status: ready-for-agent

## Problem Statement

Members may have an Item Photo but lack the time, confidence, or vocabulary to write a useful Description while cataloguing household belongings. Writing a clear Description manually is especially tedious when the visible characteristics in the photo already contain much of the useful identifying information.

The current Item edit flow can save a Member-written Description but cannot ask an LLM to enrich it. A Member who wants generated text must leave the app, describe or upload the photo elsewhere, copy the result back, and save the Item manually.

## Solution

Add a secondary **Save & generate description** action to the edit form for a non-root Item that has an Item Photo. The action validates the visible draft, captures every field plus the current photo and Description, enqueues durable device-owned background work, closes the edit form immediately, and optimistically shows the submitted Item.

The background workflow saves the complete captured draft, ensures a replacement full-size photo has uploaded, and sends that photo inline with the captured Description to Gemini through Firebase AI Logic. Gemini returns a concise replacement Description that preserves Member-provided facts and adds clearly visible details. A valid response replaces the Item's current Description even if it changed after the request.

The Member does not wait for either remote persistence or Gemini. There is no success or progress message. Failed attempts produce a stage-specific, one-time error message when the app is next active.

## User Stories

1. As a Member, I want to generate a Description from an Item Photo, so that I can record useful identifying details with less writing.
2. As a Member, I want Description Generation to use my existing Description, so that facts I already supplied are retained.
3. As a Member, I want Description Generation to use the photo currently visible in Edit, so that Gemini describes the Item I am looking at.
4. As a Member, I want an unsaved replacement photo to be used, so that I do not have to save once before requesting Description Generation.
5. As a Member, I want an unsaved Description to be used, so that my latest factual input informs Gemini.
6. As a Member, I want **Save & generate description** to save every visible Item field, so that I do not need to press Save separately.
7. As a Member, I want the edit form to close immediately after valid work is queued, so that I can continue using the Inventory without waiting for network calls.
8. As a Member, I want the submitted draft to appear immediately after the edit form closes, so that the action does not appear to have discarded my changes.
9. As a Member, I want background work to survive ordinary app exits and device restarts, so that I do not need to keep the edit screen or app open.
10. As a Member, I want background work to wait for connectivity and report failed attempts, so that I receive a visible outcome instead of an indefinitely retried request.
11. As a Member, I want the ordinary **Save** action to remain available, so that I can save an Item without sending its photo or Description to Gemini.
12. As a Member, I want the ordinary **Save** behavior to remain unchanged, so that this feature does not alter the established edit workflow.
13. As a Member, I want **Save** to remain the primary action, so that ordinary persistence is still the default choice.
14. As a Member, I want **Save & generate description** to be visually secondary, so that sending content to Gemini is a deliberate choice.
15. As a Member, I want the generation action disabled when the draft has no Item Photo, so that I cannot request a photo-based Description without a photo.
16. As a Member, I want removing the current Item Photo to disable generation, so that a removed photo is not submitted accidentally.
17. As a Member, I want invalid drafts to remain open with the existing validation errors, so that invalid Item data is not queued or presented optimistically.
18. As a Member, I want Item name, Description, Tag, and web URL limits to apply before work is queued, so that background persistence cannot begin with an invalid draft.
19. As a Member, I want a replacement full-size photo uploaded before Gemini runs, so that Gemini sees the same immutable photo revision referenced by the saved Item.
20. As a Member, I want thumbnail upload to remain independent, so that thumbnail failure does not prevent Description Generation once the full photo is available.
21. As a Member, I want Gemini to preserve every factual statement in my Description, so that generated prose does not erase knowledge the photo cannot reveal.
22. As a Member, I want Gemini to add only clearly visible details, so that the generated Description avoids unsupported guesses.
23. As a Member, I want Gemini to avoid guessing a brand or model unless it is visibly supported, so that the Inventory does not gain misleading identifiers.
24. As a Member, I want a concise plain-text paragraph, so that the Description reads naturally in the existing Item details view.
25. As a Member, I want generated text without Markdown or headings, so that formatting artifacts do not appear in the Description field.
26. As a Member, I want the generated Description limited to 2,000 Unicode characters, so that it satisfies the existing Item policy.
27. As a Member, I want a blank or oversized Gemini response rejected, so that invalid generated content does not replace a usable Description.
28. As a Member, I want Gemini to use the existing Description's language when it is detectable, so that generated text remains consistent with my writing.
29. As a Member, I want Gemini to use my device language when the existing Description is blank, so that photo-only generation uses an appropriate language.
30. As a Member, I want a successful generated Description to replace the current Description, so that the explicit generation request has a visible result.
31. As a Member, I accept that the generated result replaces a newer Member-written Description, so that the requested LLM result remains authoritative.
32. As a Member, I accept that a delayed background Save may replace newer edits to any captured Item field, so that the asynchronous workflow remains simple and last-write-wins.
33. As a Member, I want Gemini's Description update attributed to the Member who requested it, so that existing Item attribution remains meaningful.
34. As a Member, I want a failed generation to leave the saved Member-written Description intact, so that a model failure does not erase my input.
35. As a Member, I want save, upload, and generation failures reported as permanent outcomes, so that every failed request produces a visible result.
36. As a Member, I want a permanent Save failure reported distinctly, so that I know the complete draft was not persisted.
37. As a Member, I want a permanent full-photo upload failure reported distinctly, so that I know why Description Generation could not proceed.
38. As a Member, I want a permanent Description Generation failure reported distinctly, so that I can reopen Edit and try again.
39. As a Member, I want each permanent error shown only once when the app is active, so that background failures are visible without becoming persistent clutter.
40. As a Member, I do not want success messages, progress indicators, or system notifications for Description Generation, so that the workflow remains unobtrusive.
41. As a Member, I want the model version remotely changeable, so that an installed app can continue working when a Gemini model is retired.
42. As a Member, I want the app to fall back to its bundled model name when remote configuration is unavailable, so that configuration failure does not disable the feature.
43. As a Household, I want Firebase App Check enforced for AI requests, so that unauthorized clients cannot consume the project's Gemini quota.
44. As a developer, I want debug builds and emulators to use the App Check debug provider, so that protected AI requests remain testable during development.
45. As a developer, I want distributed builds to use Play Integrity configured for outside-Google-Play distribution, so that privately distributed builds can attest without Play Store installation.
46. As a maintainer, I want the background workflow behind a small interface, so that UI behavior and orchestration can be tested without Android scheduling or live Gemini calls.
47. As a maintainer, I want the model prompt and response validation kept in tested application code, so that Remote Config cannot silently weaken the agreed Description rules.

## Implementation Decisions

- Description Generation is available only while editing an existing non-root Item. It is not added to Item creation.
- The edit form retains the primary filled **Save** action and adds a secondary outlined **Save & generate description** action beneath it.
- The generation action is enabled only when the current draft has either its existing Item Photo or an unsaved replacement photo. A draft whose photo was removed is ineligible.
- The generation action uses the same synchronous local validation and normalization policies as ordinary Save. Validation failure keeps Edit open, shows the existing field error, and enqueues no work.
- Ordinary **Save** retains its existing wait-for-confirmation, success, and failure behavior. Only **Save & generate description** adopts immediate close, optimistic state, and WorkManager-owned persistence.
- A new action is added to the Inventory controller interface for Save plus Description Generation. The controller delegates the durable workflow through one small background-work interface rather than knowing WorkManager, Firebase AI Logic, or photo-transfer details.
- The background-work module accepts an immutable snapshot containing the Household and Item identities, every editable draft field, current photo choice, requesting Member attribution, and device language. It allocates any replacement photo revision before returning the optimistic Item.
- Once local validation passes and the workflow is submitted to WorkManager, Edit closes immediately without awaiting enqueue completion. The controller overlays the optimistic Item onto observed Inventory state while its background Save is pending so unrelated Firestore refreshes do not immediately erase the submitted draft.
- Enqueueing produces no success message and no persistent progress state in the UI.
- The WorkManager workflow requires network connectivity and does not retry failed attempts.
- For an unchanged existing Item Photo, the workflow saves the captured draft and then loads the captured immutable full-photo revision for generation.
- For a replacement Item Photo, the workflow saves the captured draft, uploads the full-size revision, then loads that stored revision for generation. The thumbnail upload proceeds independently and does not gate generation.
- The full-size stored photo is decoded on the device and submitted as inline image data. Cloud Storage URLs are not supplied directly to Gemini.
- The full captured draft is written unconditionally. It may overwrite any Item fields changed by another Member after enqueueing.
- After the full Save, the generated result updates only Description, update timestamp, and last-updating Member attribution. It does not replay captured names, Tags, URLs, or photo fields a second time.
- The generated Description overwrites the current Description even when a Member changed it after generation was requested. No version comparison or stale-result guard is added.
- Work is not made unique per Item. There is no cancellation, replacement, deduplication, request ID, or ordering guard for overlapping Description Generation requests. If several complete, the last write observed by Firestore wins.
- The workflow uses Firebase AI Logic with the Gemini Developer API backend on its free tier.
- The model name comes from Firebase Remote Config, with `gemini-3.7-flash` bundled as the fallback. Prompt text, language rules, output constraints, and validation remain in application code.
- Gemini receives the current Item Photo and existing Member-written Description as content. Other Item fields are not added as descriptive model context.
- The prompt treats the existing Description as authoritative source material: preserve its facts, rewrite for clarity where useful, add only clearly visible identification details, and avoid unsupported brand or model claims.
- Output is one concise plain-text paragraph with no Markdown or headings. It uses the existing Description's language when detectable and otherwise the captured device language.
- The result is trimmed and accepted only when nonblank and no longer than 2,000 Unicode code points. A response that violates the contract is a permanent generation failure and leaves the saved Description unchanged.
- The generated update uses the requesting Member's captured ID and display-name attribution, consistent with existing Item update fields and Firestore authorization.
- No special handling is added for sign-out or a different authenticated identity appearing before work runs. Ordinary Firebase authentication and authorization failures determine the result.
- Connectivity, throttling, and remote-service failures terminate the affected workflow stage without WorkManager retry.
- A permanent failure is retained locally until presented once when the app is next active. The Member sees exactly one stage-appropriate in-app Snackbar: **Couldn't save the Item.**, **Item saved, but couldn't upload its photo.**, or **Item saved, but couldn't generate its description.**
- No Android system notification is posted for success or failure.
- The remote Item schema gains no Description Generation status, request, or history fields. Work lifecycle and one-time failure delivery remain device-local.
- Firebase App Check baseline protection is enforced for Firebase AI Logic. Distributed builds use Play Integrity configured for distribution outside Google Play; local and emulator builds use the debug provider. Replay protection is not enabled initially.
- The Gemini Developer API free-tier data-use terms are accepted. The app shows Members no privacy confirmation, disclosure, or informational copy before or after submission.

## Testing Decisions

- Good tests assert externally observable behavior through a module's interface: controller state, queued workflow inputs, persisted Item values, Gemini inputs, returned workflow outcomes, and presented error messages. Tests must not assert Worker class names, WorkManager graph internals, SDK call sequences that are not contractual, or Compose layout structure.
- The primary test seam is the existing Inventory controller interface. Controller tests use an in-memory background-work adapter that records submitted snapshots and can report completion or stage failure.
- Controller tests cover generation eligibility for an existing or replacement Item Photo, ineligibility after photo removal or without a photo, local validation, immediate Edit closure, optimistic Item presentation, overlay behavior during Firestore refreshes, absence of success/progress messages, unchanged ordinary Save behavior, and one-time stage-specific failure presentation.
- WorkManager orchestration remains behind the background-work module. A platform-neutral workflow runner is an internal seam tested with fake Item persistence, photo storage, model configuration, Gemini, and failure-delivery adapters.
- Workflow tests cover the unconditional full-draft Save, last-write-wins behavior, unchanged-photo generation, replacement full-photo upload before generation, independent thumbnail upload, inline photo submission, exact captured Description input, language selection, requesting-Member attribution, and Description-only final patch.
- Workflow tests cover nonblank plain-text acceptance, whitespace trimming, the 2,000-code-point limit, preservation of the saved Description after blank or oversized output, and replacement of a newer Description by a valid result.
- Workflow tests cover Save, full-photo upload, photo loading, Gemini, and final Firestore update failures mapped to the three Member-visible failure stages without retry outcomes.
- Model configuration tests cover a Remote Config model name and fallback to `gemini-3.7-flash` when remote configuration is missing or unavailable.
- Existing Inventory controller tests are prior art for form validation, optimistic state transitions, gateway fakes, and failure presentation.
- Existing photo background-work tests are prior art for a platform-neutral runner, independent photo variants, WorkManager retry outcomes, and immutable photo revisions.
- Existing Firebase Inventory gateway tests are prior art for full Item persistence, Description-only updates, photo revisions, and requesting-Member attribution.
- Firestore and Storage emulator tests verify that the background full Save, generated Description patch, replacement photo upload, and authenticated Member attribution remain authorized while cross-Household access remains denied.
- App Check provider wiring, Firebase AI Logic enablement, Remote Config parameter setup, and console enforcement require a documented Firebase integration check because local unit tests cannot prove remote-console configuration.

## Out of Scope

- Description Generation while adding a new Item or editing the Household root Item.
- Text-only Description Generation without an Item Photo.
- Previewing, accepting, rejecting, comparing, or manually merging Gemini's response before it is saved.
- Protecting newer Member-written Descriptions or other Item fields from the queued Save or generated result.
- Per-Item work uniqueness, cancellation, deduplication, sequencing, or stale-request detection.
- Persistent generation progress, history, audit records, request records, or status fields in Firestore.
- Success Snackbars, progress indicators, completion notifications, or Android system notifications.
- A server-owned queue, Cloud Function, custom backend proxy, or completion independent of the originating app installation.
- Guarantees after app uninstall, force-stop, permanent sign-out, authentication changes, or a device that never reconnects.
- Special behavior when another Member signs in on the originating device before work runs.
- Paid Gemini usage, Agent Platform Gemini API, Cloud Storage URL model inputs, or non-Gemini providers.
- Generating or modifying Item names, Tags, web URLs, Parent Items, or photos with Gemini.
- Search grounding, tools, chat, image generation, fine-tuning, embeddings, or semantic retrieval.
- A Member-visible privacy disclosure or confirmation for Gemini free-tier data handling.
- Replay protection for App Check.
- Changing the established behavior of ordinary **Save**.
- Production-scale quotas, performance targets, billing controls, analytics, or AI quality evaluation dashboards.

## Further Notes

- This specification uses **Description Generation** as defined by the Household Inventory glossary and follows the accepted decisions that generated Descriptions override newer input, the workflow is device-owned through WorkManager and Firebase AI Logic, and the Gemini Developer API free tier is used without Member-facing disclosure.
- The current app already uses Jetpack WorkManager for persistent photo transfers and stores immutable full-size and thumbnail Item Photo revisions. The new workflow should deepen that established pattern rather than expose WorkManager details to the Inventory controller.
- Existing full-size Item Photos are mobile-optimized WebP files capped at 2 MB, which is suitable for loading and sending inline within Firebase AI Logic's request-size limit.
- Firebase project setup is part of delivery: enable Firebase AI Logic with the Gemini Developer API, configure the Remote Config model parameter, register App Check providers, allow outside-Google-Play Play Integrity verdicts for distributed builds, register development debug tokens, and enforce baseline protection for Firebase AI Logic.
- `gemini-3.7-flash` is a short-term-availability model. Remote Config reduces migration latency, but maintainers must still monitor model shutdown announcements and validate replacement model behavior.
