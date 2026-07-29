# 12 — Members rename and the Owner deletes the Household

**What to build:** Current Members can rename the Household root, while only the Household Owner can permanently delete the entire Household through a separate settings action.

**Blocked by:** 04 — Invited Member joins the Household; 10 — Members permanently delete Items and subtrees.

**Status:** ready-for-agent

- [ ] Any current Member can rename the Household using a name trimmed to 1–100 Unicode characters.
- [ ] Renaming the Household updates the root Item and all displayed Item Paths without changing Item identities.
- [ ] Household deletion is available only to the Household Owner through a separate settings action.
- [ ] The deletion warning shows the current total Member and Item counts and states that all Household data and photos will be permanently removed.
- [ ] Deletion remains disabled until the Household Owner types the displayed Household name exactly.
- [ ] Confirming deletion removes the Household, root Item, descendants, memberships, pending invitations, and all associated photos.
- [ ] Former Members return to the no-Household entry state after deletion.
- [ ] Non-Owners are denied Household deletion through both the UI and Firebase.
- [ ] Automated checks cover rename validation, Item Path updates, Owner-only deletion, count accuracy, typed-name confirmation, and complete data and photo cleanup.
