# Description Generation Overrides Newer Input

When a Description Generation completes, its result replaces the Item's current Description even when a Member changed that Description after making the request. The explicit request for an LLM-authored replacement takes precedence over preserving later input, accepting that concurrent Member work may be lost.
