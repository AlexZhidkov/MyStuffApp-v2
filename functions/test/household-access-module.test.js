import assert from "node:assert/strict";
import { test } from "node:test";
import {
  HouseholdAccessPermissionError,
  createHouseholdAccessModule,
} from "../src/household-access-module.js";

test("a verified Google email claims one matching Household Access atomically", async () => {
  const database = fakeDatabase({
    "households/household-1": household("Our Home"),
    "households/household-1/access/sam@example.com": access("sam@example.com"),
  });
  const module = createHouseholdAccessModule({ database });

  const result = await module.claimMatchingAccess({
    memberId: "member-2",
    authenticatedEmail: " Sam@Example.com ",
    displayName: "Sam",
  });

  assert.deepEqual(result, { householdId: "household-1" });
  assert.deepEqual(database.data("memberships/member-2"), {
    householdId: "household-1",
    role: "member",
    householdName: "Our Home",
    ownerMemberId: "member-1",
    ownerEmail: "owner@example.com",
    useTags: false,
  });
  assert.equal(database.data("households/household-1/access/sam@example.com").memberId, "member-2");
});

test("the one Household limit prevents a second access claim", async () => {
  const database = fakeDatabase({
    "memberships/member-2": { householdId: "household-2", role: "member" },
    "households/household-1/access/sam@example.com": access("sam@example.com"),
  });
  const module = createHouseholdAccessModule({ database });

  const result = await module.claimMatchingAccess({
    memberId: "member-2",
    authenticatedEmail: "sam@example.com",
    displayName: "Sam",
  });

  assert.deepEqual(result, { householdId: "household-2" });
  assert.equal(database.data("households/household-1/access/sam@example.com").memberId, null);
});

test("several matching Households use the first backend query result", async () => {
  const database = fakeDatabase({
    "households/household-2": household("Second Home", "member-3"),
    "households/household-2/access/sam@example.com": access("sam@example.com", "household-2"),
    "households/household-1": household("First Home"),
    "households/household-1/access/sam@example.com": access("sam@example.com"),
  });
  const module = createHouseholdAccessModule({ database });

  const result = await module.claimMatchingAccess({
    memberId: "member-2",
    authenticatedEmail: "sam@example.com",
    displayName: "Sam",
  });

  assert.deepEqual(result, { householdId: "household-2" });
  assert.equal(database.data("households/household-2/access/sam@example.com").memberId, "member-2");
  assert.equal(database.data("households/household-1/access/sam@example.com").memberId, null);
});

test("only the Household Owner can remove access and removal deletes membership", async () => {
  const database = fakeDatabase({
    "memberships/member-1": { householdId: "household-1", role: "owner" },
    "memberships/member-2": { householdId: "household-1", role: "member" },
    "households/household-1": household("Our Home"),
    "households/household-1/access/sam@example.com": access("sam@example.com", "household-1", "member-2"),
  });
  const module = createHouseholdAccessModule({ database });

  await module.removeAccess({
    memberId: "member-1",
    householdId: "household-1",
    email: " Sam@Example.com ",
  });
  assert.equal(database.data("memberships/member-2"), undefined);
  assert.equal(database.data("households/household-1/access/sam@example.com"), undefined);

  await assert.rejects(
    module.removeAccess({ memberId: "member-2", householdId: "household-1", email: "sam@example.com" }),
    HouseholdAccessPermissionError,
  );
});

function household(name, ownerMemberId = "member-1") {
  return {
    name,
    ownerMemberId,
    ownerEmail: "owner@example.com",
    useTags: false,
  };
}

function access(email, householdId = "household-1", memberId = null) {
  return {
    householdId,
    email,
    memberId,
    memberDisplayName: memberId === null ? null : "Sam",
    memberEmail: memberId === null ? null : email,
  };
}

function fakeDatabase(initialDocuments) {
  const documents = new Map(Object.entries(initialDocuments));
  return {
    doc(path) { return { path }; },
    collectionGroup(name) {
      return {
        name,
        where(field, op, value) {
          const filters = [{ field, op, value }];
          return {
            name,
            filters,
            where(nextField, nextOp, nextValue) {
              filters.push({ field: nextField, op: nextOp, value: nextValue });
              return this;
            },
          };
        },
      };
    },
    async runTransaction(work) {
      const writes = [];
      const transaction = {
        async get(reference) {
          if (reference.filters) {
            const docs = [...documents.entries()]
              .filter(([path, data]) => path.split("/").at(-2) === reference.name)
              .filter(([, data]) => reference.filters.every(({ field, value }) => data[field] === value))
              .map(([path, data]) => ({ ref: { path }, id: path.split("/").at(-1), data: () => data }));
            return { docs };
          }
          const data = documents.get(reference.path);
          return { exists: data !== undefined, data: () => data };
        },
        create(reference, data) { writes.push(() => documents.set(reference.path, data)); },
        update(reference, data) { writes.push(() => documents.set(reference.path, { ...documents.get(reference.path), ...data })); },
        delete(reference) { writes.push(() => documents.delete(reference.path)); },
      };
      const result = await work(transaction);
      writes.forEach((write) => write());
      return result;
    },
    data(path) { return documents.get(path); },
  };
}
