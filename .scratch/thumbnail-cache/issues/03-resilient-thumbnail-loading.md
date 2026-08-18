# 03 — Make thumbnail loading resilient and clear

**What to build:** A Member gets a clear, non-blocking thumbnail experience across local-cache failures, missing connectivity, corrupt entries, and transient Firebase download failures.

**Blocked by:** 02 — Cache viewed Item thumbnails.

**Status:** ready-for-agent

- [ ] A cached thumbnail remains visible without network connectivity or Firebase validation.
- [ ] Reading from disk or downloading shows a neutral loading placeholder rather than reporting that the photo is unavailable.
- [ ] A confirmed load failure shows the existing unavailable state without blocking Inventory browsing or search.
- [ ] A failed cache miss retries exponentially while its compact view remains visible, using the existing retry bounds.
- [ ] Failed downloads and partial files are never committed to the disk cache.
- [ ] A cached file that is missing, truncated, or cannot be decoded is deleted and downloaded again automatically.
- [ ] If writing a successfully downloaded thumbnail to disk fails, it still displays and remains eligible for the current session's memory cache.
- [ ] Automated checks cover offline hits, loading and unavailable presentation, retry, corrupt-entry recovery, partial-write protection, and best-effort disk writes.
