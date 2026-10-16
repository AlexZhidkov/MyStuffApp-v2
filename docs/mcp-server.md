# MyStuff MCP: Item details

The shared server URL is `https://mystuff-ai-app.web.app/mcp`.
It serves synced Firestore Inventory independently of Android. Unsynced phone
changes are unavailable. The only tool in issue 01 is `get_item`, with the input
`{"itemId":"<stable Item ID>"}`. It returns `id`, `path` (the ordered names from
Household root through the Item), `name`, and `description`. A deliberately empty
Description is `null`; missing or corrupt Item/ancestor data is a tool error.

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
- `appConnections`: separately identifiable Member/Household approvals, with
  client identity, timestamps, read scope, and `revokedAt`.

Existing Firestore rules deny access to these unmatched collections. Raw access
tokens, authorization codes, and approval cookie secrets are not stored.
Expired records are rejected regardless of whether Firestore has removed them.

Every MCP request checks the current App Connection and the existing shared
active-membership authorization. Item reads repeat the membership and deletion
checks in the same Firestore transaction as ancestry reads. Item IDs never select
a Household; both Item and ancestor reads stay inside the approved Household.
Malformed IDs, cycles, missing ancestors, foreign Items, pending Item deletion,
and unavailable Inventory fail closed. Responses contain only the four fields
listed above, with no Tags, web URLs, Photos, or other Item Attachments.

Issue 03 adds refresh tokens, 90-day inactivity, and permanent invalidation after
membership removal/restoration. This issue issues no refresh token and advertises
only authorization-code grants. Issue 04 adds Member-facing connection management.
Until then, an operator can revoke one connection by setting its `revokedAt` to a
non-null timestamp; this stops subsequent requests without signing anyone out.

## Verification record

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
check; Gemini web/Android and the remaining capabilities belong to later issues.

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
