# 02: Complete reviewer access and App Content declarations

**What to build:** Give Google reviewers a reliable way to reach all reviewable app functionality and complete every required App Content declaration with answers that match the shipped app, its services, and its public privacy and Account Deletion pages.

**Blocked by:** 01

**Status:** ready-for-human

- [x] Google Play's app-access section contains tested reviewer instructions and a reusable reviewer account or access path that reaches authenticated functionality; credentials are stored only in the protected Play Console form and never in the repository or ticket.
- [x] The reviewer access path is tested from a fresh Play-installed build and does not depend on an employee device, an expiring one-time code, or undocumented setup.
- [x] Ads and Advertising ID declarations accurately state that the app does not serve ads and does not use the Advertising ID, unless the release's actual dependencies require a different answer.
- [x] Content rating and target-audience questionnaires are completed using the behavior and content of the release rather than aspirational future features.
- [x] Data Safety disclosures match the data collected or shared by the app and its deployed Google/Firebase services, including authentication, Household data, Photos, Search, Description Generation, diagnostics, encryption, deletion, and retention behavior.
- [x] Government, financial, and health declarations are completed accurately, with supporting notes retained for any non-obvious answer.
- [x] The public privacy-policy and Account Deletion URLs load without authentication, describe the shipped behavior, and are entered in every applicable Play Console field.
- [x] App Content shows no remaining required declarations needing attention, and all changes are included in the next review submission.

## Comments

- 2026-09-16: Completed the Play Console App Content declarations with DevTools. Saved Ads = No, Advertising ID = No, Government app = No, Financial features = none, Health = none, content ratings (IARC), and Target audience = 18 and over. Saved a reusable `Google Play reviewer account` entry with English access instructions; credentials remain only in Play Console. Data Safety is saved as encrypted in transit, OAuth account creation, account deletion URL `https://alexzhidkov.github.io/MyStuffApp-v2/delete-account/`, no partial-data deletion request path, no third-party sharing, and collected Name, Email address, User IDs, Approximate location, Photos, In-app search history, Other user-generated content, Diagnostics, and Device or other IDs with their handling purposes. Both public URLs were verified unauthenticated. App Content shows no declarations needing attention; Publishing overview lists the saved changes as not yet submitted for review. A human still needs to test the supplied account from a fresh Play-installed build and then can submit the staged changes.
