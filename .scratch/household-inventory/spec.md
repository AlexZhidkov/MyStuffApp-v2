# Household Inventory Android Prototype

Status: Draft
Last updated: 2026-07-29

## Product summary

MyStuff is a privately distributed Android prototype for a shared Household Inventory. A Household is the root Item of one tree, and every other Item appears beneath exactly one Parent Item. Members can add, browse, search, edit, move, and delete Items in the same Inventory.

The prototype is intended to test whether a generic Item tree, camera-first capture, and path-based search results help people record belongings and later find where they are.

## Problem

Households accumulate belongings across rooms, cupboards, drawers, boxes, sheds, and garages. The path to an infrequently used Item is often remembered by only one person—or forgotten entirely. This causes repeated searching, duplicate purchases, and frustration.

Memory, notes, and spreadsheets are difficult to keep organized and inconvenient to update while moving around the home.

## Prototype hypothesis

A shared, generic Item tree with camera-first capture helps Household Members record belongings and later find where they are.

The prototype will be evaluated through direct Member feedback rather than a numeric pass/fail threshold or in-app analytics.

## V1 scope

V1 is a private prototype distributed only to invited testers. It is not a public Play Store release and is not expected to meet production launch standards.

V1 includes:

- Google sign-in
- One Household membership per Member
- Household creation, invitations, and Member removal
- A generic tree of Items without separate Item types
- Camera-first Item creation with an optional photo
- Browsing, editing, moving, and deleting Items
- Search across Item names, Tags, and descriptions
- Connected synchronization between Household Members
- Minimum Firebase authorization boundaries

## Explicitly out of scope for v1

- Multiple Household memberships and Household switching
- Ownership transfer
- A Member leaving a Household without being removed by the Household Owner
- App-account deletion and data export
- Offline guarantees or custom offline behavior beyond Firebase defaults
- Custom concurrent-change detection or conflict handling beyond Firebase defaults
- Accessibility requirements or accessibility certification
- Public-store distribution or production-readiness review
- Production-scale performance targets
- Durable form drafts or recovery after backgrounding, process death, force-stop, sign-out, or restart
- Duplicate-Item actions
- Manual child ordering, favorites, recent searches, and recently viewed Items
- Categories and quantities
- Receipts, warranties, valuations, depreciation, or insurance reports
- Barcode or QR-code scanning
- Lending, shopping lists, consumable stock alerts, or smart-home integrations
- Web or iOS clients
- Exact position tracking using Bluetooth, GPS, or other sensors

## Product principles

- A Household is represented by the root Item of its Inventory.
- Every other Item has exactly one current Parent Item.
- Areas, containers, belongings, and counted groups use the same Item model.
- An Item stores no Parent Item history.
- Tags are the only classification mechanism.
- Duplicate Item names are allowed, including beneath the same Parent Item.
- All Members have equal permissions for Inventory operations.
- Only the Household Owner manages membership and deletes the Household.
- Delete means permanent removal; v1 has no trash or restore workflow.

## Participants

### Household Owner

The Member who creates the Household. The Household Owner has the same Inventory permissions as every other Member and additionally can:

- Invite a Member
- Revoke a pending invitation
- View and remove Members
- Delete the Household

Ownership transfer is not supported in v1.

### Household Member

A person who joins the Household using an invitation tied to their Google Account. A Member can browse, search, add, edit, move, and delete Items and can rename the Household.

## Core journeys

### Sign in and enter a Household

1. A person signs in using their Google Account.
2. If they already belong to a Household, the app opens that Household.
3. If they do not belong to a Household, the app offers Household creation and invitation acceptance.
4. A Member who already belongs to a Household cannot accept an invitation to another Household in v1.

### Set up a Household

1. A signed-in person with no current Household creates and names a Household.
2. The app creates the Household as the root Item.
3. The creator becomes the Household Owner.
4. The Household Owner adds child Items such as Kitchen, Garage, and Shed.
5. Members may add deeper child Items such as cupboards, shelves, boxes, tools, and belongings.

### Invite a Member

