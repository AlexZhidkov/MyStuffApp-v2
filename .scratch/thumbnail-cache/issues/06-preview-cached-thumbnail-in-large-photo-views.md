# 06 — Preview cached thumbnail in large Item Photo views

**What to build:** A Member opening Item details or editing an Item sees a cached thumbnail while the full Item Photo downloads instead of seeing a premature unavailable message.

**Blocked by:** 02 — Cache viewed Item thumbnails.

**Status:** implemented

- [x] A decoded memory-cached thumbnail appears immediately in Item details and Item editing while the full Item Photo loads.
- [x] A disk-cached thumbnail is decoded concurrently with the full-photo download and never causes an additional Firebase thumbnail request on a cache miss.
- [x] The preview fills the existing photo frame with the same aspect-ratio-preserving crop as the full Item Photo.
- [x] The full Item Photo replaces the preview with a 200 ms crossfade, and a late thumbnail result cannot overwrite it.
- [x] The preview remains visible without a spinner or error overlay while full-photo failures retry automatically.
- [x] Loading without a cached preview uses a neutral surface; the unavailable message appears only after a confirmed failure when no photo can be displayed.
- [x] Full Item Photos retain their existing uncached loading behavior.
- [x] Automated checks cover cache-only memory and disk behavior, preview retention, full-photo precedence, and loading and failure presentation.

## Comments

- The design was agreed through a grilling session on 2026-08-20.
