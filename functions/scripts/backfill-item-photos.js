#!/usr/bin/env node

import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { createItemPhotoBackfill } from "../src/item-photo-backfill.js";

if (getApps().length === 0) initializeApp();

const options = parseArguments(process.argv.slice(2));
const report = await createItemPhotoBackfill({ database: getFirestore() }).run(options);
process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
if (report.failures.length > 0) process.exitCode = 1;

function parseArguments(argumentsList) {
  let dryRun = false;
  let householdId;
  for (let index = 0; index < argumentsList.length; index += 1) {
    const argument = argumentsList[index];
    if (argument === "--dry-run") {
      dryRun = true;
      continue;
    }
    if (argument === "--household-id") {
      householdId = argumentsList[index + 1];
      if (householdId === undefined || householdId.startsWith("--")) {
        throw new Error("--household-id requires a value.");
      }
      index += 1;
      continue;
    }
    if (argument.startsWith("--household-id=")) {
      householdId = argument.slice("--household-id=".length);
      if (householdId.length === 0) throw new Error("--household-id requires a value.");
      continue;
    }
    throw new Error(`Unknown argument: ${argument}`);
  }
  return { dryRun, householdId };
}
