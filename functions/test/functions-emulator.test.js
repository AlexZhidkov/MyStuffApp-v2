import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { deleteApp, initializeApp } from "firebase/app";
import {
  connectAuthEmulator,
  getAuth,
  signInWithEmailAndPassword,
  signInAnonymously,
} from "firebase/auth";
import {
  connectFirestoreEmulator,
  doc,
  getDoc,
  getFirestore as getClientFirestore,
} from "firebase/firestore";
import {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
} from "firebase/functions";
import { getApps, initializeApp as initializeAdminApp } from "firebase-admin/app";
import { getAuth as getAdminAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage as getAdminStorage } from "firebase-admin/storage";

const projectId = "demo-mystuff-search";
const emulatorAvailable =
  process.env.FIRESTORE_EMULATOR_HOST !== undefined &&
  process.env.FIREBASE_AUTH_EMULATOR_HOST !== undefined;
const householdId = "e2e-search-household";
const otherHouseholdId = "e2e-other-household";
const itemId = "e2e-clock";
const otherItemId = "e2e-private-clock";
const moveSourceId = "e2e-move-source";
const moveChildId = "e2e-move-child";
const moveTargetId = "e2e-move-target";
let clientApp;
let database;
let memberId;

before(async () => {
  if (!emulatorAvailable) return;
  if (getApps().length === 0) initializeAdminApp({ projectId });
  database = getFirestore();

  clientApp = initializeApp(
    { projectId, apiKey: "demo-key", appId: "demo-app" },
    "semantic-search-e2e",
  );
  const auth = getAuth(clientApp);
  connectAuthEmulator(auth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, {
    disableWarnings: true,
  });
  memberId = (await signInAnonymously(auth)).user.uid;
});

after(async () => {
  if (!emulatorAvailable) return;
  await database.doc(`memberships/${memberId}`).delete();
  await database.doc(`households/${householdId}/items/${itemId}`).delete();
  await database.doc(`households/${householdId}/searchIndex/${itemId}`).delete();
  await database.doc(`households/${otherHouseholdId}/items/${otherItemId}`).delete();
  await database.doc(`households/${otherHouseholdId}/searchIndex/${otherItemId}`).delete();
  await database.doc(`households/${householdId}/items/${householdId}`).delete();
  await database.doc(`households/${householdId}/items/${moveSourceId}`).delete();
  await database.doc(`households/${householdId}/items/${moveChildId}`).delete();
  await database.doc(`households/${householdId}/items/${moveTargetId}`).delete();
  await database.doc(`households/${householdId}`).delete();
  await database.doc(`households/${otherHouseholdId}`).delete();
  await deleteApp(clientApp);
});

