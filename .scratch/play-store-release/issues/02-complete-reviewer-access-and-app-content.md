# 02: Complete reviewer access and App Content declarations

**What to build:** Give Google reviewers a reliable way to reach all reviewable app functionality and complete every required App Content declaration with answers that match the shipped app, its services, and its public privacy and Account Deletion pages.

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] Google Play's app-access section contains tested reviewer instructions and a reusable reviewer account or access path that reaches authenticated functionality; credentials are stored only in the protected Play Console form and never in the repository or ticket.
- [ ] The reviewer access path is tested from a fresh Play-installed build and does not depend on an employee device, an expiring one-time code, or undocumented setup.
- [ ] Ads and Advertising ID declarations accurately state that the app does not serve ads and does not use the Advertising ID, unless the release's actual dependencies require a different answer.
- [ ] Content rating and target-audience questionnaires are completed using the behavior and content of the release rather than aspirational future features.
- [ ] Data Safety disclosures match the data collected or shared by the app and its deployed Google/Firebase services, including authentication, Household data, Photos, Search, Description Generation, diagnostics, encryption, deletion, and retention behavior.
- [ ] Government, financial, and health declarations are completed accurately, with supporting notes retained for any non-obvious answer.
- [ ] The public privacy-policy and Account Deletion URLs load without authentication, describe the shipped behavior, and are entered in every applicable Play Console field.
- [ ] App Content shows no remaining required declarations needing attention, and all changes are included in the next review submission.

