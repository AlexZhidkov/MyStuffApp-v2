# 03 — Preserve optimistic state and surface deferred failures

**What to build:** A Member continues to see the submitted Item while its Save and Description Generation run in the background, and receives one clear in-app error if a permanent Save, full-photo, or generation failure occurs—even when the app was not active when the work failed.

**Blocked by:** 01 — Generate a Description from an existing Item Photo; 02 — Generate a Description from a replacement Item Photo.

**Status:** implemented

- [x] A pending background request keeps its optimistic Item overlaid on observed Inventory data so unrelated Firestore refreshes do not immediately restore the older remote Item.
- [x] Successful background persistence reconciles the optimistic Item with the normal Firestore observation flow without showing a success message.
- [x] A permanent Save failure removes or releases the unpersisted optimistic overlay so remote Inventory state can become authoritative again.
- [x] A generation or full-photo failure after Save preserves the remotely saved draft and its Member-written Description.
- [x] Retryable failures remain invisible while WorkManager continues exponential-backoff retries.
- [x] A permanent outcome is retained device-locally until it can be presented once while the app is active.
- [x] Permanent Save failure presents exactly **Couldn't save the Item.**
- [x] Permanent full-photo upload or load failure presents exactly **Item saved, but couldn't upload its photo.**
- [x] Permanent Gemini, generated-output, or final Description-patch failure presents exactly **Item saved, but couldn't generate its description.**
- [x] Each permanent outcome is consumed after one Snackbar and is not shown again on later recompositions, navigation, or app launches.
- [x] No successful stage presents a Snackbar, progress indicator, completion banner, or Android system notification.
- [x] Ordinary **Save** retains its established operation-in-progress, success, failed-save retention, and retry behavior.
- [x] Controller tests cover optimistic overlays across live Inventory emissions, successful reconciliation, permanent Save rollback, saved-draft preservation after later-stage failure, and one-time error consumption.
- [x] Lifecycle-facing tests verify that a failure completed while no screen was active appears once when the Inventory UI is next active without asserting WorkManager implementation details.
