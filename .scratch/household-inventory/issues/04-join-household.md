# 04 — Invited Member joins the Household

**What to build:** The intended Google Account can accept a valid invitation once and enter the shared Household, while ineligible accounts are rejected.

**Blocked by:** 03 — Household Owner issues and controls invitations.

**Status:** implemented

- [x] Opening an invitation leads an unauthenticated person through Google sign-in and then resumes invitation acceptance.
- [x] Acceptance succeeds only when the signed-in Google email address matches the invitation.
- [x] A valid invitation can be accepted once before its seven-day expiry.
- [x] Acceptance makes the person a current Household Member and invalidates the invitation link immediately.
- [x] Expired, revoked, replaced, already accepted, and unknown invitations are rejected with a clear outcome.
- [x] A person who already belongs to a Household cannot accept an invitation to another Household.
- [x] The new Member can open the same Household root, while unrelated authenticated people remain unable to access it.
- [x] Automated checks cover account matching, invitation state transitions, single use, expiry, and the one-Household limit.
