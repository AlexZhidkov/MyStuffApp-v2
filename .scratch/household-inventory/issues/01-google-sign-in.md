# 01 — Member signs in and out with Google

**What to build:** A runnable Android app where a person can authenticate with their Google Account using Firebase Authentication, sign out, and reach the appropriate Household entry state.

**What to use:** Google Material Design specification, Firebase `mystuff-ai-app` project for the backend.

**Device to target:** Google Pixel 8 Pro running Android 17

**Status:** ready-for-agent

- [ ] The Android app builds, launches, and connects to the configured Firebase backend.
- [ ] A person can sign in using Google as the only authentication provider.
- [ ] A signed-in person without a Household membership sees options to create a Household or accept an invitation.
- [ ] Signing out removes access to authenticated app content and returns to the sign-in screen.
- [ ] Authentication failures are communicated without leaving the app in a partially signed-in state and can be retried.
- [ ] Automated checks cover authenticated and unauthenticated navigation states.
