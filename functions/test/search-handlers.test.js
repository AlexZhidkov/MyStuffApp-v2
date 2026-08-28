import assert from "node:assert/strict";
import { test } from "node:test";
import { createSearchHandlers } from "../src/search-handlers.js";
import { TransientEmbeddingError } from "../src/gemini-embedder.js";
import { InvalidSearchQueryError, SearchMembershipError } from "../src/search-module.js";

test("callable Search rejects an unauthenticated caller", async () => {
  const handlers = createSearchHandlers({
    searchModule: {
      async searchInventory() {
        throw new Error("Unauthenticated Search reached the module.");
      },
    },
    logger: { error() {} },
  });

  await assert.rejects(handlers.searchInventory({ auth: null, data: { query: "watch" } }), {
    code: "unauthenticated",
  });
});

test("callable Search maps query and Household failures without exposing internals", async () => {
  for (const [failure, code] of [
    [new InvalidSearchQueryError(), "invalid-argument"],
    [new SearchMembershipError(), "permission-denied"],
    [new Error("secret provider details"), "internal"],
  ]) {
    const handlers = createSearchHandlers({
      searchModule: {
        async searchInventory() {
          throw failure;
        },
      },
      logger: { error() {} },
    });

    await assert.rejects(
      handlers.searchInventory({ auth: { uid: "member-1" }, data: { query: "watch" } }),
      (error) => error.code === code && !error.message.includes("secret provider details"),
    );
  }
});

test("Item indexing retries only classified transient embedding failures", async () => {
  const transientFailure = new TransientEmbeddingError(new Error("throttled"));
  const transientHandlers = createSearchHandlers({
    searchModule: {
      async refreshItemIndex() {
        throw transientFailure;
      },
    },
    logger: { error() {} },
  });

  await assert.rejects(
    transientHandlers.refreshItemIndex({
      params: { householdId: "household-1", itemId: "clock" },
    }),
    transientFailure,
  );

  const logged = [];
  const permanentHandlers = createSearchHandlers({
    searchModule: {
      async refreshItemIndex() {
        throw new Error("invalid provider response");
      },
    },
    logger: { error: (...values) => logged.push(values) },
  });

  await permanentHandlers.refreshItemIndex({
    params: { householdId: "household-1", itemId: "clock" },
  });
  assert.equal(logged.length, 1);
});
