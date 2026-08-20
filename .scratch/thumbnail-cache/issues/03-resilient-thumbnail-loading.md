# 03 — Make thumbnail loading resilient and clear

**What to build:** A Member gets a clear, non-blocking thumbnail experience across local-cache failures, missing connectivity, corrupt entries, and transient Firebase download failures.

**Blocked by:** 02 — Cache viewed Item thumbnails.

**Status:** implemented

- [x] A cached thumbnail remains visible without network connectivity or Firebase validation.
- [x] Reading from disk or downloading shows a neutral loading placeholder rather than reporting that the photo is unavailable.
- [x] A confirmed load failure shows the existing unavailable state without blocking Inventory browsing or search.
- [x] A failed cache miss retries exponentially while its compact view remains visible, using the existing retry bounds.
- [x] Failed downloads and partial files are never committed to the disk cache.
- [x] A cached file that is missing, truncated, or cannot be decoded is deleted and downloaded again automatically.
- [x] If writing a successfully downloaded thumbnail to disk fails, it still displays and remains eligible for the current session's memory cache.
- [x] Automated checks cover offline hits, loading and unavailable presentation, retry, corrupt-entry recovery, partial-write protection, and best-effort disk writes.
