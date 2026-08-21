# 04 — Remotely configure and protect Description Generation

**What to build:** Description Generation uses a remotely selectable Gemini model and accepts model requests only from authorized builds, while continuing to work in local development and in the app's private outside-Google-Play distribution.

**Blocked by:** 01 — Generate a Description from an existing Item Photo.

**Status:** ready-for-agent

- [ ] The Gemini model name is read from Firebase Remote Config for each background request, with `gemini-3.7-flash` as the bundled fallback when the parameter is absent, blank, stale, or unavailable.
- [ ] Prompt text, factual-preservation rules, language behavior, output constraints, and validation remain in tested application code rather than Remote Config.
- [ ] Remote Config adapter tests verify a configured model name and every fallback condition without contacting Firebase.
- [ ] Firebase App Check is initialized before Firebase AI Logic is used.
- [ ] Local, emulator, and debug builds use the App Check debug provider.
- [ ] Privately distributed builds use the Play Integrity provider configured for distribution outside Google Play, without requiring `PLAY_RECOGNIZED` or `LICENSED` and with device integrity required.
- [ ] Firebase AI Logic baseline App Check protection is enforced; replay protection remains disabled.
- [ ] The app continues to use the Gemini Developer API free tier and adds no paid-tier, Agent Platform, server proxy, or alternate-provider path.
- [ ] No confirmation, privacy disclosure, or informational copy is shown to Members for free-tier data handling.
- [ ] A documented Firebase integration checklist covers enabling Firebase AI Logic with the Gemini Developer API, creating the Remote Config parameter, registering release signing fingerprints, registering development debug tokens, applying outside-Google-Play Play Integrity settings, and enforcing baseline protection.
- [ ] Integration verification demonstrates that an authorized debug build can complete Description Generation with the fallback and remotely configured models, and records how rejected unverified requests are confirmed through Firebase metrics or errors.
- [ ] Existing controller and workflow tests continue to pass without depending on live App Check, Remote Config, or Gemini infrastructure.
