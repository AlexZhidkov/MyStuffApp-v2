# 03: Publish a complete phone-first Store Listing

**What to build:** Create a credible default Google Play Store Listing for MyStuff using the existing brand direction, safe representative screenshots, accurate support information, and only the device form factors the team intends to support and test.

**Blocked by:** None

**Status:** implemented

- [x] The default English (Australia) listing has an approved app name, short description, and full description that explain Household inventory, Photos, Search, and optional Description Generation without unsupported claims.
- [x] The listing includes a compliant 512-by-512 app icon and 1024-by-500 feature graphic derived from the existing brand artwork without distortion or accidental padding.
- [x] At least four current phone screenshots show a coherent first-use journey and contain no real Member, Household, Item, location, or credential data.
- [x] App category and tags are selected to make the app discoverable as a household inventory utility, and the public support email and website are complete and monitored.
- [x] External marketing is either deliberately enabled with approved assets and contact details or deliberately disabled, with the decision recorded.
- [x] Android XR distribution is disabled unless the release has been deliberately tested on XR and has suitable XR presentation assets; other enabled form factors match the team's test coverage.
- [x] The Store Listing has no validation errors or missing required assets and is included in the next review submission.

## Comments

2026-09-13 — Created and saved the default English (Australia) listing in Play Console. The listing uses `MyStuff`, the Household Inventory / Photos / Tags / Search copy, and the optional Gemini Description Generation disclosure. Added the brand-derived icon at `design/store-listing/final/app-icon-512.png`, feature graphic at `design/store-listing/final/feature-graphic-1024x500.png`, and five 1080×1920 phone screenshots under `design/store-listing/final/phone/`. Screenshot data is fictional and follows a first-use journey from sign-in through browsing, adding a photo, searching, and requesting description generation. Play's AI declaration labels only `03-add-item-photo.png`, which contains the generated fictional garage-shelf photo; the other assets are deterministic brand/UI renders.

The listing is saved and Play reports “Ready to send for review”; it was intentionally not submitted yet because App Content/reviewer access (issue 02) remains pending. The initial DevTools session became unavailable before Store settings could be completed; those settings were completed in the fresh authenticated session described below.

2026-09-13 — Completed Store settings in the fresh authenticated DevTools session. Set category to `House & home`, selected the `Tools` tag, published contact details (`azhidkov@gmail.com` and `https://alexzhidkov.github.io/MyStuffApp-v2/`), and unchecked **Advertise my app outside Google Play**. Verified the default listing still reports “Ready to send for review.” The Android XR form-factor page has no XR screenshots or videos and no dedicated XR release track was opted into; the release manifest declares no XR feature (`android.software.xr.api.spatial`/`openxr`), so the app has no XR-specific distribution or presentation assets. No tablet or Chromebook assets were added.
