# 05 — Clear thumbnails at authentication boundaries

**What to build:** Private Household thumbnails do not remain cached when the active Member signs out, loses authentication, or changes identity.

**Blocked by:** 02 — Cache viewed Item thumbnails.

**Status:** ready-for-agent

- [ ] Every transition out of an authenticated session clears all decoded thumbnails from memory and deletes all thumbnail disk-cache entries.
- [ ] Clearing occurs for explicit sign-out, authentication expiry or failure, and a change to a different authenticated identity.
- [ ] Thumbnail loads already in progress are cancelled before cache clearing begins.
- [ ] A cancelled load cannot finish later and repopulate memory or disk with data from the previous Member's Household.
- [ ] The cache remains in Android's app-private storage without additional encryption.
- [ ] Automated checks cover each authentication boundary and the race between an in-flight download and cache clearing.
