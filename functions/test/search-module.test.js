import assert from "node:assert/strict";
import { test } from "node:test";
import { createSearchModule } from "../src/search-module.js";

test("Member Search returns only nearest Items from their Household", async () => {
  const repository = new InMemorySearchRepository({
    memberships: new Map([
      ["member-1", "household-1"],
      ["member-2", "household-2"],
    ]),
    records: [
      searchRecord("household-1", "clock", vector(1, 0)),
      searchRecord("household-1", "watch-box", vector(0.8, 0.2)),
      searchRecord("household-2", "private-clock", vector(1, 0)),
    ],
  });
  const search = createSearchModule({
    repository,
    embedder: {
      async embedItem() {
        throw new Error("Item embedding is not used by this scenario.");
      },
      async embedQuery(text) {
        assert.equal(text, "task: search result | query: watch");
        return vector(1, 0);
      },
    },
  });

  const result = await search.searchInventory({
    memberId: "member-1",
    query: "watch",
  });

  assert.deepEqual(result, { itemIds: ["clock", "watch-box"] });
});

test("refreshing an Item makes its name, Tags, and Description searchable", async () => {
  const repository = new InMemorySearchRepository({
    memberships: new Map([["member-1", "household-1"]]),
    items: new Map([
      [
        "household-1/clock",
        {
          id: "clock",
          name: "Wall Clock",
          parentItemId: "household-1",
          tags: ["Decor", "Timepiece"],
          description: "A battery-powered analogue clock.",
          photoUrl: "gs://ignored/photo.webp",
        },
      ],
    ]),
  });
  const clockVector = vector(1, 0);
  const search = createSearchModule({
    repository,
    embedder: {
      async embedItem(text) {
        assert.equal(
          text,
          "title: Wall Clock | text: tags: Decor, Timepiece | " +
            "description: A battery-powered analogue clock.",
        );
        return clockVector;
      },
      async embedQuery() {
        return clockVector;
      },
    },
  });

  await search.refreshItemIndex({
    householdId: "household-1",
    itemId: "clock",
  });

  assert.deepEqual(
    await search.searchInventory({ memberId: "member-1", query: "watch" }),
    { itemIds: ["clock"] },
  );
});

test("an older Item event cannot publish a vector for newer searchable text", async () => {
  const repository = new InMemorySearchRepository({
    memberships: new Map([["member-1", "household-1"]]),
    items: new Map([
      [
        "household-1/clock",
        {
          id: "clock",
          name: "Old Clock",
          parentItemId: "household-1",
          tags: [],
          description: null,
        },
      ],
    ]),
  });
  const search = createSearchModule({
    repository,
    embedder: {
      async embedItem() {
        repository.setItem("household-1", "clock", {
          id: "clock",
          name: "New Clock",
          parentItemId: "household-1",
          tags: [],
          description: null,
        });
        return vector(1, 0);
      },
      async embedQuery() {
        return vector(1, 0);
      },
    },
  });

  await search.refreshItemIndex({
    householdId: "household-1",
    itemId: "clock",
  });

  assert.deepEqual(
    await search.searchInventory({ memberId: "member-1", query: "watch" }),
    { itemIds: [] },
  );
});

test("refreshing unchanged searchable text reuses its current vector", async () => {
  const repository = new InMemorySearchRepository({
    memberships: new Map([["member-1", "household-1"]]),
    items: new Map([
      [
        "household-1/clock",
        {
          id: "clock",
          name: "Clock",
          parentItemId: "household-1",
          tags: [],
          description: null,
        },
      ],
    ]),
  });
  let firstEmbedding = true;
  const search = createSearchModule({
    repository,
    embedder: {
      async embedItem() {
        if (!firstEmbedding) throw new Error("Unchanged text was embedded again.");
        firstEmbedding = false;
        return vector(1, 0);
      },
      async embedQuery() {
        return vector(1, 0);
      },
    },
  });

  await search.refreshItemIndex({ householdId: "household-1", itemId: "clock" });
  await search.refreshItemIndex({ householdId: "household-1", itemId: "clock" });

  assert.deepEqual(
    await search.searchInventory({ memberId: "member-1", query: "watch" }),
    { itemIds: ["clock"] },
  );
});

test("Search rejects a query with fewer than three letters or digits", async () => {
  const search = createSearchModule({
    repository: new InMemorySearchRepository({
      memberships: new Map([["member-1", "household-1"]]),
    }),
    embedder: {
      async embedItem() {
        throw new Error("Item embedding is not used by this scenario.");
      },
      async embedQuery() {
        throw new Error("An ineligible query was embedded.");
      },
    },
  });

  await assert.rejects(
    search.searchInventory({ memberId: "member-1", query: "T-2" }),
    { name: "InvalidSearchQueryError" },
  );
  await assert.rejects(
    search.searchInventory({ memberId: "member-1", query: undefined }),
    { name: "InvalidSearchQueryError" },
  );
});

class InMemorySearchRepository {
  constructor({ memberships, records = [], items = new Map() }) {
    this.memberships = memberships;
    this.records = records;
    this.items = items;
  }

  async findHouseholdIdForMember(memberId) {
    return this.memberships.get(memberId) ?? null;
  }

  async findNearest(householdId, queryVector, limit) {
    return this.records
      .filter((record) => record.householdId === householdId)
      .map((record) => ({
        itemId: record.itemId,
        similarity: cosineSimilarity(record.embedding, queryVector),
      }))
      .sort((left, right) => right.similarity - left.similarity)
      .slice(0, limit)
      .map(({ itemId }) => itemId);
  }

  async getItem(householdId, itemId) {
    return this.items.get(`${householdId}/${itemId}`) ?? null;
  }

  setItem(householdId, itemId, item) {
    this.items.set(`${householdId}/${itemId}`, item);
  }

  async getSearchRecord(householdId, itemId) {
    return (
      this.records.find(
        (record) => record.householdId === householdId && record.itemId === itemId,
      ) ?? null
    );
  }

  async putSearchRecord(householdId, itemId, record) {
    this.records = this.records.filter(
      (candidate) =>
        candidate.householdId !== householdId || candidate.itemId !== itemId,
    );
    this.records.push({ householdId, itemId, ...record });
  }

  async deleteSearchRecord(householdId, itemId) {
    this.records = this.records.filter(
      (candidate) =>
        candidate.householdId !== householdId || candidate.itemId !== itemId,
    );
  }
}

function searchRecord(householdId, itemId, embedding) {
  return { householdId, itemId, embedding };
}

function vector(first, second) {
  return [first, second, ...Array(766).fill(0)];
}

function cosineSimilarity(left, right) {
  const dot = left.reduce((sum, value, index) => sum + value * right[index], 0);
  const leftMagnitude = Math.sqrt(left.reduce((sum, value) => sum + value * value, 0));
  const rightMagnitude = Math.sqrt(right.reduce((sum, value) => sum + value * value, 0));
  return dot / (leftMagnitude * rightMagnitude);
}
