# MyStuff MCP: Inventory reads

The shared server URL is `https://mystuff-ai-app.web.app/mcp`.
Issue 05's browsing changes and the issue 03/04 lifecycle changes have passed
local verification and require deployment. The live-client acceptance records
below describe the earlier issue 01/02 deployment.
It serves synced Firestore Inventory independently of Android. Unsynced phone
changes are unavailable. The `get_item` tool takes the input
`{"itemId":"<stable Item ID>"}`. It returns `id`, `path` (the ordered names from
Household root through the Item), `name`, and `description`. A deliberately empty
Description is `null`; missing or corrupt Item/ancestor data is a tool error.

The `list_children` tool starts at the approved Household root with `{}`. It
returns `parentItemId`, `items` (each containing only `id` and `name`), and
`nextCursor` (a continuation marker or `null` when complete). To explore the next
level, pass `{"parentItemId":"<returned Child Item ID>"}`. Each call returns
direct Child Items only, with at most 20 Items. Continue with the same
`parentItemId` and `{"cursor":"<nextCursor>"}`; root requests can keep omitting
`parentItemId` on subsequent pages.

Order matches Android's `Inventory.childrenOf`: siblings start in Firestore's
[default ascending document-ID order](https://firebase.google.com/docs/firestore/query-data/order-limit-data),
then sort by `displayOrder`, substituting the sibling's original index for a
missing/null position and breaking ties by that index. The server reads all
direct siblings to preserve this fallback behavior, then returns one page.
Pagination guarantees a complete listing for a fixed Inventory; restart from the
first page if Items are added, deleted, moved, or reordered during browsing.

Continuation markers are random opaque values. Their SHA-256-keyed records in
`mcpChildCursors` hold the server-controlled offset and bind it to one App
Connection, approved Household, and Parent Item. Client-supplied page sizes and
offsets cannot change the 20-Item limit. Every page rechecks current connection
and Household authorization and reads the Parent Item's ancestry and returned
Items' deletion states in the same Firestore transaction. Invalid markers,
missing Items, and unavailable Inventory return errors; a valid empty list
succeeds. Listings exclude Descriptions, Tags, URLs, Photos, and Attachments.

## Hosting and configuration

The existing Firebase project is `mystuff-ai-app`, on Blaze billing. Inspection
on 16 October 2026 confirmed an existing default Hosting site at
`https://mystuff-ai-app.web.app` and second-generation Functions in
`australia-southeast1`, including an existing HTTP function. `firebase.json`
routes the named MCP/OAuth endpoints on this Hosting site to the new `mcp` HTTP function. JSON-only,
stateless Streamable HTTP needs neither persistent SSE nor sticky sessions.
The function has `minInstances: 0`; it adds no rate limiter or spending cap.
The SDK's default OAuth endpoint rate limiters are explicitly disabled.
No separate Cloud Run service is needed for this transport.

The Firebase Web app `MyStuff MCP authorization` is registered in the existing
project with app ID `1:37308974986:web:47ed2a3eca7816572e6a73` for browser Google sign-in.
Set these public Functions parameters in `functions/.env.mystuff-ai-app`:

```dotenv
MCP_ORIGIN=https://mystuff-ai-app.web.app
MCP_FIREBASE_API_KEY=<Firebase Web app apiKey>
MCP_FIREBASE_APP_ID=<Firebase Web app appId>
```

These identify the Firebase app; they are not OAuth client secrets. Never put
Member tokens, Google credentials, or service account keys in this file. Make
sure Google sign-in is enabled in Firebase Authentication and the Hosting domain
is in Authentication's authorized domains. The sign-in helper uses
`mystuff-ai-app.firebaseapp.com`. Keep any browser API-key restrictions compatible
with the new Hosting origin.

Install dependencies with Node 22 and deploy only the new HTTP function and
Hosting configuration:

```bash
npm ci --prefix functions
firebase deploy --project mystuff-ai-app --only functions:mcp,hosting
```

The privacy policy source is `site/index.html`; its existing GitHub Pages
publishing workflow remains a separate deployment.

## Connect Gemini CLI

Gemini CLI 0.64.0 completed the connection check. Authenticate Gemini CLI itself
before connecting MyStuff. If its Google/Code Assist login is unavailable, select
**Use Gemini API key** in `/auth` and enter a Google AI Studio key locally.
MyStuff then uses its own browser Google sign-in and Household approval.
Use the shared URL:

```bash
gemini mcp add --transport http mystuff https://mystuff-ai-app.web.app/mcp
gemini
```

In Gemini CLI run `/mcp auth mystuff`. Follow the browser Google sign-in, review
the explicitly named Household and application, and approve read-only access.
Return to the CLI and use `/mcp` to confirm that `get_item` is available. Ask it:

```text
Use MyStuff get_item to read Item <stable Item ID>. Show its ID, complete Item Path,
name, and Description.
```

Alternatively, add `"mystuff": {"httpUrl": "https://mystuff-ai-app.web.app/mcp"}`
under `mcpServers` in Gemini CLI settings. OAuth discovery and dynamic client
registration supply the remaining setup; no Member token or client secret needs
to be copied by hand. The CLI must be able to receive the localhost browser
callback. Registered loopback callbacks permit an ephemeral port.

## Connect Gemini Custom apps

**Real Gemini web and Android acceptance passed on 17 October 2026.** The Member
supplied screenshots of Custom app tool discovery and a successful Gemini web
Item read, then confirmed the same read worked in the real Gemini Android app
and that setup showed Google sign-in and read-only approval naming the Household.

[Google's Custom app guide](https://support.google.com/gemini/answer/17209137)
currently describes setup on Gemini web and subsequent use on web and mobile.
As checked on 17 October 2026, it lists a personal Google Account, age 18 or over,
US availability, English, and Keep Activity enabled as prerequisites. Check the
actual account's available dialog; these documented restrictions do not establish
whether this Member can connect. MyStuff separately requires an existing
MyStuff Account and active Household Access.

1. On Gemini web, open Settings → Connected Apps (possibly under Personal
   Intelligence), then Custom apps → Add a custom app.
2. Enter `https://mystuff-ai-app.web.app/mcp` and select Next. MyStuff advertises
   dynamic client registration; no additional credentials were reported as needed
   in the successful setup.
   Enter the URL in Gemini's dialog, rather than the browser address bar. Opening
   it directly returns `invalid_token` / `Missing Authorization header`, because
   the MCP endpoint requires a token obtained through the connection flow.
3. Complete browser Google sign-in with the identity already used for MyStuff.
   Review the application and named Household, then approve read-only access.
   Save the custom app when Gemini lists `get_item`, then select **Connect**.
4. In a new web chat, select `@Mystuff` (or the name chosen during setup) and use `get_item`
   for a known synced stable Item ID. Verify the actual tool result contains only
   `id`, `path`, `name`, and `description`, including the complete Item Path.
5. In the real Gemini Android app, using the same Google Account, select the
   connected custom app and repeat the read for the same ID. Confirm the same
   four fields and complete Item Path. Browser mobile emulation is insufficient.

On 17 October 2026, live HTTPS checks confirmed authorization-server metadata and
protected-resource metadata returned 200, and an unauthenticated MCP initialize
returned 401 with the resource metadata URL and `inventory:read` challenge.
The browser automation endpoint at `127.0.0.1:9223` was unavailable, and no
connected Android client was available to the agent. The Member subsequently
supplied screenshots from the actual Gemini flow: **Save your custom app** listed
`Mystuff` with `get_item` as its available action, and a web chat addressed to
`@Mystuff` returned Item `0EIdCsUofAOEy4r5xgar`, a five-level Item Path, its name,
and an empty Description. No private Item text is copied here.

The Android read and sign-in/Household approval are verified by the Member's
explicit confirmation. Exact browser, Gemini Android version, device, and OS
were not supplied. Individual OAuth requests were not captured, and no additional
setup requirements were reported.

The existing public HTTP suite covers denied approval, ineligible identities,
and the approved-Household boundary; the full backend emulator suite passed in
this session. These negative cases were not repeated in the live Gemini clients.
No server compatibility changes or new deployment were needed. The successful
Gemini CLI check from 16 October used this same deployed implementation; no fresh
CLI read was performed during issue 02. No compatibility-fix regression tests
were needed because the server implementation did not change.

## Authorization and storage

Discovery is at `/.well-known/oauth-protected-resource/mcp` and
`/.well-known/oauth-authorization-server`. An unauthenticated `/mcp` request
returns a 401 challenge pointing to the resource metadata. `/register`,
`/authorize`, and `/token` implement the authorization-code flow with S256 PKCE.
Callbacks include `state` and the issuer (`iss`). Browser approvals require a
short-lived, HttpOnly cookie and same-origin JSON requests. Preview identifies
the existing Member and named Household; approval must match that preview.
Neither operation invokes Household creation or Household Access claiming.

Admin-only Firestore collections:

- `mcpClients`: registered OAuth client metadata.
- `mcpApprovals`: browser-bound approval requests, valid for ten minutes.
- `mcpCodes`: single-use approved codes, valid for one minute.
- `mcpAccessTokens`: SHA-256 keyed opaque tokens, valid for one hour.
- `mcpRefreshTokens`: SHA-256 keyed rotating renewal credentials, bound to one
  App Connection and this resource. Used records are retained to detect replay.
- `appConnections`: separately identifiable Member/Household approvals, with
  client identity, timestamps, read scope, authorization generations, and `revokedAt`.
- `mcpMemberEpochs`, `mcpHouseholdEpochs`: permanent authorization generations.
  Access removal changes the affected Member's generation; Account Deletion
  changes the Member's generation, and Household/Owner deletion changes the
  Household's generation. These records survive membership and deletion-job cleanup.

Existing Firestore rules deny access to these unmatched collections. Raw access
tokens, refresh tokens, authorization codes, and approval cookie secrets are not stored.
Expired records are rejected regardless of whether Firestore has removed them.

Every MCP request checks the current App Connection and the existing shared
active-membership authorization. Item reads repeat the membership and deletion
checks in the same Firestore transaction as ancestry reads. Item IDs never select
a Household; both Item and ancestor reads stay inside the approved Household.
Malformed IDs, cycles, missing ancestors, foreign Items, pending Item deletion,
and unavailable Inventory fail closed. Responses contain only the four fields
listed above, with no Tags, web URLs, Photos, or other Item Attachments.

Authorization-code exchange now issues access and refresh tokens, and discovery
advertises both grant types. Renewal checks current authorization in the same
transaction as credential rotation. A used refresh token cannot renew again;
replaying it invalidates its App Connection, including newly issued credentials,
following [OAuth security guidance](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14.2).
Other App Connections and MyStuff sign-in sessions are unaffected.

At exactly 90 days without an authenticated MCP request, both reads and renewal
require fresh browser approval. Renewal alone does not count as use. Every MCP
request, including requests with continuation markers, uses shared authorization;
Item ancestry reads repeat the same lifecycle check inside their transaction.
Access removal and deletion invalidate approval atomically when they begin, even
if membership returns or deletion jobs disappear before the next MCP request.
Generation records must not be removed by cleanup or given an expiry policy.
New approval records the current generations and the explicitly named Household;
old credentials cannot follow a Member into a different Household. Connections
created before generation fields existed represent only the original generation.

### Connected apps

Every signed-in Member can open **Connected apps** from MyStuff's menu, including
Members without current Household Access. Each row identifies the external
application, approved Household, approval and last-use times, and a connection
identifier suffix to distinguish repeated approvals. Revoked approvals are marked
as revoked when the list is refreshed. New approvals store the Household's name;
older approvals fall back to the current Household name or an unavailable label.

The `listAppConnections` and `revokeAppConnection` Firebase callables use the
Member's Firebase identity, independently of external OAuth credentials. Listing
queries only that Member's records; revocation checks ownership and writes
`revokedAt` in a transaction. Missing and foreign IDs return the same not-found
response. Repeating a successful revocation is safe. Storage errors fail visibly
rather than returning an empty list or a successful disconnection.

Every subsequent MCP request, Inventory read, code exchange, and token renewal
checks the revoked record, including requests using previously issued credentials.
Independent App Connections and MyStuff sign-in remain usable. Revocation cannot
recall content already returned to an external application. The approval page,
Connected apps screen, and public privacy policy explain this limit and the
read-only approved-Household scope.

### Deploy the lifecycle change

Deploy the invalidation writers together with renewal support. Deploying only
`mcp` leaves access removal and deletion unable to invalidate former approval:

```bash
firebase deploy --project mystuff-ai-app --only functions:removeHouseholdAccess,functions:requestAccountDeletion,functions:requestHouseholdDeletion,functions:manuallyDeleteAccount,functions:mcp
```

Deploy the four removal/deletion functions before enabling the new `mcp` function
if deploying in separate steps. The lifecycle change needs no rules, Hosting,
Android, or schema backfill.
Issue 04 additionally requires the Android update and these two callables:

```bash
firebase deploy --project mystuff-ai-app --only functions:listAppConnections,functions:revokeAppConnection
```

Deploy `functions:mcp` for the approval-page copy and stored Household name.
Publish `site/index.html` through the existing GitHub Pages workflow for the
privacy update. No new Hosting route, Firestore index, or rules change is needed.
Existing access-only credentials cannot acquire a refresh token; those clients
need one fresh approval to obtain renewal credentials.

## Verification record

### 17 October 2026 — root browsing and Child Item pagination

The installed Gemini CLI 0.64.0 discovered `get_item` and `list_children` against
an isolated local HTTP server using real Firestore-emulator transactions. Its
actual `gemini mcp list` command reported the connection connected. Its installed
OAuth provider and Streamable HTTP transport then read all 41 synced direct
Child Items at the root in pages of **20, 20, and 1**, preserving stable IDs and
shared order, and followed a returned ID into nested Child Items. Removing
Household Access stopped the next continuation request.

This check used synthetic Inventory stored in the emulator, isolated temporary
CLI settings, and synthetic approvals. It did not deploy issue 05 or use live
Member Inventory. The HTTP suite also verifies duplicate names, mixed missing/
null/tied ordering, 0/1/19/20/21/40/41 boundaries, marker tampering, foreign
Households and connections, mismatched Parents, revocation, all relevant
deletion states, and corrupt/unavailable data. All 45 targeted HTTP tests passed.

Reproduce with Node 22 and the installed bundle described below:

```bash
GEMINI_CLI_BUNDLE=/path/to/gemini-cli/bundle/chunk-exporting-createTransport.js \
  firebase emulators:exec --only firestore --project demo-mystuff-search \
  'node --test functions/test/mcp-http.test.js'
```

JavaScript has no configured type-checking task; the changed source and test
files pass Node's syntax checks. Final validation passed all **102 backend
tests** with all emulators and installed CLI checks enabled (no skips), all
**50 Firestore/Storage rules tests**, and the **210 Android unit tests** and
Kotlin compilation (`./gradlew test`, up-to-date). Standards and Spec reviews
against starting commit `24dca877760b7bd0ae4df20a90298b7445fd5daa` found no
actionable issues on either axis.


### 17 October 2026 — individual connection management

The actual installed Gemini CLI 0.64.0 executable (`gemini mcp list`) connected
two independent approvals against the local Functions emulator. After revoking
one through the authenticated `revokeAppConnection` callable, the CLI reported
that connection disconnected while the second remained connected. Both used
the same Member and approved Household. The HTTP checks additionally proved
that the revoked connection's existing access and refresh credentials fail,
while the second connection still reads and renews and the Member remains
signed in. This used synthetic data and isolated temporary CLI configuration;
no production deployment or live Member revocation was performed.

The CLI check is enabled by `GEMINI_CLI_BUNDLE`, using the same installed bundle
as the renewal check below. With the Firebase emulators running:

```bash
FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099 \
  node --test --test-name-pattern='Connected apps|revoking one App Connection|unavailable connection storage' functions/test/functions-emulator.test.js
```

The new HTTP tests also cover identity-scoped listing, legacy approvals, revoked
status, unauthenticated access, foreign and malformed connection IDs, idempotent
revocation, and storage outages. Android controller tests cover loading, failed
listing and retry, successful empty listing, failed revocation and retry, and
preserving independent connections. The test seams were confirmed for issue 04.

All 75 backend tests passed with the full emulators and both installed Gemini CLI
checks enabled. All 210 Android unit tests, Android lint, and all 50
Firestore/Storage rules tests passed. Kotlin compilation and JavaScript syntax
checks passed; no JavaScript type-checking task is configured.

### 17 October 2026 — renewal and permanent invalidation

Gemini CLI 0.64.0 automatically renewed expired credentials against the local
HTTP server. Its installed OAuth provider and Streamable HTTP transport
initialized the MCP connection, listed `get_item`, and read the fixture Item.
The actual `gemini mcp list` executable then renewed again and reported the
server connected. Both checks verified rotated access and refresh credentials
were saved without opening another approval page.

This check used synthetic Firebase identity and Inventory data, a controlled
server clock, and an isolated temporary Gemini configuration and token store.
It did not use the Member's credentials, deploy changes, or establish renewal
in Gemini's hosted Custom app. The earlier real Google sign-in and hosted
Gemini checks below still describe the issue 01/02 deployment.

The compatibility test is opt-in because Gemini CLI is not a project dependency.
Set `GEMINI_CLI_BUNDLE` to the installed CLI bundle exporting `createTransport`
and `MCPOAuthTokenStorage` (alongside `gemini.js`), then run:

```bash
node --test --test-name-pattern='installed Gemini CLI' functions/test/mcp-http.test.js
```

The HTTP lifecycle suite also covers controlled-clock active use and exact
90-day inactivity, named approval for a different Household, restoration before
the next request, pending-code invalidation, legacy issue 01 approvals, all three
deletion states and cleanup, refresh replay, unrelated Members, and independent
connections. A Firestore-emulator case checks real transactional code exchange,
renewal, removal/restoration, deletion cleanup, and concurrent refresh replay.
That transaction fixture uses a named emulator database so default-database
deletion workers cannot race with its controlled cleanup/restoration sequence.

All 72 backend tests passed under Node 22 with Auth, Firestore, Storage, and
Functions emulators and the optional installed Gemini CLI check enabled. All 50
Firestore/Storage rules tests and Android unit tests passed. No JavaScript type-checking task exists; source syntax checks
passed. Standards and Spec reviews have no remaining findings; the Standards
review's redundant membership-read finding was fixed before final validation.

On 16 October 2026 the scoped `functions:mcp,hosting` deployment succeeded in
`mystuff-ai-app`. The live Hosting channel had no previous release. An initial
catch-all Hosting deployment was rejected by automatic approval review; the
accepted deployment uses only the seven named MCP/OAuth route patterns.
Live HTTPS checks returned both discovery documents with status 200 and an
unauthenticated `/mcp` response with status 401 and the resource metadata challenge.
Browser-binding cookies use `__session`, the cookie Firebase Hosting forwards.

Validation completed:

```bash
node --test functions/test/mcp-http.test.js
npm run test:functions:emulator
npm run test:rules
./gradlew test
```

The complete backend emulator suite passed 62 tests, including eight HTTP
MCP/OAuth tests. Those include real Firestore transactions for approval, concurrent
single-use code exchange, and complete Item Path reads. The other HTTP cases use
an Admin SDK storage fake and a Firebase identity verifier fake. Android unit tests
passed, and all 50 Firestore/Storage rules tests passed. No JavaScript type-checking task exists; syntax/import checks passed on
Node 22.

**Real Gemini CLI acceptance passed on 16 October 2026.** Gemini CLI 0.64.0 ran
with a temporary project containing only the shared server URL and locally
configured API-key authentication for its model. `/mcp auth mystuff` completed
OAuth discovery, dynamic client registration, browser Google/Firebase sign-in,
named-Household read-only approval, localhost callback, and token exchange.
The CLI reported successful authentication and tool reload; `/mcp` showed
`mystuff - Ready (1 tool) (OAuth)` with `mcp_mystuff_get_item`.

After the Member explicitly confirmed that MyStuff was closed and their phone
was offline, the CLI successfully called `get_item` for synced Item
`0EIdCsUofAOEy4r5xgar`. The response contained `id`, `path`, `name`, and
`description`, with five names in the complete Item Path. No private Item text
or credentials are included in this record. This completes issue 01's real-client
check. Issue 02's Gemini web/Android acceptance is recorded above; the remaining
capabilities belong to issues 03–08.

An earlier attempt with Gemini CLI 0.50.0 stopped at the CLI's own Code Assist
model login, before MyStuff approval. Its sign-in rejection was resolved for
this check by using Gemini CLI's supported
[API-key authentication](https://geminicli.com/docs/get-started/authentication/).
Foreign-Household/malformed IDs, cancellation, ineligible identities, invalid or
expired credentials, and current deletion/access-removal states are covered by
the automated HTTP suite; those were not simulated against the Member's live data.

Protocol references:

- [MCP authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
- [MCP transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [Gemini CLI MCP and OAuth setup](https://geminicli.com/docs/tools/mcp-server/)
- [Firebase Hosting HTTP-function rewrites](https://firebase.google.com/docs/hosting/functions)
