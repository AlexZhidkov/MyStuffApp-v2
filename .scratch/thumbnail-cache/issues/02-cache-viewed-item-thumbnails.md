# 02 — Cache viewed Item thumbnails

**What to build:** A Member sees previously viewed Item thumbnails faster because compact Inventory and search views reuse app-private memory or disk data instead of downloading the same thumbnail again.

**Blocked by:** 01 — Give each Item photo an immutable revision.

**Status:** implemented

- [x] A compact Item or search-result view loads its thumbnail on demand and caches a successful Firebase Storage response.
- [x] The disk cache stores the original compressed WebP bytes under a deterministic SHA-256 filename derived from the complete versioned thumbnail location.
- [x] The disk cache uses Android's app-private cache storage, survives normal app restarts, and has no app-defined size limit or eviction policy beyond Android's platform behaviour.
- [x] Decoded thumbnails are retained in a least-recently-used memory cache capped at one-eighth of the app's available heap.
- [x] A memory hit renders without a placeholder frame or Firebase request.
- [x] A disk hit decodes locally, promotes the bitmap into memory, and makes no Firebase request.
- [x] Only visible compact thumbnails load on demand; opening an Inventory does not prefetch all Item thumbnails.
- [x] Full-size photos in Item detail and editing views retain their existing uncached loading behaviour.
- [x] The focused cache uses the existing authenticated Firebase Storage download path and introduces no general-purpose image-loading dependency.
- [x] Automated checks prove memory and disk hits avoid Firebase and that full-size photo loading bypasses the persistent thumbnail cache.
