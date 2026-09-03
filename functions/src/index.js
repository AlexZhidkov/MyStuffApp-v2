import { GoogleGenAI } from "@google/genai";
import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import { defineSecret } from "firebase-functions/params";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { onCall } from "firebase-functions/v2/https";
import { createDeterministicEmbedder } from "./deterministic-embedder.js";
import { createFirestoreSearchRepository } from "./firestore-search-repository.js";
import { createGeminiEmbedder } from "./gemini-embedder.js";
import { createSearchHandlers } from "./search-handlers.js";
import { createSearchModule } from "./search-module.js";
import { createItemMoveHandlers } from "./item-move-handlers.js";
import { createItemMoveModule } from "./item-move-module.js";
import { createInvitationAcceptanceHandlers } from "./invitation-acceptance-handlers.js";
import { createInvitationAcceptanceModule } from "./invitation-acceptance-module.js";

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
let invitationAcceptanceHandlers;

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

function getInvitationAcceptanceHandlers() {
  if (invitationAcceptanceHandlers !== undefined) {
    return invitationAcceptanceHandlers;
  }
  invitationAcceptanceHandlers = createInvitationAcceptanceHandlers({
    acceptance: createInvitationAcceptanceModule({ database: getFirestore() }),
    logger,
  });
  return invitationAcceptanceHandlers;
}

export const acceptHouseholdInvitation = onCall(runtimeOptions, (request) =>
  getInvitationAcceptanceHandlers().acceptHouseholdInvitation(request),
);
