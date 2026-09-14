const { readFileSync } = require("node:fs");
const assert = require("node:assert/strict");
const { test } = require("node:test");

test("Storage authorization keeps its Firestore access contract within two documents", () => {
  const rules = readFileSync("storage.rules", "utf8");
  const accesses = [...rules.matchAll(/firestore\.(?:get|exists)\s*\(/g)];

  // The Storage emulator does not enforce the production cross-service limit. Keep this
  // source-level guard beside the emulator tests so adding another lookup cannot silently
  // make production transfers fail with permission-denied.
  assert.ok(
    accesses.length <= 2,
    `storage.rules contains ${accesses.length} Firestore access calls; the production limit is 2`,
  );
});
