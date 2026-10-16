# 02: Connect through Gemini's Custom app flow

**What to build:** Let a Member configure MyStuff through Gemini's actual Connected Apps → Custom → MCP Server URL dialog, approve their named Household after browser Google sign-in, and read Item details from Gemini web and Android. Resolve compatibility against the real clients early, while retaining the working Gemini CLI connection.

**Blocked by:** 01: Connect Gemini CLI and read Item details.

**Status:** ready-for-agent

- [ ] A Member enters the shared MyStuff HTTPS server URL in the actual Gemini Custom app dialog and completes browser Google sign-in and read-only approval for the named Household. Aim for setup requiring only that URL; record any actual additional setup requirements rather than assuming compatibility.
- [ ] Verify and, where necessary, correct remote transport, OAuth discovery, client registration, browser callbacks, and token exchange against the actual Gemini flow.
- [ ] The resulting connection successfully reads Item details in both Gemini web and Android with the agreed response fields and stable IDs. Protocol tests or Gemini CLI success alone do not satisfy this criterion.
- [ ] Ineligible identities and denied approval cannot establish Inventory access. Client compatibility changes preserve the approved-Household boundary and existing MyStuff Account requirement.
- [ ] Gemini CLI still connects and reads Item details after the compatibility changes. Add regression coverage for compatibility fixes that can be reproduced automatically.
- [ ] Record the real-client setup steps, client/platform details, results, and any unresolved limitations. Do not mark this ticket complete while either Gemini web or Android remains unverified.
