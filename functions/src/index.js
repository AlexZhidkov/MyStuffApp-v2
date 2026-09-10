import { GoogleGenAI } from "@google/genai";
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { logger } from "firebase-functions";
import { defineSecret } from "firebase-functions/params";
import { onDocumentCreated, onDocumentWritten } from "firebase-functions/v2/firestore";
import { onCall } from "firebase-functions/v2/https";
import { createDeterministicEmbedder } from "./deterministic-embedder.js";
import { createFirestoreSearchRepository } from "./firestore-search-repository.js";
import { createGeminiEmbedder } from "./gemini-embedder.js";
import { createSearchHandlers } from "./search-handlers.js";
import { createSearchModule } from "./search-module.js";
import { createItemMoveHandlers } from "./item-move-handlers.js";
import { createItemMoveModule } from "./item-move-module.js";
import { createHouseholdAccessHandlers } from "./household-access-handlers.js";
import { createHouseholdAccessModule } from "./household-access-module.js";
import { createItemDeletionHandlers } from "./item-deletion-handlers.js";
import { createItemDeletionModule } from "./item-deletion-module.js";

if (getApps().length === 0) initializeApp();

const REGION = "australia-southeast1";
const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");
const emulator = process.env.FUNCTIONS_EMULATOR === "true";
const runtimeOptions = {
  region: REGION,
  minInstances: 0,
  secrets: emulator ? [] : [GEMINI_API_KEY],
};
let handlers;
let itemMoveHandlers;
let householdAccessHandlers;
let itemDeletionHandlers;

function getHandlers() {
  if (handlers !== undefined) return handlers;
  const embedder = emulator
    ? createDeterministicEmbedder()
    : createGeminiEmbedder(new GoogleGenAI({ apiKey: GEMINI_API_KEY.value() }));
  const searchModule = createSearchModule({
    repository: createFirestoreSearchRepository(getFirestore()),
    embedder,
  });
  handlers = createSearchHandlers({ searchModule, logger });
  return handlers;
}

export const refreshItemSearchIndex = onDocumentWritten(
  {
    ...runtimeOptions,
    document: "households/{householdId}/items/{itemId}",
    retry: true,
  },
  (event) => getHandlers().refreshItemIndex(event),
);

export const searchInventory = onCall(runtimeOptions, (request) =>
  getHandlers().searchInventory(request),
);

function getItemMoveHandlers() {
  if (itemMoveHandlers !== undefined) return itemMoveHandlers;
  itemMoveHandlers = createItemMoveHandlers({
    itemMoveModule: createItemMoveModule({ database: getFirestore() }),
    logger,
  });
  return itemMoveHandlers;
}

export const moveInventoryItem = onCall(runtimeOptions, (request) =>
  getItemMoveHandlers().moveInventoryItem(request),
);

function getItemDeletionHandlers() {
  if (itemDeletionHandlers !== undefined) return itemDeletionHandlers;
  itemDeletionHandlers = createItemDeletionHandlers({
    itemDeletionModule: createItemDeletionModule({
      database: getFirestore(),
      bucket: getStorage().bucket(),
    }),
    logger,
  });
  return itemDeletionHandlers;
}

export const deleteInventoryItem = onCall(runtimeOptions, (request) =>
  getItemDeletionHandlers().deleteInventoryItem(request),
);

export const cleanupDeletedInventoryItem = onDocumentCreated(
  {
    ...runtimeOptions,
    document: "households/{householdId}/itemDeletionJobs/{itemId}",
    retry: true,
  },
  (event) => getItemDeletionHandlers().cleanupDeletedInventoryItem(event),
);

function getHouseholdAccessHandlers() {
  if (householdAccessHandlers !== undefined) {
    return householdAccessHandlers;
  }
  householdAccessHandlers = createHouseholdAccessHandlers({
    householdAccess: createHouseholdAccessModule({ database: getFirestore() }),
    logger,
  });
  return householdAccessHandlers;
}

export const claimHouseholdAccess = onCall(runtimeOptions, (request) =>
  getHouseholdAccessHandlers().claimHouseholdAccess(request),
);

export const removeHouseholdAccess = onCall(runtimeOptions, (request) =>
  getHouseholdAccessHandlers().removeHouseholdAccess(request),
);
