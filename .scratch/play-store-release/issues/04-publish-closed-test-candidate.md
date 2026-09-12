# 04: Publish a production-like closed-test candidate

**What to build:** Produce and publish a new closed-testing release that is operationally representative of production, diagnosable when it fails, and available to the intended tester group in the intended countries or regions.

**Blocked by:** 01, 02, 03

**Status:** ready-for-agent

- [ ] The candidate uses a new version code and an approved user-facing version name, and it is built from an identified source revision with production-equivalent signing, backend, and App Check behavior.
- [ ] Release optimization and resource shrinking are enabled at the intended production settings, and the matching deobfuscation mapping is uploaded when code shrinking is used.
- [ ] Native debug symbols are uploaded when applicable; otherwise the Play warning is investigated and its harmless disposition is documented against the actual bundle contents.
- [ ] Closed testing is configured with the intended countries or regions, a maintained tester group, accurate release notes, and a working opt-in link.
- [ ] A tester can install the candidate through Google Play, update from the existing internal-testing build where applicable, launch it, sign in, and reach the primary Household inventory experience.
- [ ] Google Play reports the release as active on the closed track with no unresolved release-blocking errors.