1. The Household Owner enters the intended Member's Google email address.
2. The app creates a single-use invitation tied to that email address.
3. The intended person opens the invitation and signs in with the same Google Account.
4. If the invitation is valid and the person has no other Household membership, they join the Household.

### Add an Item

1. A Member selects **Add item**.
2. The app opens the camera before the details form.
3. The Member takes a photo or continues without one.
4. After capture, the Member may retake the photo, crop and use it, or continue without it.
5. The app presents the Item form.
6. The Member enters a required name and may add a description and Tags.
7. The Member confirms or changes the Parent Item.
8. The Member saves the Item and sees it beneath its Parent Item.

If camera permission is denied or the camera is unavailable, the app continues directly to the Item form without a photo. Item creation must not depend on camera permission.

### Find an Item

1. A Member enters one query in the Household search.
2. The app searches Item names, Tags, and descriptions together.
3. Results show the Item name, photo thumbnail when available, and Item Path.
4. The Member opens a result to view its details and immediate Child Items.

### Browse the Inventory

1. A Member opens the Household root.
2. They navigate through Child Items.
3. They select an Item to see its details, Item Path, and immediate Child Items.

V1 does not guarantee a particular ordering for Child Items.

### Move an Item

1. A Member opens an Item and chooses **Move**.
2. They select a new Parent Item and confirm.
3. The Item and all its descendants appear beneath the new Parent Item.
4. Connected Household Members see the changed paths without a manual refresh.

### Delete a subtree

1. A Member chooses to delete a non-root Item that has Child Items.
2. The app names the selected Item, shows its Item Path, and shows the exact number of descendants that will also be deleted.
3. The app states that the selected Item, its descendants, and their photos will be permanently deleted.
4. The Member types the selected Item's name exactly as displayed.
5. Deletion becomes available only after the entered name matches.

## Functional requirements

### Authentication, Household, and membership

| ID     | Requirement                                                                                                                                                                                                                       |
| ------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ACC-01 | A person can sign in and sign out using their Google Account; no other authentication provider is supported.                                                                                                                      |
| ACC-02 | A signed-in person with no Household membership can create a named Household and becomes its Household Owner.                                                                                                                     |
| ACC-03 | A Member can belong to no more than one Household in v1.                                                                                                                                                                          |
| ACC-04 | A Member who already belongs to a Household cannot create another Household or accept another Household invitation.                                                                                                               |
| ACC-05 | The Household Owner can create an invitation tied to one Google email address.                                                                                                                                                    |
| ACC-06 | An invitation is single-use, revocable, and valid for seven days. Accepting, revoking, or replacing it invalidates the original link immediately.                                                                                 |
| ACC-07 | Invitation acceptance requires sign-in with the Google Account whose email address matches the invitation.                                                                                                                        |
| ACC-08 | The Household Owner can view and remove Household Members but cannot remove themselves.                                                                                                                                           |
| ACC-09 | Removing a Member retains the Household's Items and their attribution metadata but removes that Member's backend access to the Household.                                                                                         |
| ACC-10 | All Members can view, add, edit, move, and delete non-root Items and can rename the Household.                                                                                                                                    |
| ACC-11 | Only the Household Owner can manage invitations and membership or delete the Household.                                                                                                                                           |
| ACC-12 | Household names are trimmed, must contain 1–100 Unicode characters, and preserve their entered capitalization.                                                                                                                    |
| ACC-13 | Household deletion is a separate settings action that shows the total Member and Item counts, states that all Household data and photos will be permanently removed, and requires the Household Owner to type the Household name. |

### Item tree and data

