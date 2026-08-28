# Search by Meaning

Status: implemented

## Problem Statement

Current Search finds only literal case- and diacritic-insensitive substrings in Item names, Tags, and Descriptions. A Member searching for `watch` therefore cannot find an Item named `Clock` unless the literal query also appears in its Tags or Description.

Search should relate a query to Items through synonyms, categories, and obvious purposes while retaining predictable literal results. It should remain useful when its cloud backend is offline, delayed, or unavailable.

## Solution

Keep literal Search on the Android device and add conceptual retrieval through an authenticated JavaScript Cloud Functions backend and a Firestore Standard vector index. A backend-owned Search record holds one Gemini embedding per non-root Item. Firestore triggers eventually refresh these records after relevant Item changes, while a callable Function embeds a Member's query, verifies Household membership, and returns the nearest Item IDs.

The app shows literal results immediately. After a 500 ms pause on a query containing at least three letters or digits, it requests up to ten vector results and merges them beneath precise literal matches. Cloud failures silently leave the complete literal result list in place.

## User Stories

1. As a Member, I want `watch` to find an Item named `Clock`, so that I can find Items without recalling their recorded wording.
2. As a Member, I want precise Item names and Tags to remain predictable, so that conceptual ranking does not displace obvious literal results.
3. As a Member, I want generated Descriptions to make Items findable, so that useful generated text participates in Search like any other Description.
4. As a Member, I want literal Search to keep working while cloud Search is unavailable, so that Search never depends entirely on network inference.
5. As a Member, I want Search confined to my Household, so that another Household's indexed Items cannot appear in my results.

## Search Behaviour

- Searchable Item text is the Item name, Tags, and Description, regardless of whether the Description was written by a Member or produced by Description Generation.
- Item Paths, Item Photos, web URLs, and the Household root are not searchable and are not embedded.
- Search supports synonyms, categories, obvious purposes, and attributes explicitly present in searchable Item text.
- Search does not promise unstated properties, indirect associations, multi-step planning, subjective judgments, negation-based filtering, aggregation, or question answering.
- Same-language conceptual matching is the prototype contract. Cross-language and typo-tolerant matching may work incidentally but are neither promised nor tested.
- Every nonblank query produces the complete existing literal results immediately.
- A vector query starts after 500 ms without query changes only when the normalized query contains at least three Unicode letters or digits.
- A pending vector query shows a small inline progress indicator. Older responses are ignored when the query changes or Search closes.
- A successful vector response produces the union of every precise literal match and up to ten nearest conceptual matches, with duplicate Items removed.
- Precise literal matches rank first in this order: exact Item name, Item-name prefix, exact Tag. Existing normalization remains case- and diacritic-insensitive.
- Remaining results use Firestore cosine-distance order. Name substrings, Tag substrings, and Description matches receive no guaranteed position unless included by vector ranking.
- No minimum similarity threshold is applied. Plausible but weak nearest results are acceptable in the prototype.
- Cloud, embedding, index, authentication, and connectivity failures are not shown to the Member; the complete local literal results remain visible.
- Result cards remain unchanged: Item name, optional thumbnail, and Item Path. Scores, explanations, and literal/conceptual labels are not shown.
- Opening a result retains the existing navigation and add-Item behaviour.

## Backend and Index

- Functions use JavaScript on the second-generation Node 22 runtime with zero minimum instances.
- No maximum-instance limit, application rate limiter, query-length cap, App Check enforcement, budget alert, or spend-cap requirement is added for Search.
- The callable Search Function requires Firebase Authentication, resolves or verifies `memberships/{uid}`, and queries only that Member's Household.
- The callable Function returns Item IDs only. Android ignores duplicate IDs and IDs absent from its currently loaded Inventory.
- Each non-root Item has a backend-owned record under its Household's separate Search index. Client Firestore Rules deny direct reads and writes to these records.
- A Search record contains the Item ID, one top-level 768-dimensional vector, a deterministic source-content hash, and the embedding-model version.
- The document embedding uses `gemini-embedding-2` over labelled Item name, Tags, and Description text. The query uses the model's retrieval-query prefix. Firestore ranks with cosine distance.
- Item create and relevant update events generate an embedding eventually without delaying the Item write. Unrelated Item changes are skipped when the source-content hash is unchanged.
- Item deletion deletes its Search record. Household-root events do not create Search records.
- Before storing an embedding, the handler re-reads the current Item and verifies the source-content hash, preventing an older event from overwriting newer indexed content.
- Existing vectors remain available when re-embedding fails. A new Item participates only in literal Search until its first embedding succeeds.
- Only transient network, throttling, and provider/server failures use the second-generation Function platform's classified exponential retry policy for up to 24 hours. Permanent and unexpected failures stop immediately.
- Handlers are idempotent under duplicate delivery and retries.
- There are no existing Items to backfill. The first release includes no backfill or embedding-model migration command.
- Search may use unrestricted provider retention, training, or other processing without Member notice, as recorded in ADR-0004.

## Existing Search Contract

- `SRC-01`, `SRC-03`, `SRC-06`, and `SRC-07` in the Household Inventory specification remain in force.
- `SRC-02` continues to define local literal matching and fallback behaviour.
- The final post-vector result order defined here supersedes the field and match ordering in `SRC-04` and `SRC-05`.

## Testing Decisions

- Pure Android tests cover query normalization and eligibility, debounce behaviour, precise-literal ranking, vector-result merging and deduplication, stale-response suppression, loading state, and silent literal fallback.
- Controller tests use a fake Search gateway and do not depend on Cloud Functions or Gemini.
- Function core logic uses an injectable embedder. Automated tests use deterministic normalized 768-dimensional fake vectors and make no live Gemini calls.
- Pure Function tests cover labelled embedding text, content hashing, model versioning, error classification, stale-event protection, authorization, response mapping, and idempotency.
- Firebase Emulator Suite tests cover Item trigger to Search-record creation/update/deletion and authenticated Household-scoped callable vector results.
- The vector field remains top-level because nested vector-field behaviour is not relied upon in the emulator.
- Local tests cannot prove that the required production vector index exists. A documented manual staging smoke test verifies a real Gemini embedding, production Firestore vector index, and Household isolation.
- Implementation does not deploy or call the live project without explicit authorization.

## Out of Scope

- Item Photo understanding or embedding.
- Item Path or web URL matching.
- Cross-language, fuzzy, or typo-tolerant Search guarantees.
- Similarity thresholds or a no-conceptual-match state.
- Search answers, aggregations, filters, recommendations, or subjective reasoning.
- Scores, match explanations, or result-type labels.
- Offline conceptual Search.
- Backfill and embedding-model migration tooling.
- Firestore Enterprise Pipeline operations or another vector database.
- Production-scale latency, throughput, availability, or relevance guarantees.
- Search-specific App Check enforcement, rate limiting, hard spending limits, or monitoring dashboards.

## Further Notes

- This specification follows the Household Inventory glossary and ADR-0005.
- The prototype intentionally permits noisy nearest results so the first implementation can remain simple. A similarity threshold may be reconsidered after collecting real query and result examples.
- `watch -> Clock` is the initial positive relevance example. The existing literal and controller cases remain regression examples.
