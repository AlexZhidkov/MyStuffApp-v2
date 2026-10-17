# 02: Connect through Gemini's Custom app flow

**What to build:** Let a Member configure MyStuff through Gemini's actual Connected Apps → Custom → MCP Server URL dialog, approve their named Household after browser Google sign-in, and read Item details from Gemini web and Android. Resolve compatibility against the real clients early, while retaining the working Gemini CLI connection.

**Blocked by:** 01: Connect Gemini CLI and read Item details.

**Status:** needs-info

- [ ] A Member enters the shared MyStuff HTTPS server URL in the actual Gemini Custom app dialog and completes browser Google sign-in and read-only approval for the named Household. Aim for setup requiring only that URL; record any actual additional setup requirements rather than assuming compatibility.
- [ ] Verify and, where necessary, correct remote transport, OAuth discovery, client registration, browser callbacks, and token exchange against the actual Gemini flow.
- [ ] The resulting connection successfully reads Item details in both Gemini web and Android with the agreed response fields and stable IDs. Protocol tests or Gemini CLI success alone do not satisfy this criterion.
- [ ] Ineligible identities and denied approval cannot establish Inventory access. Client compatibility changes preserve the approved-Household boundary and existing MyStuff Account requirement.
- [ ] Gemini CLI still connects and reads Item details after the compatibility changes. Add regression coverage for compatibility fixes that can be reproduced automatically.
- [ ] Record the real-client setup steps, client/platform details, results, and any unresolved limitations. Do not mark this ticket complete while either Gemini web or Android remains unverified.

## Comments

### 17 October 2026 — server preflight passed; real-client evidence needed

Checked the existing shared URL, `https://mystuff-ai-app.web.app/mcp`.
Live authorization-server and protected-resource discovery returned HTTP 200;
unauthenticated MCP initialize returned HTTP 401 with the metadata URL and
`inventory:read` scope. The existing public HTTP tests passed (seven passed,
one emulator-only test skipped in the standalone run), and the full backend
emulator suite subsequently passed, including the Firestore-backed MCP test.
Node 22 syntax checks passed for the MCP server, OAuth, Inventory, and browser
authorization modules. There is no JavaScript type-checking task in the repo.

No server compatibility defect has been reproduced, so no server code or
deployment changed. The Gemini CLI acceptance from issue 01 still describes
the deployed implementation; a new real CLI read was not performed in this
session.

The browser automation connection at `127.0.0.1:9223` was unavailable, and the
agent had no connected Android client. Gemini web/Android client versions,
actual dialog fields, Google sign-in/approval, callback/token exchange, and
Item reads are all still unverified. Requested a real Custom app attempt from
the Member; need its outcome (including any error and the stage where it occurs)
to identify compatibility changes. No acceptance checkbox is complete on the
basis of these preflight checks.

Added a verification procedure and Google's documented eligibility requirements
to [`docs/mcp-server.md`](../../../docs/mcp-server.md#connect-gemini-custom-apps-verification-pending).
The actual setup's additional requirements remain unknown. Keep this issue open
until real Gemini web and Android Item reads pass, adding HTTP regression tests
for any reproducible compatibility fixes.

The Member reported `invalid_token` / `Missing Authorization header` and
confirmed it came from opening the server URL directly in the browser address
bar. That is the expected unauthenticated response, not a failed Custom app
connection. Requested the result of entering the URL in Gemini's actual dialog;
that attempt is still pending.

### 17 October 2026 — actual Custom app discovery and Gemini web read

The Member supplied a screenshot of Gemini's **Save your custom app** dialog,
with the application named `Mystuff` and `get_item` listed as an available action.
A subsequent screenshot shows a web chat addressed to `@Mystuff` successfully
reading synced Item `0EIdCsUofAOEy4r5xgar`. Gemini displayed its stable ID,
five-level Item Path, name, and empty Description. No private Item text is
recorded here. The existing deployed server served this read without changes.

Requested the same read in the real Gemini Android app, confirmation of Google
sign-in and read-only approval naming the Household, and client versions where
available. Actual additional setup fields have not yet been confirmed. The
screenshots establish real Custom app discovery and a web read, but do not show
individual OAuth requests or prove the Android result. Keep the combined
acceptance criteria unchecked and this issue open pending the remaining evidence.
