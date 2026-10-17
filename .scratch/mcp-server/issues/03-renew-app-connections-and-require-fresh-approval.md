# 03: Renew active App Connections and require fresh approval when needed

**What to build:** Keep an actively used App Connection working through automatic token renewal, while requiring fresh approval after 90 days without use or after loss of Household Access. An existing connection must never regain access merely because membership returns or a Member switches Households.

**Blocked by:** 01: Connect Gemini CLI and read Item details.

**Status:** implemented

- [x] Active App Connections automatically renew through the standard OAuth flow without repeated browser approval. Verify renewal in a real MCP client and document the result.
- [x] After 90 days without use, an App Connection cannot read Inventory or obtain usable renewed credentials until the Member gives fresh approval. Automated tests use a controlled clock to cover active use and the inactivity boundary.
- [x] Household Access removal permanently invalidates affected App Connections and their ability to renew. Restoring access to the same Household requires fresh approval, including when removal and restoration both happen before the next MCP request.
- [x] Joining or switching to a different Household never redirects an old App Connection; reading the different Household requires fresh approval naming it.
- [x] Account Deletion, Household deletion, and Household Owner deletion states stop reads and renewal using the existing deletion checks. Cleanup or a later sign-in must not revive old credentials or restore former approval.
- [x] Automated lifecycle tests cover removal/restoration, changed Household, inactivity, renewal, deletion, and attempted reuse of invalidated credentials. Existing MyStuff sign-in sessions and unrelated Members' connections remain usable when their own authorization is valid.
- [x] The lifecycle checks apply to every Inventory request and continuation request through shared authorization, rather than depending on individual tools to remember invalidation rules.

## Comments

### 17 October 2026 — implemented and verified locally

Added rotating OAuth refresh credentials, shared transactional App Connection
authorization, and the exact 90-day inactivity boundary. Authenticated MCP
requests update activity; background renewal alone does not. Household Access
removal and deletion requests atomically change permanent authorization
generations, so restored membership, cleared deletion jobs, and later sign-in
cannot restore former approval. Legacy issue 01 approvals follow the same
invalidation rules. Refresh-token replay invalidates its connection while
independent App Connections and unrelated Members remain usable.

Gemini CLI 0.64.0's installed OAuth provider/HTTP transport automatically renewed
expired credentials and read a fixture Item; the actual `gemini mcp list`
executable renewed again and reported the server connected. This used synthetic
identity/Inventory data and isolated temporary credentials against a local
server. No new production deployment or hosted Gemini Custom app renewal check
was performed. Reproduction instructions, verification limits, and the required
removal/deletion/MCP deployment set are in
[`docs/mcp-server.md`](../../../docs/mcp-server.md).

All 72 backend tests passed under Node 22 with the full Firebase emulators and
the optional Gemini CLI compatibility check enabled. All 50 Firestore/Storage
rules tests and Android unit tests passed. JavaScript has no configured
type-checking task; source syntax checks passed. The public HTTP MCP/OAuth seam
remains the confirmed seam from issue 01. Lifecycle cases include controlled
time, restoration before the next request, changed Household, deletion cleanup,
pending-code reuse, refresh replay, and real Firestore transaction races.

Code review against starting commit `69d217000e70ad99120aaa215089f3f5f190cd45`:
Standards found one redundant membership read, which was removed and re-reviewed;
Spec found no actionable gaps. Both axes have zero remaining findings.
