# 14 — Optimise Item photos for mobile without blocking Item creation

**What to build:** A Member can finish creating an Item immediately after choosing a photo while mobile-optimised full and thumbnail WebP images upload independently in the background.

**Blocked by:** 06 — Members add optional Item photos camera-first.

**Status:** implemented

- [x] A cropped Item photo produces only lossy WebP images, with no JPEG generation, storage, migration, or fallback behaviour.
- [x] The full image is at most 1024 × 1024 pixels and uses WebP quality 75 so reasonably sized text on an Item remains readable on a phone.
- [x] The thumbnail is at most 256 × 256 pixels and uses WebP quality 68 for compact Item and search-result views.
- [x] Images smaller than a target size are not upscaled.
- [x] The full image is stored at `households/{householdId}/items/{itemId}.webp` and the thumbnail at `households/{householdId}/items/{itemId}-thumb.webp`.
- [x] Item creation completes and releases the Item form without waiting for either image upload, allowing the Member to continue using the app immediately.
- [x] Full-image and thumbnail uploads proceed and retry independently after Item creation, including while the Member navigates elsewhere or backgrounds the app.
- [x] Item detail and editing views load the full image, while compact Item and search-result views load the thumbnail.
- [x] A pending or independently failed image upload leaves the Item usable and displays a non-blocking placeholder instead of blocking navigation or Item creation.
- [x] Photo replacement or removal, Item subtree deletion, and Household deletion each account for both stored image variants.
- [x] Storage authorization accepts only the two expected WebP object names and MIME type, with upload limits appropriate to each image size.
- [x] Automated checks cover WebP dimensions and format, both storage locations, immediate Item creation, independent background upload success and retry, variant-specific display, authorization, replacement, and deletion.

## Comments

- Implemented full and thumbnail WebP processing, deterministic storage locations, persistent independent WorkManager transfers, retrying placeholders, cleanup scheduling, and variant-specific authorization.
- Editing, search, Item subtree deletion, and Household deletion remain owned by issues 07, 08, 10, and 12; their photo presentation and cleanup paths now use the variant-aware seams added here.
- Added JVM, Android instrumentation, and Firebase emulator coverage. The Android encoder test requires a connected device or emulator.
