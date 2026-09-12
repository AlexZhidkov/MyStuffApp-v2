# 05: Clear Google Play automated quality gates

**What to build:** Exercise the closed-test candidate through Google Play's automated and catalogue-based checks, resolve release-blocking findings, and turn lower-severity findings into traceable follow-up work.

**Blocked by:** 04

**Status:** ready-for-agent

- [ ] A pre-launch report is generated for the closed-test candidate and reaches authenticated screens using the reviewer access instructions where Google Play supports supplied credentials.
- [ ] The pre-launch results contain no unresolved critical crash, ANR, security, privacy, rendering, or accessibility issue that would make the release unsafe for production testing.
- [ ] Every actionable non-blocking finding is either resolved or captured in a local issue with evidence, severity, affected device, and reproduction guidance.
- [ ] The Device Catalogue is reviewed for supported phones, tablets, and ChromeOS devices, and incompatible devices or untested form factors are excluded for an explicit technical reason.
- [ ] Android vitals and release-quality monitoring are checked for available closed-test data, with a baseline or an explicit note that the cohort has not yet produced sufficient data.
- [ ] The candidate's Play Console quality pages and relevant evidence are recorded so the production-access application can refer to a specific tested release.

