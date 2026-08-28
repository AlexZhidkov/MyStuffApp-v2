import assert from "node:assert/strict";
import { test } from "node:test";
import { createGeminiEmbedder } from "../src/gemini-embedder.js";

test("Gemini embedder requests and returns one 768-dimensional embedding", async () => {
  const expectedVector = Array(768).fill(0.25);
  const embedder = createGeminiEmbedder({
    models: {
      async embedContent(request) {
        assert.deepEqual(request, {
          model: "gemini-embedding-2",
          contents: "task: search result | query: watch",
          config: { outputDimensionality: 768 },
        });
        return { embeddings: [{ values: expectedVector }] };
      },
    },
  });

  const result = await embedder.embedQuery("task: search result | query: watch");

  assert.deepEqual(result, expectedVector);
});

test("Gemini embedder classifies timeouts throttling and server failures as transient", async () => {
  for (const status of [408, 429, 503]) {
    const embedder = createGeminiEmbedder({
      models: {
        async embedContent() {
          throw Object.assign(new Error("Provider unavailable."), { status });
        },
      },
    });

    await assert.rejects(embedder.embedItem("title: Clock | text: tags: | description:"), {
      name: "TransientEmbeddingError",
    });
  }
});

test("Gemini embedder classifies a nested network failure as transient", async () => {
  const networkFailure = Object.assign(new Error("fetch failed"), {
    cause: Object.assign(new Error("socket reset"), { code: "ECONNRESET" }),
  });
  const embedder = createGeminiEmbedder({
    models: {
      async embedContent() {
        throw networkFailure;
      },
    },
  });

  await assert.rejects(
    embedder.embedItem("title: Clock | text: tags: | description:"),
    (error) => error.name === "TransientEmbeddingError" && error.cause === networkFailure,
  );
});
