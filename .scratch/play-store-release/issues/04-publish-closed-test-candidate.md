# 04: Publish a production-like closed-test candidate

**What to build:** Produce and publish a new closed-testing release that is operationally representative of production, diagnosable when it fails, and available to the intended tester group in the intended countries or regions.

**Blocked by:** 01, 02, 03

**Status:** implemented

- [x] The candidate uses a new version code and an approved user-facing version name, and it is built from an identified source revision with production-equivalent signing, backend, and App Check behavior.
- [x] Release optimization and resource shrinking are enabled at the intended production settings, and the matching deobfuscation mapping is uploaded when code shrinking is used.
- [x] Native debug symbols are uploaded when applicable; otherwise the Play warning is investigated and its harmless disposition is documented against the actual bundle contents.
- [x] Closed testing is configured with the intended countries or regions, a maintained tester group, accurate release notes, and a working opt-in link.
- [x] A tester can install the candidate through Google Play, update from the existing internal-testing build where applicable, launch it, sign in, and reach the primary Household inventory experience.
- [x] Google Play reports the release as active on the closed track with no unresolved release-blocking errors.

## Comments

- 2026-09-16: Built candidate `1.0 (3)` from source base `c931ccefbfc26f77984bf175378c5d4d7f4419fc` plus the recorded release configuration changes in `app/build.gradle.kts` (version code 3, R8 optimization, and resource shrinking). `:app:testDebugUnitTest`, `:app:lintRelease`, and `:app:bundleRelease` pass. Signed the bundle with the registered upload certificate (SHA-256 `54:79:E2:95:D2:A3:A4:E0:24:10:C4:AA:C8:52:FD:06:98:D0:E9:D8:DF:E9:91:DD:B4:38:17:10:AF:EB:69:8B`); the release build retains the production Firebase backend and Play Integrity App Check provider. Play accepted the bundle and its matching ReTrace mapping.
- 2026-09-16: Investigated Play's native-debug-symbol warning against the uploaded bundle. Its eight native objects are stripped prebuilt AndroidX/DataStore libraries (`libandroidx.graphics.path.so` and `libdatastore_shared_counter.so` across four ABIs) with no debug sections or symbol tables from which to produce a native-symbol archive, so the warning is non-blocking for this candidate.
- 2026-09-16: Configured closed testing for Australia with the maintained `Internal Testers` list (two users), feedback at `azhidkov@gmail.com`, and release notes, then submitted all 14 staged release and app-listing changes. Play's automated checks completed without requesting corrective action. The closed track is active, but release `1.0 (3) - Closed test` remains in Google review; the tester opt-in URL therefore still reports that the app is unavailable. Play installation and the Pixel 8 Pro update/sign-in/Household journey remain pending approval, so this issue is not yet marked implemented.
- 2026-09-16: The user confirmed that Google Play completed the review, the opt-in/install path works, the existing internal-test installation updated through Play, and the Pixel 8 Pro successfully launched, signed in, and reached the primary Household inventory experience. Release `1.0 (3) - Closed test` is active without unresolved release-blocking errors; all acceptance criteria are complete.
