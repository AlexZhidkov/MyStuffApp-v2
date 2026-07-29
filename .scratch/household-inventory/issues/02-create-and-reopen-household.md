# 02 — Member creates and reopens a Household

**What to build:** A signed-in Member can create one named Household, become its Household Owner, and later reopen its root Item. Firebase authorization prevents non-Members from accessing it.

**Blocked by:** 01 — Member signs in and out with Google.

**Status:** ready-for-agent

- [ ] A signed-in person without a Household can create one using a name trimmed to 1–100 Unicode characters.
- [ ] Creating a Household creates exactly one root Item with the same name and makes the creator its Household Owner.
- [ ] The Household is the only Item without a Parent Item and has no photo, description, or Tags.
- [ ] A returning Member is routed directly into their existing Household after sign-in.
- [ ] A Member who already belongs to a Household cannot create another Household.
- [ ] Firebase authorization allows current Members to access the Household and rejects authenticated non-Members.
- [ ] Automated checks cover creation, reopening, name validation, the one-Household limit, and the Household authorization boundary.
