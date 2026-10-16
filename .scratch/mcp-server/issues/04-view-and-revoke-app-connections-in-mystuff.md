# 04: View and revoke App Connections in MyStuff

**What to build:** Give every Member a Connected apps screen in MyStuff where they can view their own App Connections and revoke an individual external application's access. Explain the read-only scope, approved Household, and limits of revocation, and align public privacy information with external-client access and the existing cloud-processing disclosure decision.

**Blocked by:** 01: Connect Gemini CLI and read Item details.

**Status:** ready-for-agent

- [ ] A Member can open Connected apps and distinguish their individual App Connections, including the external application and approved Household. Empty, loading, and failed-listing states are understandable.
- [ ] A Member can revoke one App Connection and see the result. Failed revocation is not presented as successful disconnection.
- [ ] Once revocation succeeds, subsequent Inventory requests and token renewal for that App Connection fail, including requests using previously issued credentials. Revocation does not depend on access-token expiry.
- [ ] A second App Connection remains usable after the first is revoked, and the Member stays signed in to MyStuff. Members cannot list or revoke another Member's App Connections.
- [ ] The approval and connection-management information consistently describe read-only access to the approved Household and explain that previously returned content cannot be recalled from the external client.
- [ ] Update public privacy information to describe external-client Inventory access while preserving the existing Firebase/Gemini cloud-processing disclosures and readily accessible in-app privacy link.
- [ ] Automated backend and Android coverage verifies listing authorization, individual revocation, error handling, and independent connections. A real-client check proves the revoked connection stops while another remains usable.
