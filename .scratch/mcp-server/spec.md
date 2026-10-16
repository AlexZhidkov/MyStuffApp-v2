# MyStuff MCP access

Design confirmed after review; ready for implementation.
Implementation and live-client verification have not started.

## Purpose and scope

Every MyStuff Member can connect an external MCP client to read their own
Household's synced Inventory. Access works while the Member's phone is offline
or MyStuff is closed; changes still waiting to sync are not available remotely.

V1 supports Search, Item details, and listing Child Item names. It is read-only.
Returned Item content is limited to Item Path, name, and Description. Tags remain
searchable but are not returned; web URLs, Photos, and other Item Attachments are
also excluded from responses. Stable Item IDs and continuation markers are
technical metadata, allowing clients to distinguish duplicate names and navigate
the tree.

## Connecting and approving access

The primary Member setup is Gemini's Connected Apps → Custom → MCP Server URL
flow. The Member supplied a screenshot showing that dialog available in their
account. Aim for configuration using only the shared MyStuff HTTPS server URL,
followed by browser Google sign-in and read-only approval on a MyStuff
authorization page.

Require an existing MyStuff Account and active Household Access. Connecting must
not create a Household or automatically grant Household Access. The approval
identifies the Household whose Inventory the external application can read.
Use standard OAuth authorization for the MCP connection, with the existing
Google/Firebase identity identifying the Member.

Gemini CLI is the first connectivity check. A real Gemini Custom app connection,
including use from Gemini web and Android, is required before V1 is complete.
Other compatible MCP clients can use the same service; compatibility with every
client is not presumed from protocol support alone.

## App Connection lifecycle

- Each App Connection belongs to a Member and one approved Household.
- Check current Household Access on every request, including subsequent pages.
  Clients cannot select an unauthorized Household through Item IDs or cursors.
- Changing Households requires fresh approval. An existing connection must not
  automatically gain access to the new Household.
- Active App Connections continue working through automatic token renewal.
  After 90 days without use, require fresh approval.
- Loss of Household Access invalidates the App Connection. Restoring access
  later still requires fresh approval, even for the same Household.
- Account Deletion also stops future access. Respect existing Account Deletion,
  Household deletion, and Household Owner deletion checks.
- Members can view and revoke individual App Connections in MyStuff's Connected
  apps screen. Revoking one stops its subsequent requests without disconnecting
  other applications or signing the Member out of MyStuff.
- Previously returned content cannot be recalled from an external client.

The authorization page must make the read-only scope and approved Household
clear. Keep public privacy information aligned with external-client access and
the existing cloud-processing disclosure decision in ADR-0008.

## Inventory capabilities

### Search

Follow the existing Android Search contract in
[the Search specification](../search/spec.md), including normalization, query
eligibility, precise literal ranking, conceptual matching, and deduplication.
Search names, Tags, and Descriptions; Item Paths and Photos are not Search inputs.

After conceptual success, return precise literal matches followed by deduplicated
conceptual matches, preserving the existing conceptual candidate limit of ten.
If conceptual Search is unavailable, return the broader literal results and
explicitly mark the response as literal-only fallback. This fallback indicator
is an MCP response addition; Android currently falls back silently.

Return Item Path, name, Description, and stable Item ID for each result. An
authorization failure or unavailable Inventory data is an error, not an empty
successful Search result or conceptual-only failure.

### Item details

Read an Item by its stable ID within the approved Household. Return its complete
Item Path, name, and Description, with its ID as technical metadata. Names and
Item Paths are not unique identifiers: sibling Items may share a name.

### Child Item listing

Return the names and stable IDs of direct Child Items in MyStuff's existing
shared order. Browsing can begin at the Household root without finding it through
Search. Clients can make further calls for Child Items to explore descendants;
one listing does not recursively flatten the tree.

### Pagination

Both Search and Child Item listing return at most **20 Items per page**, with an
explicit continuation marker when more results remain. Clients can request
subsequent pages; results must not be silently truncated. The 20-Item page limit
does not increase the existing ten-candidate conceptual Search limit.

## Hosting and usage

Use serverless hosting in the existing Firebase/Google Cloud project, with cold
starts allowed and no always-on instances required. Start by validating the
existing Firebase HTTP-function deployment path; use a separate Cloud Run
service if the integration needs it. This avoids maintaining a Linux VM.

V1 adds **no application rate limits, per-Member daily quotas, or application
spending caps**. This does not remove authentication, authorization, or the
agreed 20-Item page size. Existing Android Search behavior and its operational
limits remain unchanged.

## Release acceptance

V1 is complete only after the following pass:

1. Gemini CLI connects and performs the supported read operations.
2. A Member connects through Gemini's actual Custom app dialog, signs in with
   Google, approves the named Household, and uses the connection in Gemini web
   and Android. Use real clients rather than assuming compatibility.
3. Access continues with MyStuff closed and the Member's phone offline, using
   synced data.
4. Two-Household checks prove that Item IDs, queries, continuation markers, and
   altered requests cannot cross the approved Household boundary.
5. Search agrees with Android on representative literal, conceptual, ranking,
   deduplication, and fallback cases. An unavailable conceptual provider returns
   authorized literal results with the fallback indicator.
6. Item details contain the agreed content fields and complete Item Path;
   duplicate names remain distinguishable by ID.
7. Root-based browsing returns direct Child Items in the shared order. Both
   Search and Child Item listing correctly continue beyond 20 results when the
   result set contains more than 20 Items.
8. Revoking one App Connection stops its subsequent requests while another
   connection remains usable. Household Access removal, Account Deletion, and
   relevant Household deletion states also stop access.
9. Restored Household Access and a different Household require fresh approval.
   Connections unused for 90 days also require fresh approval; active
   connections renew automatically.

## Implementation notes

- Existing backend Search returns conceptual Item IDs only. Android currently
  owns literal matching, result merging, and Item Path construction, so exposing
  the existing callable alone does not implement this contract.
- Reuse the existing active-membership checks in
  `functions/src/member-authorization.js` rather than trusting client-supplied
  Household IDs or approval-time membership alone.
- Firebase sign-in supplies the Member identity; it is not by itself the full
  MCP OAuth authorization and App Connection lifecycle.
- Verify remote transport, OAuth discovery, client registration, browser
  callbacks, and token renewal against the actual clients during implementation.
- Relevant code includes `functions/src/search-module.js`,
  `app/src/main/java/com/azhidkov/mystuff/InventoryController.kt`, and
  `app/src/main/java/com/azhidkov/mystuff/Inventory.kt`.

## Related decisions

- [ADR-0010: serve MCP from synced cloud Inventory](../../docs/adr/0010-serve-mcp-from-synced-cloud-inventory.md)
- [ADR-0011: bind App Connections to approved Households](../../docs/adr/0011-bind-app-connections-to-approved-households.md)
- [Domain glossary](../../CONTEXT.md)
