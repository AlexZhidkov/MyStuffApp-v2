# Use Cloud Functions and Firestore Vector Search

Search uses usage-based Firebase Cloud Functions to generate Item and query embeddings and query a Firestore Standard vector index, while the Android app preserves deterministic literal matching locally. Each backend-owned Search record contains one 768-dimensional `gemini-embedding-2` vector generated from the Item's labelled name, Tags, and Description, plus its source-content hash and embedding-model version; cosine distance ranks query results. Keeping these records separate from Item documents prevents Inventory snapshots from downloading vectors, and Firestore triggers update the index eventually without delaying Item changes.

This reuses the project's existing Blaze billing without an always-on server and accepts temporarily stale conceptual results and pay-as-you-go Functions, Firestore, and Gemini costs. Literal Search remains available while index work is pending or unavailable, and the backend must enforce Household membership before reading or returning Search data.

This supersedes ADR-0003's free-tier billing assumption. Description Generation remains device-owned through Firebase AI Logic, but Gemini Developer API usage is paid because the project is linked to a Cloud Billing account.
