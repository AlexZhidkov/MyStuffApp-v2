# 03: Repair broken Item Photo references and prove the device journey

**What to build:** Repair Items that already point at missing Photo objects, then prove on a USB-connected Android device that a Member can capture an Item Photo, store and display it, generate a Description from it, and still see the result after reloading the app.

**Blocked by:** 01: Restore production Item Photo access within the Storage rules budget; 02: Make Description Generation clean up failed Photo uploads.

**Status:** ready-for-agent

- [ ] A dry-run mode inventories Item Photo projections and attachment records whose full-size Storage object is missing, with enough Household, Item, and attachment identity to review every proposed repair.
- [ ] Applying the repair clears only confirmed broken Item Photo projections, removes their orphaned attachment records and partial stored variants, and leaves intact or concurrently replaced Photos unchanged.
- [ ] The repair is idempotent, reports examined/repaired/skipped/error counts, and can be safely rerun after interruption.
- [ ] The affected `bucket` Item is returned to a consistent no-Photo state if its broken reference still exists; the unrecoverable original image is not represented as restored and can be retaken by the Member.
- [ ] On the connected Android device, an authenticated Member can create or edit an Item with a new Photo, select **Save & generate description**, and observe the Photo and generated Description without a Storage permission failure.
- [ ] The stored full-size object, thumbnail, attachment record, and Item Photo projection agree on the immutable attachment revision used by the successful device attempt.
- [ ] After the app is restarted or its data is reloaded, the same Photo and generated Description remain visible.
- [ ] Verification records the app build, Firebase environment, device, App Check state, and relevant worker outcome so the production-only regression is reproducible.
