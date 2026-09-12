# 02 — Delete MyStuff Accounts and Households

Status: implemented

- [x] Add discoverable in-app Account Deletion and Owner-only Household deletion.
- [x] Require fresh Google reauthentication and exact Household-name confirmation
  when shared Household data will be deleted.
- [x] Show current Member and visible Item counts before Household deletion.
- [x] Revoke access through durable deletion jobs before asynchronous cleanup.
- [x] Delete Owner Households; preserve non-Owner shared content with anonymized
  attribution; remove Authentication last.
- [x] Cancel pending local work and clear app-owned temporary files and caches.
- [x] Cover application behavior, backend cleanup, and security rules with tests.