| ID     | Requirement                                                                                                                                            |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| ITM-01 | Creating a Household creates exactly one root Item using the Household name.                                                                           |
| ITM-02 | The Household is the only Item without a Parent Item.                                                                                                  |
| ITM-03 | Every non-root Item stores exactly one current Parent Item and no Parent Item history.                                                                 |
| ITM-04 | Any Item can have zero or more Child Items. Child Items are derived from their Parent Item relationships.                                              |
| ITM-05 | The Item relationships form one connected, acyclic tree rooted at the Household.                                                                       |
| ITM-06 | The tree has no product-defined maximum depth in v1.                                                                                                   |
| ITM-07 | V1 does not guarantee a particular ordering for Child Items.                                                                                           |
| ITM-08 | Every Item has an immutable internal identity. Names and Item Paths are labels and are not unique identifiers.                                         |
| ITM-09 | Duplicate Item names are allowed, including duplicate names beneath the same Parent Item.                                                              |
| ITM-10 | A non-root Item has a required name, one optional photo, an optional description, and optional Tags. It has no Category or quantity.                   |
| ITM-11 | An Item name is trimmed, must contain 1–100 Unicode characters, and preserves its entered capitalization.                                              |
| ITM-12 | An Item description is optional and contains at most 2,000 characters.                                                                                 |
| ITM-13 | An Item may have at most 20 Tags. Each Tag is trimmed and contains 1–40 Unicode characters.                                                            |
| ITM-14 | Tags compare case- and diacritic-insensitively, preserve entered capitalization for display, and cannot be duplicated on one Item after normalization. |
| ITM-15 | Existing Household Tags may be suggested during entry, but Members can create new Tags.                                                                |
| ITM-16 | The Household root has a name but no photo, description, Tags, or Parent Item and does not appear in search results.                                   |
| ITM-17 | Any Member can rename the Household root. The root cannot be moved or deleted through Item actions.                                                    |
| ITM-18 | An Item records created and last-updated timestamps plus display-name snapshots for its creating and last-updating Members.                            |

### Item creation and editing

| ID     | Requirement                                                                                                                            |
| ------ | -------------------------------------------------------------------------------------------------------------------------------------- |
| EDT-01 | Selecting **Add item** opens the camera before the Item details form for every non-root Item.                                          |
| EDT-02 | Camera permission denial or camera unavailability continues to the Item form and does not block Item creation.                         |
| EDT-03 | After photo capture, a Member can retake the photo, crop and use it, or continue without it.                                           |
| EDT-04 | Cancelling the crop returns to the post-capture choice rather than cancelling Item creation.                                           |
| EDT-05 | From an Item detail screen, **Add item** defaults the Parent Item to the displayed Item.                                               |
| EDT-06 | From Home, **Add item** defaults the Parent Item to the Household root.                                                                |
| EDT-07 | From Search, **Add item** defaults to the Parent Item of the currently opened result, or to the Household root when no result is open. |
| EDT-08 | A Member can change the proposed Parent Item before saving.                                                                            |
| EDT-09 | A Member can view and edit a non-root Item's name, photo, description, and Tags.                                                       |
| EDT-10 | V1 need not recover unsaved form data after backgrounding, process recreation, force-stop, sign-out, restart, or navigation away.      |

### Item paths and movement

| ID     | Requirement                                                                                                           |
| ------ | --------------------------------------------------------------------------------------------------------------------- |
| MOV-01 | A Member can view an Item's Item Path from the Household root.                                                        |
| MOV-02 | Compact views may collapse middle path segments, but the complete Item Path must be available on demand.              |
| MOV-03 | A Member can move a non-root Item and all its descendants beneath a different Parent Item in the same Household.      |
| MOV-04 | A move changes only the moved Item's Parent Item and preserves the Item and its descendants.                          |
| MOV-05 | The app prevents an Item from being moved beneath itself or any of its descendants.                                   |
| MOV-06 | The app rejects a move if the Item or chosen Parent Item no longer exists or no longer belongs to the same Household. |

### Deletion

| ID     | Requirement                                                                                                                                                                                                            |
| ------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| DEL-01 | Any Member can permanently delete a childless non-root Item after confirmation.                                                                                                                                        |
| DEL-02 | Any Member can permanently delete a non-root Item and all its descendants after the enhanced subtree confirmation.                                                                                                     |
| DEL-03 | Enhanced subtree confirmation names the selected Item, presents its Item Path, shows the exact descendant count, states that deletion is permanent, and requires the Member to type the selected Item's displayed name. |
| DEL-04 | Deleted Items disappear from the Inventory and search results.                                                                                                                                                         |
| DEL-05 | Deleting an Item, subtree, or Household also permanently deletes every associated photo.                                                                                                                               |
| DEL-06 | V1 has no trash, undo, restore, or Parent Item history.                                                                                                                                                                |

