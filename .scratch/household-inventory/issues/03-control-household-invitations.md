# 03 — Household Owner issues and controls invitations

**What to build:** The Household Owner can create an invitation tied to one Google email address and revoke or replace it before it is accepted.

**Blocked by:** 02 — Member creates and reopens a Household.

**Status:** ready-for-agent

- [ ] The Household Owner can create a pending invitation for one entered Google email address.
- [ ] An invitation records its Household, intended email address, creation time, seven-day expiry, and current status.
- [ ] The Household Owner can revoke a pending invitation.
- [ ] Replacing an invitation immediately invalidates its previous link and leaves only the replacement usable.
- [ ] Non-Owners cannot create, replace, or revoke Household invitations through either the UI or Firebase.
- [ ] The invitation UI clearly distinguishes pending, revoked, replaced, and expired invitations.
- [ ] Automated checks cover Owner authorization, seven-day expiry, revocation, and replacement.
