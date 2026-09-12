# 08: Launch with managed staged publishing

**What to build:** Release MyStuff to production through a controlled Google Play launch, using managed publishing and a conservative staged rollout with clear health checks, expansion criteria, and a recovery plan.

**Blocked by:** 07

**Status:** ready-for-human

- [ ] Managed publishing is enabled before the final production submission so approval cannot make the app public at an unintended time.
- [ ] The production release uses the approved, tested bundle and includes accurate release notes, countries or regions, Store Listing, App Content declarations, and Data Safety disclosures.
- [ ] A documented launch decision identifies the initial rollout percentage, responsible person, observation window, health thresholds, and conditions for halting or expanding the rollout.
- [ ] The team can stop the rollout and ship a corrected higher-version release if a critical defect appears; the recovery path does not depend on downgrading an installed app.
- [ ] The approved release is deliberately published to the initial production cohort and its availability is verified from the public Google Play listing.
- [ ] Crash, ANR, authentication, App Check, backend, review, and support signals are monitored during every rollout stage, with expansion only after the agreed thresholds remain healthy.
- [ ] The rollout reaches the intended production audience, and final listing URLs, release identifiers, launch date, and post-launch observations are recorded.