### Search

| ID     | Requirement                                                                                                                     |
| ------ | ------------------------------------------------------------------------------------------------------------------------------- |
| SRC-01 | One search input queries non-root Item names, Tags, and descriptions together.                                                  |
| SRC-02 | Search uses case- and diacritic-insensitive substring matching.                                                                 |
| SRC-03 | Search does not provide fuzzy or typo-tolerant matching in v1.                                                                  |
| SRC-04 | Results rank matching fields in this order: name, Tag, description.                                                             |
| SRC-05 | Within each field priority, results rank exact matches before prefix matches and prefix matches before other substring matches. |
| SRC-06 | Results show the Item name, photo thumbnail when available, and Item Path.                                                      |
| SRC-07 | A compact result may collapse middle Item Path segments, but the full Item Path is available on demand.                         |

### Connected synchronization

| ID     | Requirement                                                                                                                                        |
| ------ | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| SYN-01 | Changes made by a connected Member become visible to other connected Members without a manual refresh.                                             |
| SYN-02 | While a form remains open, the app communicates whether a save is in progress, succeeded, or failed.                                               |
| SYN-03 | A failed save can be retried in place while that form remains open.                                                                                |
| SYN-04 | V1 makes no offline availability or offline-write guarantee beyond behavior supplied by Firebase by default.                                       |
| SYN-05 | Offline cache behavior, including how quickly a disconnected removed Member loses access to cached Household data, is an explicitly deferred risk. |

## Information model

### Household

The Household is the root Item and boundary of one shared Inventory.

- Immutable identity
- Name
- Household Owner
- Current Members
- Pending invitations
- Created timestamp

There is exactly one Household root in each Inventory.

### Member

- Google Account identity
- Display name
- Membership in zero or one Household
- Household role: Owner or Member
- Membership status

### Invitation

- Immutable identity
- Household identity
- Intended Google email address
- Created and expiry timestamps
- Status: pending, accepted, revoked, expired, or replaced

An invitation expires seven days after creation and can be accepted only once.

### Item

- Immutable identity
- Household identity
- Name
- Parent Item identity, required except for the Household root
- Optional photo reference, except for the Household root
- Optional description
- Optional Tags
- Created and last-updated timestamps
- Creating and last-updating Member display-name snapshots

The model must preserve these invariants:

- The Household is the only Item without a Parent Item.
- Every other Item has exactly one current Parent Item.
- Every Item is reachable from the Household.
- An Item cannot be its own ancestor or descendant.
- A Move preserves the moved Item and its descendants.
- Names and Item Paths are not used as Item identities.

## UX requirements

- The home screen prioritizes Household search and the immediate Child Items of the Household root.
- The current Household name is always clear.
- The primary **Add item** action is available from Home, Search, and Item detail screens.
- The camera-first flow always permits continuing without a photo.
- The Item form clearly shows the selected Parent Item.
- Item details visually distinguish the selected Item from its Child Items.
- Empty states explain the next useful action.
- Complete Item Paths remain available even when compact views collapse middle segments.
- Destructive actions explain exactly what data will be permanently removed.
- The app follows familiar Android navigation and system-back behavior.
- V1 does not guarantee preservation of unsaved form data after leaving the active flow.

## Privacy and authorization requirements

- Household data is readable and writable only by current Household Members.
- Firebase authorization rules enforce the Household boundary independently of the client UI.
- All current Members have equal access to Inventory operations.
- Only the Household Owner can manage invitations, remove Members, or delete the Household.
- Invitations are restricted to the intended Google Account, expire after seven days, are revocable, and cannot be reused.
- Removing a Member blocks their backend access to the Household.
- Camera permission is requested only from the Member-initiated **Add item** or photo-edit flow.
- Formal security review, offline cache revocation, account deletion, and production credential-hardening requirements are deferred beyond v1.

## Technical constraints

- The client is an Android app.
- Firebase is the backend platform.
- Firebase Authentication uses Google as the only sign-in provider.
- The target device for prototype checks is a Google Pixel 8 Pro running Android 17.
- Distribution is private/internal.

