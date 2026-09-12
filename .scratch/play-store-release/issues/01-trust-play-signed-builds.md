# 01: Make Play-signed builds trusted by Firebase

**What to build:** Complete and verify the Firebase configuration needed for Google Play-signed releases to authenticate and use protected backend capabilities. Keep development builds usable through the App Check debug provider, and bring the publishing guide up to date with the deployed backend and Play distribution path.

**Blocked by:** None

**Status:** ready-for-agent

- [ ] The Firebase Android app contains the current and previous Google Play app-signing SHA-1 and SHA-256 certificate fingerprints; the upload certificate is not treated as the identity of Play-installed builds.
- [ ] Firebase App Check is linked to Google Play Integrity for the `mystuff-ai-app` project and requires `PLAY_RECOGNIZED` and `LICENSED` for Play-distributed release builds, with Device integrity as the minimum acceptable device integrity level.
- [ ] The one-hour App Check token lifetime is retained unless a documented test demonstrates a better security/reliability trade-off.
- [ ] A build installed from the Google Play internal-testing opt-in path can sign in with Google and complete a representative Household, Search, and Description Generation journey without certificate or App Check failures.
- [ ] A local development build still works with the App Check debug provider without weakening the release configuration.
- [ ] The Play publishing documentation reflects the deployed website and backend, the active internal release, and the requirement that release builds come through Google Play.

