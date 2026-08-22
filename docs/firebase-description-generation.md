# Firebase Description Generation Integration

This checklist configures the Firebase services used by Description Generation. The app uses
Firebase AI Logic with the Gemini Developer API free tier. It does not use Agent Platform, a
paid-tier path, a server proxy, or another model provider, and it does not show Members any
additional confirmation or informational copy.

The Remote Config parameter is `description_generation_model`. The app reads and activates this
parameter for every background generation request. A nonblank value fetched within the last 24
hours is used; otherwise application code falls back to `gemini-3.7-flash`. Prompts, factual
preservation, language selection, output constraints, and response validation remain bundled in
the app.

## Firebase console checklist

- [ ] In **Firebase AI Logic**, choose **Get started**, select the **Gemini Developer API**, and
  keep the project on its free-tier path. Do not configure Agent Platform or a billing-dependent
  alternate backend.
- [ ] In **Remote Config**, create the string parameter `description_generation_model`, set its
  default value to a currently validated Gemini model name, and publish the configuration.
- [ ] In **Project settings > Your apps**, register the SHA-256 fingerprints for every certificate
  used to sign a privately distributed release build.
- [ ] In **App Check > Apps**, register the Android app with **Play Integrity**. Configure it as an
  app distributed exclusively outside Google Play: do not require `PLAY_RECOGNIZED`, do not
  require `LICENSED`, and set the minimum acceptable device integrity level to **Device
  integrity** (`MEETS_DEVICE_INTEGRITY`).
- [ ] For each development machine or emulator, run a debug build, copy the App Check debug token
  from Logcat, and register it in **App Check > Apps > Manage debug tokens**. Never commit a debug
  token to the repository.
- [ ] In **App Check > APIs > Firebase AI Logic**, set **Baseline protection** to **Enforced** and
  leave **Replay protection** disabled. Confirm the app is using ordinary session tokens rather
  than limited-use tokens.

Firebase's Android setup references are the
[Remote Config guide](https://firebase.google.com/docs/remote-config/android/get-started),
[debug-provider guide](https://firebase.google.com/docs/app-check/android/debug-provider),
[outside-Google-Play Play Integrity guide](https://firebase.google.com/docs/app-check/android/play-integrity-provider),
and [Firebase AI Logic App Check guide](https://firebase.google.com/docs/ai-logic/app-check).

## Integration verification

Use an existing Item with an Item Photo and a valid signed-in Member. Record the build commit,
device, UTC time, configured model, visible result, and relevant App Check metric or error for each
case in the evidence table below.

1. Remove `description_generation_model` from the published Remote Config template, clear the
   app's data, launch it, and register the newly logged App Check debug token before signing in.
   Run Description Generation, confirm it completes with the bundled `gemini-3.7-flash` fallback,
   and confirm it appears as a verified Firebase AI Logic request in **App Check > APIs**.
2. Publish `description_generation_model` with a different currently supported model, wait for
   the publish to propagate, clear the app's data, launch it, and register its new debug token.
   Sign in and run Description Generation again. Confirm it completes and the generated
   Description changes in Firestore. Record the published model name as evidence that this
   exercised the remotely configured path.
3. On a disposable debug installation, delete or do not register its debug token, then attempt
   Description Generation. Confirm rejection from the worker-visible Firebase error and from an
   **Unverified: invalid requests** increase in the Firebase AI Logic App Check metrics. Restore a
   registered debug token before continuing development.
4. Confirm **Replay protection** still shows **Disabled** after the requests.

| Case | Commit / build | Device | UTC time | Model | Result and App Check evidence |
| --- | --- | --- | --- | --- | --- |
| Authorized fallback |  |  |  | `gemini-3.7-flash` |  |
| Authorized remote model |  |  |  |  |  |
| Rejected unverified request |  |  |  | n/a |  |

Do not mark the integration verified until all three evidence rows are populated from the target
Firebase project. Local unit tests deliberately use an in-memory Remote Config boundary and do
not require live Remote Config, App Check, or Gemini infrastructure.
