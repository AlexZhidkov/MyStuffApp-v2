# Publishing MyStuff to Google Play

> External requirements last verified: 12 September 2026. Repository implementation
> status updated: 12 September 2026. Google Play requirements change regularly;
> check the linked official documentation before submitting a release.

MyStuff has an active Google Play Internal Testing release, `1.0 (1) - Internal test`,
and the release build currently compiles successfully. However, the app is not yet
ready for a public production release.

The policy implementation described in Section 3 is deployed. The production Firebase
backend is active in `mystuff-ai-app`, and the GitHub Pages privacy and account-deletion
pages are live. The remaining release blockers are:

- Verify the active Internal Testing build on a Play-installed device against the
  representative journey in Section 7.
- Complete the remaining Play Console listing, app-content, testing, and production gates.

## 1. Decide the permanent package name

The current application ID is `com.azhidkov.stuff`:

```kotlin
defaultConfig {
    applicationId = "com.azhidkov.stuff"
}
```

Google Play package names are effectively permanent. If a different identifier,
such as `com.example.mystuff`, is wanted, change it before creating the Play listing.

## 2. Create a Play Console account

Register through the [Google Play Console](https://play.google.com/console/).
Registration currently has a one-time US$25 fee, followed by identity and contact
verification. New personal accounts also require verification using a physical,
non-rooted device running Android 10 or newer.

Official references:

- [Get started with Play Console](https://support.google.com/googleplay/android-developer/answer/6112435)
- [Device verification requirements](https://support.google.com/googleplay/android-developer/answer/14316361)

## 3. Implement the policy blockers

Before requesting production review:

1. Add a readily discoverable **Delete account** action inside the app.
2. Provide a public web page through which account deletion can also be requested.
3. Delete the Firebase Authentication account and associated Firestore and Storage
   data, subject to any clearly disclosed and legally necessary retention policy.
4. Decide what happens when the person deleting their account owns a shared
   Household. Possible designs include transferring ownership or deleting the
   Household and its data.
5. Publish a privacy policy on a public HTML URL and link it from inside the app.
6. Disclose the handling of Google identity data, Household and Item content,
   photos, Firebase storage, semantic-search processing, AI description processing,
   retention, and deletion.

These items are implemented by the in-app Account and Household deletion flows,
durable Firebase cleanup jobs, deletion-aware Firestore and Storage rules, and the
static site under [`site/`](../site/). The deployed public URLs are:

- Privacy Policy: `https://alexzhidkov.github.io/MyStuffApp-v2/`
- Account deletion: `https://alexzhidkov.github.io/MyStuffApp-v2/delete-account/`

The Pages workflow is manual (`workflow_dispatch`). Both pages were verified live on
12 September 2026. If the policy changes, run `Deploy privacy site` from the repository's
`main` branch and verify both URLs again in the Play-delivered build. The private
external-request procedure and failed-job recovery steps are in
[Account Deletion Operations](account-deletion-operations.md).

Google requires both in-app and external deletion paths for apps that create
accounts. The privacy policy must be publicly accessible, non-geofenced, and not
merely a PDF.

Official references:

- [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111)
- [Firebase data-disclosure guidance](https://firebase.google.com/docs/android/play-data-disclosure)

The exact answers in the Play Console Data Safety form must be audited against the
released app and its configured Firebase and Gemini services. Do not infer those
answers solely from dependency names.

## 4. Create a signed Android App Bundle

The following command currently compiles the release bundle and runs release lint:

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew bundleRelease lintRelease
```

It creates:

```text
app/build/outputs/bundle/release/app-release.aab
```

The plain Gradle task does not add an upload signature because the repository does
not contain release-key material. Generate the upload-signed AAB with the upload key,
then distribute the release only through Google Play. Play signs the APKs delivered
to testers and Members with the app-signing key; the upload key is not the installed
app's identity.

The simplest signing workflow is through Android Studio:

1. Select **Build > Generate Signed Bundle / APK**.
2. Select **Android App Bundle**.
3. Create an upload keystore and key.
4. Back up the keystore and its passwords securely. Never commit them.
5. Select the `release` build type.
6. Generate the signed `.aab`.

Enable Play App Signing during the first upload. Google will protect the app-signing
key, while the locally held key is used as the upload key for later releases.

The initial version is currently:

```kotlin
versionCode = 1
versionName = "1.0"
```

Increment `versionCode` for every bundle uploaded after the first one. Update
`versionName` when the user-visible version should change.

Official reference:

- [Sign your app](https://developer.android.com/studio/publish/app-signing)

## 5. Create the Play listing and upload to Internal Testing

The **MyStuff** Play app is enrolled in Play App Signing. **Testing > Internal
testing** is active with release `1.0 (1) - Internal test` as of 12 September 2026.
Upload later signed AABs to Internal Testing before promotion.

Internal testers enrol and install from:
`https://play.google.com/apps/internaltest/4701725446253616628`

The app already targets Android 16 / API 36, which satisfies the Google Play target
API requirement in effect when this guide was last verified.

Official references:

- [Create and set up an app](https://support.google.com/googleplay/android-developer/answer/9859152)
- [Target API level requirements](https://support.google.com/googleplay/android-developer/answer/11926878)

## 6. Connect the Play signing identity to Firebase

All certificates in Play's recommended quantum-ready signing setup are registered on
the Firebase Android app: the current classical key, the post-quantum key, and the
previous classical key used on older Android versions. Firebase generates Android OAuth
clients from the SHA-1 fingerprints, and the refreshed
[`app/google-services.json`](../app/google-services.json) must ship in builds that use
Google sign-in. The Play Console app's Play Integrity API is linked to `mystuff-ai-app`
(project number `37308974986`).

When the Play app-signing key changes:

1. Open **Play Console > App integrity**.
2. Copy the SHA-1 and SHA-256 fingerprints for every certificate Play identifies as an
   app-signing key, including each key in a quantum-ready signing setup.
3. Add them under **Firebase > Project settings > Your apps > `com.azhidkov.stuff`**.
4. Retain the previous Play app-signing fingerprints while Play may still deliver
   builds signed with that key. Do not substitute the upload certificate: it verifies
   bundle uploads but does not identify Play-installed builds.
5. Download the updated `google-services.json`.
6. Replace `app/google-services.json` with the updated file.
7. Increment `versionCode`, rebuild, and upload a new internal-test AAB if the
   Firebase configuration changed after the first upload.
8. Confirm the Play Integrity API remains linked to Firebase/Google Cloud project
   `mystuff-ai-app` (project number `37308974986`).
9. Register the Play app-signing SHA-256 certificate under Firebase App Check.

This is essential because release builds use the Play Integrity App Check provider
in `app/src/release/java/com/azhidkov/mystuff/AppCheckProvider.kt`.

The Firebase App Check registration currently requires `PLAY_RECOGNIZED`, `LICENSED`,
and `MEETS_DEVICE_INTEGRITY`, and retains the one-hour token lifetime. Debug builds
continue to use the App Check debug provider; registering a local debug token does not
relax the Play Integrity policy used by release builds.

Official references:

- [Firebase App Check with Play Integrity](https://firebase.google.com/docs/app-check/android/play-integrity-provider)
- [Firebase Google sign-in for Android](https://firebase.google.com/docs/auth/android/google-signin)

## 7. Deploy and verify the production backend

The production backend was verified active in `mystuff-ai-app` on 12 September 2026:
Cloud Functions run in `australia-southeast1`, and the deployed Firestore indexes
include the Household Access and Search indexes. For later deployments, select the
intended production Firebase project, then deploy Functions, indexes, and both
security-rule sets:

```bash
firebase use mystuff-ai-app
firebase deploy --only functions,firestore:indexes,firestore:rules,storage
```

Before running that command, confirm that `mystuff-ai-app` is the intended target
project. Deployment changes external production services.

Also complete the repository's integration checklists:

- [Firebase Description Generation integration](firebase-description-generation.md)
- [Firebase Semantic Search](firebase-semantic-search.md)

Install the app from the Play internal-testing link, rather than Android Studio,
and test at least:

- Google sign-in with a new and an existing user
- Household creation and reopening
- Item and attachment creation, editing, moving, and deletion
- Photo capture, selection, upload, display, and deletion
- AI description generation
- Literal and semantic search
- Invitation flows that are represented as available in the store listing
- Account deletion and associated data cleanup
- Operation after reinstalling the Play-delivered app

For the Firebase signing and App Check release gate, record a single representative
Play-installed journey that covers the core protected capabilities:

| Build | Install source | Device | UTC time | Google sign-in | Household | Search | Description Generation | Certificate / App Check evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `1.0 (1) - Internal test` | Internal-testing opt-in |  |  |  |  |  |  |  |

## 8. Complete the Play Console forms

Complete all dashboard and **App content** tasks, including:

- Public privacy-policy URL
- Data Safety declarations
- Account-deletion URL
- Ads declaration (currently expected to be **No**, provided no ads are added)
- App access instructions
- Target audience
- Content-rating questionnaire
- Support contact details
- App category
- Countries and regions

Because MyStuff is login-gated, Google must receive reusable access instructions
and any credentials necessary to review all functionality. For Google sign-in,
use a dedicated, maintained reviewer account and provide clear English instructions
through the Play Console's secure App access form. If reviewing collaboration
requires multiple accounts, provide all necessary test accounts and setup steps.

Official references:

- [Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455)
- [Reviewer sign-in requirements](https://support.google.com/googleplay/android-developer/answer/15748846)
- [Complete the Data Safety form](https://support.google.com/googleplay/android-developer/answer/10787469)

## 9. Prepare the store listing

Provide:

- A 512×512, 32-bit PNG Play icon, no larger than 1,024 KB
- A 1024×500 JPEG or 24-bit PNG feature graphic without alpha
- At least two screenshots across supported device types
- Preferably at least four portrait screenshots at 1080×1920 for merchandising
- App name, short description, and full description

The existing 1254×1254 artwork under `design/icons/` is a suitable source from
which to export a compliant 512×512 Play icon.

Official reference:

- [Store listing asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151)

## 10. Run the required test period and publish

For a personal developer account created after 13 November 2023, production access
requires a closed test with at least 12 testers continuously opted in for 14
consecutive days.

Recommended sequence:

1. Publish and validate an internal-test release.
2. Complete the store listing, policy forms, privacy policy, and account deletion.
3. Start a closed test.
4. Keep at least 12 testers continuously opted in for 14 days if the account is
   subject to the new-personal-account requirement.
5. Apply for production access from the Play Console dashboard.
6. Address pre-review checks and testing feedback.
7. Create a production release, preferably with a staged rollout.
8. Submit it for Google Play review.

Official reference:

- [Testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465)

## Recommended next milestone

Finish the Play-installed verification in Section 7, then complete the store listing
and app-content forms before publishing the closed-test candidate.
