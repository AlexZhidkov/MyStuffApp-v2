# 03: Renew active App Connections and require fresh approval when needed

**What to build:** Keep an actively used App Connection working through automatic token renewal, while requiring fresh approval after 90 days without use or after loss of Household Access. An existing connection must never regain access merely because membership returns or a Member switches Households.

**Blocked by:** 01: Connect Gemini CLI and read Item details.

**Status:** ready-for-agent

- [ ] Active App Connections automatically renew through the standard OAuth flow without repeated browser approval. Verify renewal in a real MCP client and document the result.
- [ ] After 90 days without use, an App Connection cannot read Inventory or obtain usable renewed credentials until the Member gives fresh approval. Automated tests use a controlled clock to cover active use and the inactivity boundary.
- [ ] Household Access removal permanently invalidates affected App Connections and their ability to renew. Restoring access to the same Household requires fresh approval, including when removal and restoration both happen before the next MCP request.
- [ ] Joining or switching to a different Household never redirects an old App Connection; reading the different Household requires fresh approval naming it.
- [ ] Account Deletion, Household deletion, and Household Owner deletion states stop reads and renewal using the existing deletion checks. Cleanup or a later sign-in must not revive old credentials or restore former approval.
- [ ] Automated lifecycle tests cover removal/restoration, changed Household, inactivity, renewal, deletion, and attempted reuse of invalidated credentials. Existing MyStuff sign-in sessions and unrelated Members' connections remain usable when their own authorization is valid.
- [ ] The lifecycle checks apply to every Inventory request and continuation request through shared authorization, rather than depending on individual tools to remember invalidation rules.