## Prototype evaluation

The prototype is evaluated through facilitated sessions and Member feedback, not numeric success metrics.

A representative session should involve at least two Members sharing one Household. The session should exercise:

- Creating a tree at least three levels deep
- Adding approximately 20 real Items through the camera-first flow
- Finding several named Items without coaching
- Interpreting a deep Item Path
- Editing and moving an Item
- Observing a change made by another connected Member
- Deleting both a childless Item and a subtree

Record confusion, incorrect assumptions, friction, and participant comments. The results inform the next specification revision; v1 has no numeric usability pass threshold.

## Verification strategy

Automated tests cover correctness, including:

- Firebase authorization boundaries
- One-Household-per-Member enforcement
- Account-bound invitation acceptance and expiry
- Tree connectivity and cycle prevention
- Root movement and Item-delete prevention
- Item creation and validation
- Subtree movement
- Childless and subtree deletion
- Photo cleanup after deletion
- Search normalization, field priority, and match ranking

Performance and responsiveness are checked manually on the target device. V1 has no hard Item-count, Member-count, launch-time, search-time, or task-time acceptance threshold.

## V1 acceptance criteria

The private prototype is ready for evaluation when:

- A person can sign in with a Google Account and create a Household when they have no current Household.
- The Household Owner can issue a seven-day, account-bound invitation that the intended Google Account can accept once.
- A Member cannot create or join a second Household.
- Two connected Members see the same Inventory and receive each other's changes without manual refresh.
- A Member can build and browse a multi-level Item tree with duplicate names and no Item types.
- **Add item** opens the camera first and still works when a photo is skipped, permission is denied, or the camera is unavailable.
- A Member can search names, Tags, and descriptions from one input and see each result's Item Path.
- A Member can edit an Item and move its complete subtree without violating tree invariants.
- Any Member can permanently delete a childless Item.
- Any Member can permanently delete a subtree after typing the Item name and reviewing the exact descendant count.
- Item, subtree, and Household deletion also removes associated photos.
- The Household Owner can remove another Member and can delete the Household through Owner-only settings.
- Automated correctness tests pass.
- The prototype can complete the manual evaluation session on the target device.

## Known risks and deliberate v1 limitations

| Risk or limitation                                                       | V1 treatment                                                                                                                                        |
| ------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| Camera-first capture may slow structural Item creation                   | Keep camera-first because it is part of the hypothesis; observe Member feedback.                                                                    |
| A generic tree may not match how every Household thinks about belongings | Preserve one Item model and observe where Members become confused.                                                                                  |
| Duplicate sibling names can produce visually identical Item Paths        | Use immutable identities internally and show available context such as photo and description; observe whether Members need stronger disambiguation. |
| Any Member can permanently delete a large subtree                        | Require exact-count and typed-name confirmation; no restore exists in v1.                                                                            |
| Very large or deep trees may exceed a simple prototype implementation    | Do not add an artificial depth cap or production-scale acceptance target in v1.                                                                     |
| Firebase default offline behavior may expose incomplete or stale data    | Make no offline guarantee and defer custom caching and revocation behavior.                                                                         |
| Unsaved form data may be lost when the active flow is interrupted        | Accept this limitation for v1 and keep only an in-place retry while the form remains open.                                                          |
| Accessibility has not been designed or validated                         | Accessibility work is explicitly deferred beyond v1.                                                                                                |
| Private Household data could expose sensitive locations                  | Retain Firebase authorization rules and account-bound invitations even in the prototype.                                                            |

## Future considerations

- Multiple Households and Household switching
- Ownership transfer, self-leave, and account deletion
- Offline-first browsing and writes
- Recoverable deletion, audit history, and production-scale subtree deletion
- Accessibility design and validation
- Stable child ordering, favorites, recent searches, and recently viewed Items
- Durable drafts
- Production-scale performance targets and public release readiness
- QR labels and barcode-assisted entry
- Receipts, warranty reminders, and purchase information
- Insurance export and valuation
- Borrowing and lending
- Low-stock reminders
- Bulk import and export
- Web and iOS clients
