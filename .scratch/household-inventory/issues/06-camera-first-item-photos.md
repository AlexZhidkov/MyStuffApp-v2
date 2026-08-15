# 06 — Members add optional Item photos camera-first

**What to build:** Adding an Item begins with camera capture but always allows the Member to create the Item without a photo.

**Blocked by:** 05 — Members build and browse a text-only Inventory.

**Status:** implemented

- [x] Selecting **Add item** from Home or an Item detail screen opens the camera before the Item form.
- [x] Camera permission is requested only after the Member initiates Item creation or photo editing.
- [x] After capture, the Member can retake the photo, crop and use it, or continue without it.
- [x] Cancelling crop returns to the post-capture choice instead of cancelling Item creation.
- [x] Denied permission, unavailable camera hardware, and capture failure continue to the Item form without a photo.
- [x] A saved photo is stored with the Item and displayed in its detail and compact views.
- [x] Item creation succeeds when no photo is captured.
- [x] Automated checks cover capture outcomes, crop cancellation, photo omission, and the permission-denied path.

## Comments

- The Member explicitly requested that this implementation not enforce the original Household-root photo restriction.