test(
  "Item writes refresh the index and the authenticated callable stays Household-scoped",
  { skip: !emulatorAvailable, timeout: 20_000 },
  async () => {
    await database.doc(`households/${householdId}`).set({
      name: "Search Household",
      ownerMemberId: memberId,
    });
    await database.doc(`households/${otherHouseholdId}`).set({
      name: "Other Household",
      ownerMemberId: "e2e-other-owner",
    });
    await database.doc(`memberships/${memberId}`).set({ householdId });
    await database.doc(`households/${householdId}/items/${itemId}`).set({
      name: "Mantel clock",
      parentItemId: householdId,
      tags: ["timepiece"],
      description: "A decorative clock used to tell the time.",
    });
    await database.doc(`households/${otherHouseholdId}/items/${otherItemId}`).set({
      name: "Mantel clock",
      parentItemId: otherHouseholdId,
      tags: ["timepiece"],
      description: "A private decorative clock used to tell the time.",
    });

    let originalSourceHash;
    await waitFor(async () => {
      const index = await database
        .doc(`households/${householdId}/searchIndex/${itemId}`)
        .get();
      originalSourceHash = index.data()?.sourceHash;
      const otherIndex = await database
        .doc(`households/${otherHouseholdId}/searchIndex/${otherItemId}`)
        .get();
      return typeof originalSourceHash === "string" && otherIndex.exists;
    });

    const functions = getFunctions(clientApp, "australia-southeast1");
    const [host, port] = (process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001")
      .split(":");
    connectFunctionsEmulator(functions, host, Number(port));
    const result = await httpsCallable(functions, "searchInventory")({ query: "clock" });

    assert.deepEqual(result.data, { itemIds: [itemId] });

    await database.doc(`households/${householdId}/items/${itemId}`).update({
      description: "An updated clock description.",
    });
    await waitFor(async () => {
      const updatedIndex = await database
        .doc(`households/${householdId}/searchIndex/${itemId}`)
        .get();
      return updatedIndex.data()?.sourceHash !== originalSourceHash;
    });

    await database.doc(`households/${householdId}/items/${itemId}`).delete();
    await waitFor(async () => {
      const deletedIndex = await database
        .doc(`households/${householdId}/searchIndex/${itemId}`)
        .get();
      return !deletedIndex.exists;
    });
  },
);

test(
  "an authenticated Member moves an Item subtree transactionally",
  { skip: !emulatorAvailable, timeout: 20_000 },
  async () => {
    await database.doc(`households/${householdId}`).set({
      name: "Search Household",
      ownerMemberId: memberId,
    });
    await database.doc(`memberships/${memberId}`).set({ householdId });
    await database.doc(`households/${householdId}/items/${householdId}`).set({
      householdId,
      parentItemId: null,
    });
    await database.doc(`households/${householdId}/items/${moveSourceId}`).set({
      householdId,
      parentItemId: householdId,
      name: "Source",
      tags: [],
      description: "",
    });
    await database.doc(`households/${householdId}/items/${moveChildId}`).set({
      householdId,
      parentItemId: moveSourceId,
      name: "Child",
      tags: [],
      description: "",
    });
    await database.doc(`households/${householdId}/items/${moveTargetId}`).set({
      householdId,
      parentItemId: householdId,
      name: "Target",
      tags: [],
      description: "",
    });

    const functions = getFunctions(clientApp, "australia-southeast1");
    const [host, port] = (process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001")
      .split(":");
    connectFunctionsEmulator(functions, host, Number(port));
    const move = httpsCallable(functions, "moveInventoryItem");
    const result = await move({
      householdId,
      itemId: moveSourceId,
      newParentItemId: moveTargetId,
    });

    assert.deepEqual(result.data, {
      itemId: moveSourceId,
      parentItemId: moveTargetId,
    });
    const source = await database
      .doc(`households/${householdId}/items/${moveSourceId}`)
      .get();
    const child = await database
      .doc(`households/${householdId}/items/${moveChildId}`)
      .get();
    assert.equal(source.data()?.parentItemId, moveTargetId);
    assert.equal(source.data()?.name, "Source");
    assert.equal(source.data()?.updatedById, memberId);
    assert.equal(child.data()?.parentItemId, moveSourceId);
  },
);

test(
  "Account Deletion revokes access anonymizes shared attribution and deletes Authentication",
  { skip: !emulatorAvailable, timeout: 30_000 },
  async () => {
    const deletionHouseholdId = "e2e-deletion-household";
    const deletionMemberId = "e2e-deletion-member";
    const deletionEmail = "delete-me@example.com";
    const deletionItemId = "e2e-deletion-item";
    const adminAuthentication = getAdminAuth();
    await adminAuthentication.createUser({
      uid: deletionMemberId,
      email: deletionEmail,
      emailVerified: true,
      password: "deletion-test-password",
    });
    const deletionApp = initializeApp(
      { projectId, apiKey: "demo-key", appId: "deletion-demo-app" },
      "account-deletion-e2e",
    );
    const deletionAuthentication = getAuth(deletionApp);
    connectAuthEmulator(
      deletionAuthentication,
      `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`,
      { disableWarnings: true },
    );
    await signInWithEmailAndPassword(
      deletionAuthentication,
      deletionEmail,
      "deletion-test-password",
    );

    await database.doc(`households/${deletionHouseholdId}`).set({
      name: "Deletion Home",
      ownerMemberId: "e2e-deletion-owner",
      ownerEmail: "owner@example.com",
      useTags: false,
    });
    await database.doc("memberships/e2e-deletion-owner").set({
      householdId: deletionHouseholdId,
      role: "owner",
    });
    await database.doc(`memberships/${deletionMemberId}`).set({
      householdId: deletionHouseholdId,
      role: "member",
    });
    await database.doc(
      `households/${deletionHouseholdId}/access/${deletionEmail}`,
    ).set({
      householdId: deletionHouseholdId,
      email: deletionEmail,
      memberId: deletionMemberId,
      memberDisplayName: "Delete Me",
      memberEmail: deletionEmail,
    });
    await database.doc(
      `households/${deletionHouseholdId}/items/${deletionItemId}`,
    ).set({
      householdId: deletionHouseholdId,
      name: "Shared Item",
      parentItemId: deletionHouseholdId,
      tags: [],
      description: "Shared description",
      createdById: deletionMemberId,
      createdByDisplayName: "Delete Me",
      updatedById: deletionMemberId,
      updatedByDisplayName: "Delete Me",
    });

    const deletionFunctions = getFunctions(deletionApp, "australia-southeast1");
    const [host, port] = (process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001")
      .split(":");
    connectFunctionsEmulator(deletionFunctions, host, Number(port));
    const preview = await httpsCallable(
      deletionFunctions,
      "previewAccountDeletion",
    )();
    assert.deepEqual(preview.data, {
      deletesHousehold: false,
      householdName: null,
      memberCount: 0,
      itemCount: 0,
    });
    await httpsCallable(deletionFunctions, "requestAccountDeletion")();

    await waitFor(async () => {
      const [job, membership] = await Promise.all([
        database.doc(`accountDeletionJobs/${deletionMemberId}`).get(),
        database.doc(`memberships/${deletionMemberId}`).get(),
      ]);
      const authenticationDeleted = await adminAuthentication
        .getUser(deletionMemberId)
        .then(() => false, (error) => error.code === "auth/user-not-found");
      return !job.exists && !membership.exists && authenticationDeleted;
    });

    const [access, item] = await Promise.all([
      database.doc(
        `households/${deletionHouseholdId}/access/${deletionEmail}`,
      ).get(),
      database.doc(
        `households/${deletionHouseholdId}/items/${deletionItemId}`,
      ).get(),
    ]);
    assert.equal(access.exists, false);
    assert.equal(item.data()?.createdById, "former-member");
    assert.equal(item.data()?.createdByDisplayName, "Former member");
    assert.equal(item.data()?.updatedById, "former-member");
    assert.equal(item.data()?.updatedByDisplayName, "Former member");

    await database.recursiveDelete(database.doc(`households/${deletionHouseholdId}`));
    await database.doc("memberships/e2e-deletion-owner").delete();
    await deleteApp(deletionApp);
  },
);

test(
  "Owner Account Deletion reports visible counts and deletes the shared Household",
  { skip: !emulatorAvailable, timeout: 30_000 },
  async () => {
    const ownerId = "e2e-owner-deletion-member";
    const otherMemberId = "e2e-owner-deletion-other-member";
    const ownerEmail = "delete-owner@example.com";
    const deletionHouseholdId = "e2e-owner-deletion-household";
    const adminAuthentication = getAdminAuth();
    await adminAuthentication.createUser({
      uid: ownerId,
      email: ownerEmail,
      emailVerified: true,
      password: "owner-deletion-test-password",
    });
    const deletionApp = initializeApp(
      { projectId, apiKey: "demo-key", appId: "owner-deletion-demo-app" },
      "owner-account-deletion-e2e",
    );
    const deletionAuthentication = getAuth(deletionApp);
    connectAuthEmulator(
      deletionAuthentication,
      `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`,
      { disableWarnings: true },
    );
    await signInWithEmailAndPassword(
      deletionAuthentication,
      ownerEmail,
      "owner-deletion-test-password",
    );

    await database.doc(`households/${deletionHouseholdId}`).set({
      name: "Owner Deletion Home",
      ownerMemberId: ownerId,
      ownerEmail,
      useTags: false,
    });
    await database.doc(`memberships/${ownerId}`).set({
      householdId: deletionHouseholdId,
      role: "owner",
    });
    await database.doc(`memberships/${otherMemberId}`).set({
      householdId: deletionHouseholdId,
      role: "member",
    });
    await database.doc(
      `households/${deletionHouseholdId}/items/${deletionHouseholdId}`,
    ).set({ householdId: deletionHouseholdId, parentItemId: null });
    await database.doc(
      `households/${deletionHouseholdId}/items/visible-item`,
    ).set({ householdId: deletionHouseholdId, parentItemId: deletionHouseholdId });
    const photo = getAdminStorage()
      .bucket(`${projectId}.appspot.com`)
      .file(`households/${deletionHouseholdId}/items/visible-item.webp`);
    await photo.save(Buffer.from("test-photo"), { contentType: "image/webp" });

    const deletionFunctions = getFunctions(deletionApp, "australia-southeast1");
    const [host, port] = (process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001")
      .split(":");
    connectFunctionsEmulator(deletionFunctions, host, Number(port));
    const preview = await httpsCallable(
      deletionFunctions,
      "previewAccountDeletion",
    )();
    assert.deepEqual(preview.data, {
      deletesHousehold: true,
      householdName: "Owner Deletion Home",
      memberCount: 2,
      itemCount: 1,
    });
    await httpsCallable(deletionFunctions, "requestAccountDeletion")({
      householdName: "Owner Deletion Home",
    });

    await waitFor(async () => {
      const [job, household, ownerMembership, otherMembership] = await Promise.all([
        database.doc(`accountDeletionJobs/${ownerId}`).get(),
        database.doc(`households/${deletionHouseholdId}`).get(),
        database.doc(`memberships/${ownerId}`).get(),
        database.doc(`memberships/${otherMemberId}`).get(),
      ]);
      const authenticationDeleted = await adminAuthentication
        .getUser(ownerId)
        .then(() => false, (error) => error.code === "auth/user-not-found");
      const [photoExists] = await photo.exists();
      return !job.exists && !household.exists && !ownerMembership.exists &&
        !otherMembership.exists && authenticationDeleted && !photoExists;
    });

    await deleteApp(deletionApp);
  },
);

async function waitFor(predicate) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.fail("Timed out waiting for the expected emulator state.");
}
