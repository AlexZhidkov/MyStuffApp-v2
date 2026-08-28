import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import {
  createFirestoreSearchRepository,
  TransientSearchIndexError,
} from "../src/firestore-search-repository.js";

const emulatorAvailable = process.env.FIRESTORE_EMULATOR_HOST !== undefined;
let database;

before(() => {
  if (!emulatorAvailable) return;
  if (getApps().length === 0) initializeApp({ projectId: "demo-mystuff-search" });
  database = getFirestore();
});

after(async () => {
  if (!emulatorAvailable) return;
  await database.recursiveDelete(database.doc("households/household-1"));
  await database.recursiveDelete(database.doc("households/household-2"));
  await database.doc("memberships/member-1").delete();
});

test(
  "Firestore repository limits nearest Item IDs to the requested Household",
  { skip: !emulatorAvailable },
  async () => {
    const repository = createFirestoreSearchRepository(database);
    await database.doc("memberships/member-1").set({ householdId: "household-1" });
    await repository.putSearchRecord("household-1", "clock", {
      embedding: vector(1, 0),
      sourceHash: "clock-hash",
      modelVersion: "gemini-embedding-2:768",
    });
    await repository.putSearchRecord("household-1", "watch-box", {
      embedding: vector(0.8, 0.2),
      sourceHash: "watch-box-hash",
      modelVersion: "gemini-embedding-2:768",
    });
    await repository.putSearchRecord("household-2", "private-clock", {
      embedding: vector(1, 0),
      sourceHash: "private-hash",
      modelVersion: "gemini-embedding-2:768",
    });

    assert.equal(await repository.findHouseholdIdForMember("member-1"), "household-1");
    assert.deepEqual(
      await repository.findNearest("household-1", vector(1, 0), 10),
      ["clock", "watch-box"],
    );
  },
);

test("Firestore repository classifies retryable service failures", async () => {
  const unavailable = Object.assign(new Error("temporarily unavailable"), { code: 14 });
  const repository = createFirestoreSearchRepository({
    doc() {
      return { get: async () => { throw unavailable; } };
    },
  });

  await assert.rejects(
    repository.findHouseholdIdForMember("member-1"),
    (error) => error instanceof TransientSearchIndexError && error.cause === unavailable,
  );
});

test("Firestore repository leaves permanent failures unclassified", async () => {
  const denied = Object.assign(new Error("permission denied"), { code: 7 });
  const repository = createFirestoreSearchRepository({
    doc() {
      return { get: async () => { throw denied; } };
    },
  });

  await assert.rejects(
    repository.findHouseholdIdForMember("member-1"),
    (error) => error === denied,
  );
});

function vector(first, second) {
  return [first, second, ...Array(766).fill(0)];
}
