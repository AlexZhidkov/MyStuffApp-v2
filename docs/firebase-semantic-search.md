# Firebase Semantic Search

Semantic Search augments the Android app's immediate literal matching with
Household-scoped conceptual results. A second-generation JavaScript Cloud Function
embeds each non-root Item's name, Tags, and Description with
`gemini-embedding-2`; Firestore Standard stores and searches one 768-dimensional
cosine vector per Item.

The backend runs in `australia-southeast1` with zero minimum instances. The project
must remain on Blaze billing. By design, this prototype adds no maximum-instance
limit, rate limiter, App Check requirement, query-length cap, budget alert, or
spend cap.

## One-time setup

Use Node 22 for the Functions package and install both dependency sets:

```bash
npm install
npm install --prefix functions
```

Create a Gemini Developer API key, then store it as a Functions secret. Do not put
the key in source control, `google-services.json`, or the Android app:

```bash
firebase functions:secrets:set GEMINI_API_KEY
```

The checked-in `firestore.indexes.json` declares the required collection-group
vector index for the top-level `searchEmbedding` field. The checked-in Firestore
Rules deny all client access to `searchIndex`; only the Admin SDK in Functions can
read or write those records.

## Local verification

Pure Function tests do not call Gemini:

```bash
npm run test:functions
```

The full backend emulator test uses a deterministic 768-dimensional embedder. It
proves Item create, update, and deletion propagation plus an authenticated,
Household-scoped callable query:

```bash
npm run test:functions:emulator
```

Run authorization and Android tests separately because the Firebase test commands
use the same emulator ports:

```bash
npm run test:rules
./gradlew test
```

No Gemini key is needed in the emulator. `src/index.js` selects the deterministic
embedder only when the Firebase Functions emulator sets `FUNCTIONS_EMULATOR=true`.

## Deployment

The implementation and tests do not deploy automatically. After selecting the
intended Firebase project, deploy the Functions, vector index, and Rules together:

```bash
firebase use mystuff-ai-app
firebase deploy --only functions,firestore:indexes,firestore:rules
```

Firestore may take time to build the vector index. Conceptual queries cannot work
until that index is ready. There are no existing Items to backfill for the first
release; new Item writes will create Search records after deployment.

The `refreshItemSearchIndex` trigger keeps an existing vector when re-embedding
fails. Classified transient embedding and Firestore failures are rethrown to the
platform's built-in retry policy for up to 24 hours. Permanent failures are logged
and stop, while Android continues to show literal results.

## Staging smoke test

Use a non-production Household after the vector index reports ready:

1. Create an Item named `Clock` with a useful Description and wait for
   `households/<householdId>/searchIndex/<itemId>` to appear.
2. Search for `watch`. After the 500 ms debounce and brief progress indicator,
   `Clock` should appear even though `watch` is not literal Item text.
3. Add exact-name, name-prefix, and exact-Tag matches for the same query. Confirm
   they appear above conceptual results in that order.
4. Update the Item Description and confirm the Search record's `sourceHash`
   changes. Delete the Item through an administrative staging workflow and confirm
   its Search record disappears.
5. Create a similarly named Item in another Household and confirm it never appears
   for the first Household's Member.
6. Temporarily make the callable unavailable and confirm Search shows the complete
   local literal result list without an error message.

This smoke test intentionally validates only same-language synonyms, categories,
and obvious purposes. Typo tolerance, cross-language retrieval, and minimum
similarity quality are not release guarantees.
