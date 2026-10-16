# 01: Connect Gemini CLI and read Item details

**What to build:** Let an existing Member connect Gemini CLI to a shared MyStuff HTTPS MCP server, sign in with Google in a browser, approve read-only access to one named Household, and read an Item by its stable ID. Serve synced cloud Inventory independently of the phone and MyStuff's app lifecycle. Establish the remote transport, standard MCP OAuth authorization, and separately identifiable App Connections needed by the remaining capabilities.

**Blocked by:** None (can start immediately).

**Status:** implemented

- [x] Validate the existing Firebase HTTP-function deployment path in the existing Firebase/Google Cloud project; use a separate Cloud Run service only if the integration needs it. Allow cold starts and require no always-on instances.
- [x] Gemini CLI completes OAuth discovery, client registration as needed, browser Google/Firebase sign-in, named-Household read-only approval, and callback/token exchange using the shared HTTPS server URL. Record a successful real-client Item-details check and reproducible connection instructions.
- [x] Connecting requires an existing MyStuff Account and active Household Access. It neither creates a Household nor claims or grants Household Access; cancellation and ineligible identities produce no usable App Connection.
- [x] Each App Connection belongs to the authenticated Member and the explicitly approved Household. Unauthenticated, expired, invalid, or unapproved credentials cannot read Inventory, and a client-supplied Household identifier cannot redirect access.
- [x] Every Item-details request reuses the existing active-membership authorization checks and rejects current Account Deletion, Household deletion, and Household Owner deletion states rather than trusting approval-time membership alone.
- [x] Item details return stable Item ID, complete Item Path from Household root through the Item, name, and Description. Sibling Items with duplicate names remain distinguishable by ID. Tags, web URLs, Photos, other Item Attachments, and unrelated Item fields are not returned.
- [x] Foreign-Household Item IDs and malformed identifiers cannot expose Inventory outside the approved Household. Missing or unavailable Item/Inventory data produces an error rather than fabricated or incomplete successful content.
- [x] Automated tests exercise authorization, approval binding, duplicate names, complete Item Paths, response-field restrictions, and failure behavior. A real CLI read works with MyStuff closed and the Member's phone offline, using only synced data.
- [x] The service exposes no Inventory mutation capability and adds no application rate limits, per-Member daily quotas, or application spending caps. Existing Android Search behavior and operational limits remain unchanged.

## Comments

### 16 October 2026 — implementation and verification

Deployed `functions:mcp,hosting` to the existing `mystuff-ai-app` project, using
only named MCP/OAuth routes and zero minimum instances. The shared endpoint is
`https://mystuff-ai-app.web.app/mcp`. Live discovery and unauthenticated challenges
work. No separate Cloud Run service was needed.

Implemented browser Google/Firebase identity verification, named-Household preview
and approval, S256 PKCE, single-use OAuth codes, expiring opaque access tokens, and
separate App Connections. The only tool is `get_item`. Its Item Path is built in
a Firestore transaction using current shared membership/deletion checks.

The confirmed test seam is the public HTTP MCP/OAuth endpoints. All 62 backend
emulator tests passed, including eight MCP HTTP tests and real Firestore
transactions. All 50 Firestore/Storage rules tests and Android unit tests passed. Code review found and fixed the
Hosting-specific cookie requirement (`__session`); Standards has no remaining
actionable findings. Renewal/permanent membership-loss invalidation remain issue 03.

At this initial check, the real-client requirements remained unchecked. Google browser sign-in completed,
but Gemini CLI 0.50.0 rejected its own Code Assist model login as no longer
supported for individuals, before reaching MyStuff approval. API-key authentication
or another working Gemini CLI model login was needed to finish the real read with
the phone offline. At that point, the issue was not yet complete.

Connection/deployment instructions and the verification record are in
[`docs/mcp-server.md`](../../../docs/mcp-server.md).

### 16 October 2026 — real-client acceptance completed

Gemini CLI 0.64.0, with locally configured API-key model authentication,
successfully completed `/mcp auth mystuff` against the shared HTTPS URL.
The Member signed in with Google, approved read-only access to the named
Household, and received the successful browser callback. The CLI confirmed
successful authentication, reloaded its tools, and showed the server as
`Ready (1 tool) (OAuth)`.

The Member then explicitly confirmed that MyStuff was closed and their phone
was offline. The real CLI successfully called `mcp_mystuff_get_item` for synced
Item `0EIdCsUofAOEy4r5xgar`, returning only `id`, `path`, `name`, and
`description`, with five names in the Item Path. No private Item text or
credentials are recorded here. All issue 01 acceptance criteria are complete.
