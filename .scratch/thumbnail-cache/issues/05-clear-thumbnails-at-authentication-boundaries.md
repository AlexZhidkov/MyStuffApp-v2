# 05 — Clear thumbnails at authentication boundaries

**What to build:** Private Household thumbnails do not remain cached when the active Member signs out, loses authentication, or changes identity.

**Blocked by:** 02 — Cache viewed Item thumbnails.

**Status:** implemented

- [x] Every transition out of an authenticated session clears all decoded thumbnails from memory and deletes all thumbnail disk-cache entries.
- [x] Clearing occurs for explicit sign-out, authentication expiry or failure, and a change to a different authenticated identity.
- [x] Thumbnail loads already in progress are cancelled before cache clearing begins.
- [x] A cancelled load cannot finish later and repopulate memory or disk with data from the previous Member's Household.
- [x] The cache remains in Android's app-private storage without additional encryption.
- [x] Automated checks cover each authentication boundary and the race between an in-flight download and cache clearing.

## Comments

- Firebase authentication-state observation and session UI transitions both reach the Item Photo loading module's session seam, covering explicit sign-out, authentication loss, and identity replacement.
- Session clearing cancels owned requests before clearing thumbnail memory/disk and attachment display files. Cache generations reject late non-cancellable download completions.
- The persisted app-private session identity also detects an identity change across process recreation; no additional cache encryption was introduced.
