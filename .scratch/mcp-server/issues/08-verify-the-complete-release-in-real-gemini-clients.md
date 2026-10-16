# 08: Verify the complete release in real Gemini clients

**What to build:** Establish reproducible release evidence that the complete read-only MyStuff MCP service works in Gemini CLI and through the actual Gemini Custom app connection in Gemini web and Android. Exercise the approved-Household boundary and App Connection lifecycle against the integrated service, resolve failures, and leave a clear release verdict.

**Blocked by:** 02: Connect through Gemini's Custom app flow; 03: Renew active App Connections and require fresh approval when needed; 04: View and revoke App Connections in MyStuff; 05: Browse Child Items from the Household root; 07: Add conceptual Search with Android-equivalent merging.

**Status:** ready-for-agent

- [ ] Record deployment configuration, client/platform details, setup instructions, executed checks, and outcomes for Gemini CLI plus the actual Gemini Custom app flow in Gemini web and Android. Each client performs Search, Item details, and Child Item listing; protocol support alone is not acceptance evidence.
- [ ] A Member connects using the shared HTTPS URL, signs in with Google, and approves read-only access to the named Household. Ineligible identities and denied approval cannot read Inventory or gain Household Access.
- [ ] Supported reads continue with MyStuff closed and the Member's phone offline. Evidence distinguishes synced Inventory from changes still waiting to sync.
- [ ] Two-Household checks show that Item IDs, queries, continuation markers, and altered requests cannot cross the approved Household boundary. Check authorization again after a first page has already been returned.
- [ ] Representative Search cases agree with Android on normalization, eligibility, literal and conceptual matching, ranking, and deduplication. A controlled conceptual-provider failure returns authorized broader literal results with the fallback indicator; unavailable Inventory and authorization failures remain errors.
- [ ] Item details contain only the agreed fields and the complete Item Path, with duplicate names distinguishable by stable ID. Root browsing returns direct Child Items in shared order; both Search and Child Item listing continue correctly beyond 20 Items without silently truncating results.
- [ ] Revoking one App Connection stops its next read, continuation, and renewal while another connection remains usable and MyStuff stays signed in. Household Access removal, Account Deletion, Household deletion, and Household Owner deletion states also stop access.
- [ ] Restored Household Access, including restoration before the next request, and a different Household require fresh approval. Active connections renew in a real client, and controlled-clock lifecycle checks prove that 90 days without use requires fresh approval.
- [ ] Public privacy information and the authorization page consistently explain external-client read-only access and the approved Household. Verify response-field restrictions and the absence of Inventory mutation capabilities.
- [ ] Resolve failures found during verification and add focused regression coverage where reproducible. Do not declare V1 complete while required real-client evidence is missing; record unresolved blockers and the final release verdict explicitly.
