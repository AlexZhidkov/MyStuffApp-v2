# Play Store policy blockers

Implement the repository-side privacy and Account Deletion requirements described
in `docs/play-store-publishing.md`. Deployment, Play Console configuration, signing,
certificates, and store submission remain manual release work.

## Decisions

- Publish a concise Privacy Policy and external Account Deletion instructions from
  this public repository through GitHub Pages.
- Use Alex Zhidkov, Western Australia, Australia and `azhidkov@gmail.com` as the
  operator identity and privacy contact.
- Require fresh Google reauthentication for destructive actions in the app.
- A non-Owner Account Deletion removes Authentication, membership, and every
  matching Household Access; shared Items and Photos remain and their attribution
  becomes `Former member`.
- An Owner Account Deletion deletes the entire Household for all Members. A separate
  Owner-only Household deletion keeps the Owner's MyStuff Account.
- Deletion creates a durable job that immediately revokes app access. Cleanup is
  asynchronous, retryable, and idempotent; Firebase Authentication is deleted last.
- Provider recovery copies and security logs may remain for their documented periods;
  MyStuff keeps no separate archive.
- External requests are manually verified by email and processed through a private,
  manually invoked Firebase Function with preview and explicit execution modes.

## Out of scope

- Automatically deploying GitHub Pages or Firebase resources
- Play Console forms, signing, certificates, listing assets, and submission
- Automated policy-change monitoring or deletion-completion email

