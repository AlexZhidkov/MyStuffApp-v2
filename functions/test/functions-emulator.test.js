import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { deleteApp, initializeApp } from "firebase/app";
import {
  connectAuthEmulator,
  getAuth,
  GoogleAuthProvider,
  signInAnonymously,
  signInWithCredential,
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
import { getFirestore } from "firebase-admin/firestore";

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
const invitationHouseholdId = "e2e-invitation-household";
const invitationId = "e2e-invitation";
const invitedSubject = "e2e-invited-member";
let clientApp;
let database;
let memberId;
let invitedMemberId;

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
  if (invitedMemberId !== undefined) {
    await database.doc(`memberships/${invitedMemberId}`).delete();
  }
  await database.doc(`invitations/${invitationId}`).delete();
  await database.doc(`households/${invitationHouseholdId}/items/${invitationHouseholdId}`).delete();
  await database.doc(`households/${invitationHouseholdId}`).delete();
  await deleteApp(clientApp);
});

test(
  "the intended Google Account accepts an invitation once and gains Household access",
  { skip: !emulatorAvailable, timeout: 20_000 },
  async () => {
    await database.doc(`households/${invitationHouseholdId}`).set({
      name: "Invitation Home",
      ownerMemberId: "invitation-owner",
      rootItemId: invitationHouseholdId,
    });
    await database
      .doc(`households/${invitationHouseholdId}/items/${invitationHouseholdId}`)
      .set({ householdId: invitationHouseholdId, parentItemId: null });
    await database.doc(`invitations/${invitationId}`).set({
      householdId: invitationHouseholdId,
      intendedEmail: "sam@example.com",
      createdAt: new Date(Date.now() - 60_000),
      expiresAt: new Date(Date.now() + 60_000),
      status: "pending",
      replacesInvitationId: null,
      replacedByInvitationId: null,
    });

    const invitedApp = initializeApp(
      { projectId, apiKey: "demo-key", appId: "invited-app" },
      "invitation-acceptance-e2e",
    );
    try {
      const auth = getAuth(invitedApp);
      connectAuthEmulator(auth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, {
        disableWarnings: true,
      });
      const credential = await signInWithCredential(
        auth,
        GoogleAuthProvider.credential(unsignedGoogleIdToken()),
      );
      invitedMemberId = credential.user.uid;

      const functions = getFunctions(invitedApp, "australia-southeast1");
      const [functionsHost, functionsPort] =
        (process.env.FUNCTIONS_EMULATOR_HOST ?? "127.0.0.1:5001").split(":");
      connectFunctionsEmulator(functions, functionsHost, Number(functionsPort));
      const accept = httpsCallable(functions, "acceptHouseholdInvitation");

      const result = await accept({ invitationId });

      assert.deepEqual(result.data, { householdId: invitationHouseholdId });
      const membership = await database.doc(`memberships/${invitedMemberId}`).get();
      const invitation = await database.doc(`invitations/${invitationId}`).get();
      assert.deepEqual(membership.data(), {
        householdId: invitationHouseholdId,
        role: "member",
      });
      assert.equal(invitation.data()?.status, "accepted");
      assert.equal(invitation.data()?.acceptedByMemberId, invitedMemberId);

      const clientDatabase = getClientFirestore(invitedApp);
      const [firestoreHost, firestorePort] =
        (process.env.FIRESTORE_EMULATOR_HOST ?? "127.0.0.1:8080").split(":");
      connectFirestoreEmulator(clientDatabase, firestoreHost, Number(firestorePort));
      await getDoc(doc(
        clientDatabase,
        `households/${invitationHouseholdId}/items/${invitationHouseholdId}`,
      ));

      await assert.rejects(accept({ invitationId }), { code: "functions/already-exists" });

      const unrelatedDatabase = getClientFirestore(clientApp);
      connectFirestoreEmulator(unrelatedDatabase, firestoreHost, Number(firestorePort));
      await assert.rejects(getDoc(doc(
        unrelatedDatabase,
        `households/${invitationHouseholdId}/items/${invitationHouseholdId}`,
      )));
    } finally {
      await deleteApp(invitedApp);
    }
  },
);

test(
  "Item writes refresh the index and the authenticated callable stays Household-scoped",
  { skip: !emulatorAvailable, timeout: 20_000 },
  async () => {
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

async function waitFor(predicate) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.fail("Timed out waiting for the Item Search index record.");
}

function unsignedGoogleIdToken() {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const issuedAt = Math.floor(Date.now() / 1000);
  return `${encode({ alg: "none", typ: "JWT" })}.${encode({
    iss: "https://accounts.google.com",
    aud: "demo-client",
    sub: invitedSubject,
    email: "sam@example.com",
    email_verified: true,
    iat: issuedAt,
    exp: issuedAt + 3600,
  })}.`;
}
