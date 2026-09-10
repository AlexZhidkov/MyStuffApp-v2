# 11 — Household Owner manages Household Access

**What to build:** The Household Owner manages allowed Google email addresses and current Members in one screen; eligible people join automatically on sign-in, and removal revokes access without changing Inventory content or attribution.

**Blocked by:** 02 — Member creates and reopens a Household; 05 — Members build and browse a text-only Inventory.

**Status:** ready-for-agent

- [ ] The Household Owner sees one Members screen with the non-removable Owner first, followed by one row per allowed Google email address.
- [ ] An unclaimed row shows its email and **Not signed in yet**; after claiming, the same row shows the Member's Google name and email.
- [ ] The Owner can add an email after trimming it and can remove any non-Owner row after a simple confirmation.
- [ ] Email matching ignores letter case but does not treat dot or `+` alias variants as equivalent; duplicate addresses and the Owner's address are rejected.
- [ ] Adding Household Access sends no message, creates no link, requires no approval, and does not expire.
- [ ] A signed-in person without a Household automatically claims matching Household Access, which binds to their stable Google UID; if several rows match, the backend atomically chooses an arbitrary first match without asking.
- [ ] A person who already belongs to a Household does not claim another row, and the other Owner sees only **Not signed in yet** rather than the reason.
- [ ] Non-Owners cannot list, add, or remove Household Access through either the UI or Firebase.
- [ ] Removal blocks the former Member's connected backend access while retaining Household Items and their creating and last-updating display-name snapshots.
- [ ] The removed person returns to the no-Household entry state on their next authorized app state; re-adding their email creates fresh Household Access.
- [ ] The flow offers neither ownership transfer nor self-removal, and Firebase's default offline-cache limitations remain accepted.
- [ ] The legacy invitation UI, deep links, Android models and gateways, backend functions, Firebase rules, and related automated tests are removed rather than retained as a compatibility path.
- [ ] No existing access data is migrated; deployment assumes manual cleanup by the project owner.
- [ ] Automated checks cover Owner authorization, email matching, automatic and arbitrary-first claiming, the one-Household limit, removal, backend revocation, and attribution retention.
