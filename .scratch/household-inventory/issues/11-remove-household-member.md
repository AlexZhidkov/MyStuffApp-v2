# 11 — Household Owner removes a Member

**What to build:** The Household Owner can view and remove another Household Member while Inventory content and attribution remain intact.

**Blocked by:** 04 — Invited Member joins the Household; 05 — Members build and browse a text-only Inventory.

**Status:** ready-for-agent

- [ ] The Household Owner can view the Household's current Members and identify their roles.
- [ ] The Household Owner can remove another Member but cannot remove themselves.
- [ ] A non-Owner cannot remove Members through either the UI or Firebase.
- [ ] Removal blocks the former Member's connected backend access to the Household.
- [ ] Removing a Member retains the Household's Items and their creating and last-updating display-name snapshots.
- [ ] The removed person returns to the no-Household entry state on their next authorized app state.
- [ ] The flow does not offer ownership transfer or self-removal.
- [ ] Automated checks cover Owner-only authorization, self-removal prevention, backend revocation, data retention, and attribution retention.
