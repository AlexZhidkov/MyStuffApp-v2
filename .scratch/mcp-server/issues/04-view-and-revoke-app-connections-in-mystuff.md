# 04: View and revoke App Connections in MyStuff

**What to build:** Give every Member a Connected apps screen in MyStuff where they can view their own App Connections and revoke an individual external application's access. Explain the read-only scope, approved Household, and limits of revocation, and align public privacy information with external-client access and the existing cloud-processing disclosure decision.

**Blocked by:** 01: Connect Gemini CLI and read Item details.

**Status:** implemented

- [x] A Member can open Connected apps and distinguish their individual App Connections, including the external application and approved Household. Empty, loading, and failed-listing states are understandable.
- [x] A Member can revoke one App Connection and see the result. Failed revocation is not presented as successful disconnection.
- [x] Once revocation succeeds, subsequent Inventory requests and token renewal for that App Connection fail, including requests using previously issued credentials. Revocation does not depend on access-token expiry.
- [x] A second App Connection remains usable after the first is revoked, and the Member stays signed in to MyStuff. Members cannot list or revoke another Member's App Connections.
- [x] The approval and connection-management information consistently describe read-only access to the approved Household and explain that previously returned content cannot be recalled from the external client.
- [x] Update public privacy information to describe external-client Inventory access while preserving the existing Firebase/Gemini cloud-processing disclosures and readily accessible in-app privacy link.
- [x] Automated backend and Android coverage verifies listing authorization, individual revocation, error handling, and independent connections. A real-client check proves the revoked connection stops while another remains usable.

## Comments

### 17 October 2026 — implemented and verified locally

Added Connected apps to every signed-in Member's menu, including Members without
current Household Access. Connections identify their application, approved
Household, approval and last-use times, and an identifier suffix. Loading,
empty, failed-listing, and failed-revocation states remain distinct; revocation
is confirmed by the server before success is shown. Opening the screen preserves
the underlying Inventory state.

Authenticated Firebase callables list only the caller's connections and revoke
one owned connection transactionally. Existing shared authorization rejects
reads, code exchange, and renewal immediately after revocation, including use of
previously issued credentials. Foreign connection IDs cannot be listed or
revoked. Approval copy, connection-management information, and the public privacy
policy explain the read-only approved-Household scope and that returned content
cannot be recalled. Firebase/Gemini processing disclosures and the in-app privacy
link remain available.

The actual installed Gemini CLI 0.64.0 connected two approvals against the local
Functions emulator. After the Member-facing revoke callable succeeded for one,
`gemini mcp list` showed that connection disconnected and the other connected.
HTTP checks also proved the revoked credentials could neither read nor renew,
while the independent connection could do both and MyStuff sign-in remained
usable. This used synthetic data and isolated temporary CLI credentials; no
production deployment or live Member revocation was performed.

The user confirmed the HTTP MCP/OAuth and Android controller test seams. All 75
backend tests passed with the full emulators and both installed Gemini CLI checks
enabled. All 210 Android unit tests, Android lint, and all 50 Firestore/Storage
rules tests passed. Kotlin compilation and JavaScript syntax checks passed;
JavaScript has no configured type-checking task. The rules suite uses its own
`mystuff-ai-app` emulator project; running Storage rules against the backend's
`demo-mystuff-search` emulator does not resolve their cross-service checks.

Code review against starting commit `f7634b8c92f1f42519a4af8faa097916ac96a5b9`:
Spec found no gaps. Standards identified the pending issue lifecycle update and
duplicated Gemini CLI test setup; both were addressed before completion.
Deployment steps and the full verification record are in
[`docs/mcp-server.md`](../../../docs/mcp-server.md).
