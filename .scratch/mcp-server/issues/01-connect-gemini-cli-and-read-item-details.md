# 01: Connect Gemini CLI and read Item details

**What to build:** Let an existing Member connect Gemini CLI to a shared MyStuff HTTPS MCP server, sign in with Google in a browser, approve read-only access to one named Household, and read an Item by its stable ID. Serve synced cloud Inventory independently of the phone and MyStuff's app lifecycle. Establish the remote transport, standard MCP OAuth authorization, and separately identifiable App Connections needed by the remaining capabilities.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] Validate the existing Firebase HTTP-function deployment path in the existing Firebase/Google Cloud project; use a separate Cloud Run service only if the integration needs it. Allow cold starts and require no always-on instances.
- [ ] Gemini CLI completes OAuth discovery, client registration as needed, browser Google/Firebase sign-in, named-Household read-only approval, and callback/token exchange using the shared HTTPS server URL. Record a successful real-client Item-details check and reproducible connection instructions.
- [ ] Connecting requires an existing MyStuff Account and active Household Access. It neither creates a Household nor claims or grants Household Access; cancellation and ineligible identities produce no usable App Connection.
- [ ] Each App Connection belongs to the authenticated Member and the explicitly approved Household. Unauthenticated, expired, invalid, or unapproved credentials cannot read Inventory, and a client-supplied Household identifier cannot redirect access.
- [ ] Every Item-details request reuses the existing active-membership authorization checks and rejects current Account Deletion, Household deletion, and Household Owner deletion states rather than trusting approval-time membership alone.
- [ ] Item details return stable Item ID, complete Item Path from Household root through the Item, name, and Description. Sibling Items with duplicate names remain distinguishable by ID. Tags, web URLs, Photos, other Item Attachments, and unrelated Item fields are not returned.
- [ ] Foreign-Household Item IDs and malformed identifiers cannot expose Inventory outside the approved Household. Missing or unavailable Item/Inventory data produces an error rather than fabricated or incomplete successful content.
- [ ] Automated tests exercise authorization, approval binding, duplicate names, complete Item Paths, response-field restrictions, and failure behavior. A real CLI read works with MyStuff closed and the Member's phone offline, using only synced data.
- [ ] The service exposes no Inventory mutation capability and adds no application rate limits, per-Member daily quotas, or application spending caps. Existing Android Search behavior and operational limits remain unchanged.
