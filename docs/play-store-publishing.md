# Publishing MyStuff to Google Play

> Last verified: 3 September 2026. Google Play requirements change regularly;
> check the linked official documentation before submitting a release.

MyStuff can be published to Google Play, and the release build currently compiles
successfully. However, the app is not yet ready for a public production release.

The main blockers are:

- The generated Android App Bundle (AAB) is unsigned because
  [`app/build.gradle.kts`](../app/build.gradle.kts) has no release signing configuration.
- The app creates Firebase accounts but has no account-deletion flow; the
  [prototype specification](../.scratch/household-inventory/spec.md) explicitly defers it.
- A public privacy policy and an in-app privacy-policy link are missing.
- Production Firebase, Google sign-in, and Play Integrity certificates still need configuring.

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
JAVA_HOME=/opt/android-studio/jbr ./gradlew bundleRelease
```

It creates:

```text
app/build/outputs/bundle/release/app-release.aab
```

Without release signing configuration, that file is unsigned and Play Console will
not accept it.

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

Create an app named **MyStuff**, enroll in Play App Signing, and upload the signed
AAB to **Testing > Internal testing** first.

The app already targets Android 16 / API 36, which satisfies the Google Play target
API requirement in effect when this guide was last verified.

Official references:

- [Create and set up an app](https://support.google.com/googleplay/android-developer/answer/9859152)
- [Target API level requirements](https://support.google.com/googleplay/android-developer/answer/11926878)

## 6. Connect the Play signing identity to Firebase

After Play App Signing is configured:

1. Open **Play Console > App integrity**.
2. Copy the Play app-signing SHA-1 and SHA-256 certificate fingerprints.
3. Add them under **Firebase > Project settings > Your apps > `com.azhidkov.stuff`**.
4. Also retain any required local upload/release certificate fingerprints.
5. Download the updated `google-services.json`.
6. Replace `app/google-services.json` with the updated file.
7. Increment `versionCode`, rebuild, and upload a new internal-test AAB if the
   Firebase configuration changed after the first upload.
8. Link the Play Integrity API to the same Firebase/Google Cloud project.
9. Register the Play app-signing SHA-256 certificate under Firebase App Check.

This is essential because release builds use the Play Integrity App Check provider
in `app/src/release/java/com/azhidkov/mystuff/AppCheckProvider.kt`.

For distribution exclusively through Google Play, Firebase recommends requiring
the `PLAY_RECOGNIZED` and `LICENSED` verdicts. This differs from the repository's
current private, outside-Play setup notes.

Official references:

- [Firebase App Check with Play Integrity](https://firebase.google.com/docs/app-check/android/play-integrity-provider)
- [Firebase Google sign-in for Android](https://firebase.google.com/docs/auth/android/google-signin)

## 7. Deploy and verify the production backend

Select the intended production Firebase project, then deploy Functions, indexes,
and both security-rule sets:

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

Implement account deletion and the in-app privacy-policy entry point first. Release
signing, Firebase certificate registration, and the initial internal-test upload can
then be completed without leaving known production-policy blockers unresolved.
