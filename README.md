# MyStuff

MyStuff is an Android prototype for a shared Household Inventory. It is designed
to help Household Members record belongings in a flexible Item tree and find
them later by following an Item Path such as:

```text
Our Home → Garage → Cabinet → Drill
```

A Household is the root Item of one shared Inventory. Areas, containers, and
belongings are all represented by the same Item model, so the tree can match
the way each Household is organized.

## Project status

MyStuff is an early, privately distributed prototype rather than a
production-ready or public Play Store application.

Currently implemented:

- Google sign-in through Android Credential Manager and Firebase Authentication
- Existing Firebase session restoration
- Retryable sign-in errors and sign-out
- A Jetpack Compose sign-in experience
- A signed-in Household entry screen
- Transactional Household creation with one root Item and an Owner membership
- Automatic reopening of an existing Household after sign-in
- Owner-only, seven-day Household invitations with revocation and link replacement
- Child Item creation and editing with names, descriptions, Tags, and camera-first
  optional mobile-sized WebP photos and thumbnails
- Independent persistent background upload and retry for each Item photo variant
- Deep Inventory browsing with immediate Child Items and complete Item Paths
- Firebase rules and emulator tests for Household, Item, invitation, and photo authorization
- Unit-tested session state transitions

Invitation acceptance, search, moving, deletion, and connected
synchronization are planned in the
[prototype specification](.scratch/household-inventory/spec.md).

## Technology

- Kotlin
- Jetpack Compose and Material 3
- Android Credential Manager
- Firebase Authentication
- Cloud Firestore
- Firebase Storage
- Jetpack WorkManager
- Gradle Kotlin DSL with a version catalog
- JUnit 4

The app targets Android 16 / API 36 and supports Android 9 / API 28 and newer.

## Prerequisites

- Android Studio with Android SDK Platform 36 installed
- JDK 17
- An Android device or emulator running API 28 or newer
- A Firebase project configured for Google sign-in

The Gradle wrapper is included, so a separate Gradle installation is not
required.

## Firebase setup

The app expects Firebase configuration at
`app/google-services.json`. To connect your own Firebase project:

1. Create an Android app in Firebase with package name
   `com.azhidkov.stuff`.
2. Enable Google as an Authentication sign-in provider.
3. Register the SHA fingerprint for the certificate used to sign the app.
4. Download the generated `google-services.json` and place it in `app/`.

The checked-in configuration belongs to the prototype's existing Firebase
project. A locally signed build may not be authorized for Google sign-in until
its signing certificate is registered in that project.

## Build and run

Open the repository in Android Studio, allow Gradle sync to complete, select an
API 28+ device, and run the `app` configuration.

The equivalent command-line workflow is:

```bash
./gradlew assembleDebug
./gradlew installDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Run from the terminal

```bash
cd /home/alex/Source/MyStuffApp-v2

/home/alex/Android/Sdk/platform-tools/adb devices

JAVA_HOME=/opt/android-studio/jbr ./gradlew installDebug
```

## Tests

Run the local unit tests with:

```bash
./gradlew test
```

The local unit tests exercise authentication and Household navigation with fake
gateways. Firestore and Storage authorization tests run against the Firebase emulators:

```bash
npm install
npm run test:rules
```

The on-device photo encoder check verifies the generated WebP format and dimensions:

```bash
./gradlew connectedDebugAndroidTest
```

## Project structure

```text
app/src/main/java/com/azhidkov/mystuff/
├── MainActivity.kt                  Compose application entry point
├── SessionController.kt             Session and Household navigation decisions
├── FirebaseAuthenticationGateway.kt Google and Firebase authentication
├── FirebaseHouseholdGateway.kt      Household persistence in Cloud Firestore
└── ui/
    ├── SignInScreen.kt
    ├── HouseholdEntryScreen.kt
    └── theme/

app/src/test/                         Local unit tests
.scratch/household-inventory/         Product specification and implementation issues
CONTEXT.md                            Household Inventory domain language
```

## Product and domain documentation

- [Prototype specification](.scratch/household-inventory/spec.md) describes the
  V1 scope, journeys, requirements, and deferred work.
- [Domain context](CONTEXT.md) defines the project's shared language and Item
  tree model.
- Implementation work is tracked as local Markdown issues under
  `.scratch/household-inventory/issues/`.
