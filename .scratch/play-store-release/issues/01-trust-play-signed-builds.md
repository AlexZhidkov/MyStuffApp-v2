# 01: Make Play-signed builds trusted by Firebase

**What to build:** Complete and verify the Firebase configuration needed for Google Play-signed releases to authenticate and use protected backend capabilities. Keep development builds usable through the App Check debug provider, and bring the publishing guide up to date with the deployed backend and Play distribution path.

**Blocked by:** None

**Status:** ready-for-agent

- [x] The Firebase Android app contains the current and previous Google Play app-signing SHA-1 and SHA-256 certificate fingerprints; the upload certificate is not treated as the identity of Play-installed builds.
- [x] Firebase App Check is linked to Google Play Integrity for the `mystuff-ai-app` project and requires `PLAY_RECOGNIZED` and `LICENSED` for Play-distributed release builds, with Device integrity as the minimum acceptable device integrity level.
- [x] The one-hour App Check token lifetime is retained unless a documented test demonstrates a better security/reliability trade-off.
- [ ] A build installed from the Google Play internal-testing opt-in path can sign in with Google and complete a representative Household, Search, and Description Generation journey without certificate or App Check failures.
- [x] A local development build still works with the App Check debug provider without weakening the release configuration.
- [x] The Play publishing documentation reflects the deployed website and backend, the active internal release, and the requirement that release builds come through Google Play.

## Comments

- 2026-09-12: Verified the current classical, post-quantum, and previous classical Play app-signing SHA-1/SHA-256 fingerprints in Firebase and refreshed `app/google-services.json`; the upload certificate is not registered as an installed-app OAuth identity. Verified the Play Integrity App Check policy (`PLAY_RECOGNIZED`, `LICENSED`, minimum `MEETS_DEVICE_INTEGRITY`, one-hour TTL) and linked Play Console to project number `37308974986`. Debug and release source sets retain their separate App Check providers. Gradle tests/lint, 45 Firebase Rules emulator tests, and 50 Functions emulator tests pass. The Play-installed device journey remains pending; the first attempt used build `1.0 (1)`, whose bundled Firebase configuration predated the Play certificate registrations, so replacement build `1.0 (2)` is required.
- 2026-09-13: The original upload-keystore password was unavailable, so Play Console accepted a reset request for the reason “I forgot the password to my keystore.” A new upload key and signed `1.0 (2)` bundle are ready, but Play will not accept that certificate until 15 September 2026 at 04:51:43 UTC. The existing Google-managed quantum-ready app-signing keys and Firebase fingerprints remain unchanged.
