# Household Inventory Android App

Status: Draft
Last updated: 2026-07-28

## Product summary

MyStuff is an Android app that gives each Household one shared tree of the things in that home. The Household is the root Item, and every other Item appears beneath exactly one parent Item. Family members can add, browse, search, edit, and move Items within this tree, and a Member can belong to more than one Household.

## Problem

Families accumulate belongings across rooms, cupboards, drawers, boxes, sheds, and garages. The path to an infrequently used Item is often remembered by only one person—or forgotten entirely. This causes repeated searching, duplicate purchases, and frustration.

Existing approaches such as memory, notes, and spreadsheets are difficult to keep organized and inconvenient to update while moving around the house.

## Product vision

Any family member should be able to answer “Do we have this?” and “Where is it?” in a few seconds.

## Assumptions to validate

- A Member can belong to one or more Households.
- Every Member uses their own Google Account and Android device. Google is the only supported authentication provider.
- After signing in, a Member with access to multiple Households must select which Household to open.
- All Members have equal rights to view, add, edit, move, and delete Items. Only the Household Owner manages membership and Household settings.
- The Household itself is the root Item and has no parent.
- Every other Item has exactly one parent Item.
- An Item stores only its current Parent Item; previous Parent Items are not retained.
- Any Item can have zero or more child Items; there are no separate types for rooms, containers, or storage places.
- The Item tree can represent paths such as `Our Home → Garage → Metal cabinet → Drill`.
- Deleting an Item is permanent. Any Member can delete a childless Item or an entire subtree; subtree deletion requires an enhanced warning.
- An Item can have at most one optional photo in v1.
- Items do not have Categories; Tags are the only classification mechanism.
- Items do not store quantities.
- The app targets ordinary household organization rather than insurance-grade asset documentation.
- Internet access is required for household sharing, but previously loaded inventory remains readable during a temporary connection loss.

## Goals

### User goals

- Find an item without searching the house.
- Check whether the household already owns something.
- Record an Item quickly while standing near it.
- Keep parent-child relationships current when belongings move.
- Allow all family members to work from the same inventory.

### Business and product goals

- Deliver a small, trustworthy first release centered on finding items.
- Establish a data model that can later support receipts, warranties, and other item details.

## Non-goals for v1

- Home insurance reports or certified valuations
- Purchase receipts, warranties, depreciation, or financial reporting
- Barcode or QR-code scanning
- Lending items to people outside the household
- Shopping lists or consumable stock alerts
- Smart-home or retailer integrations
- Web or iOS clients
- Exact position tracking using Bluetooth, GPS, or other sensors

## Users

### Household owner

The person who initially sets up the Household tree, adds its first child Items, and invites family members. They value structure and want the inventory to stay useful over time.

### Household member

A family member who mainly searches for items but occasionally adds, edits, or moves them. They need the app to be understandable without learning the owner's filing system.

## Core user journeys

### Sign in and select a Household

1. A Member signs in using their Google Account.
2. The app loads the Households available to that Member.
3. If exactly one Household is available, the app opens it.
4. If multiple Households are available, the Member selects the Household they want to open before its Inventory is shown.

### Set up a household

1. A new Member signs in using their Google Account.
2. The Member creates and names a Household.
3. The app creates the Household as the root Item.
4. The Member adds child Items such as Kitchen, Garage, and Shed beneath the Household.
5. The Member optionally adds more child Items such as cupboards, shelves, boxes, tools, or belongings.
6. The Member invites family members.

### Add an item

1. A Member selects **Add item**.
2. The app immediately opens the camera.
3. The Member takes a photo of the Item or opts out of taking photo.
4. The app presents the captured photo for cropping, and the Member confirms the crop.
5. The app presents the Item details form.
6. The Member enters a required name and may enter an optional description and optional Tags.
7. The Member confirms or changes the Parent Item.
8. The Member saves the Item and sees it beneath the selected Parent Item.

Target: a basic item can be added in under 30 seconds.

### Find an item

1. A member opens the app and searches by item name or tag.
2. Matching results show the Item's name, photo when available, and full Item path.
3. The member opens a result to see its details.

Target: a known item can be found within 10 seconds.

### Browse the Item tree

1. A member opens the Household root Item.
2. They navigate through the Item tree.
3. They select an Item to see its immediate child Items and details.

### Move an item

1. A member opens an item.
2. They choose **Move**.
3. They select the new parent Item and confirm.
4. The Item and its descendants appear beneath the new parent, and their new paths are immediately visible to other household members.

## Technical constraints

- Firebase is the app's backend platform.
- Firebase Authentication uses Google as its only sign-in provider.
- The target device and operating system for v1 are a Google Pixel 8 Pro running Android 17.

## Functional requirements

Priorities use:

- **P0** — required for the first usable release
- **P1** — important if time allows; may follow immediately after launch
- **P2** — explicitly deferred

### Account and household

| ID     | Priority | Requirement                                                                                                       |
| ------ | -------- | ----------------------------------------------------------------------------------------------------------------- |
| ACC-01 | P0       | A person can sign in and sign out using their Google Account; no other authentication method is supported.        |
| ACC-02 | P0       | A signed-in Member can create a named Household root Item and becomes its owner.                                  |
| ACC-03 | P0       | The Household Owner can invite a family member using a shareable invitation.                                      |
| ACC-04 | P0       | An invited person can join the Household using their own Google Account.                                          |
| ACC-05 | P0       | All Household Members can view, add, edit, move, and delete Items.                                                |
| ACC-06 | P0       | The Household Owner can view and remove Household Members.                                                        |
| ACC-07 | P1       | A Member can leave a Household, provided ownership is transferred first when applicable.                          |
| ACC-08 | P0       | A Member can belong to multiple Households using the same Google Account.                                         |
| ACC-09 | P0       | After sign-in, a Member with multiple available Households must select a Household before its Inventory is shown. |
| ACC-10 | P0       | After sign-in, a Member with exactly one available Household is taken directly to that Household.                 |

### Items

| ID     | Priority | Requirement                                                                                                                                             |
| ------ | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ITM-01 | P0       | Creating a Household creates its root Item with the Household's name.                                                                                   |
| ITM-02 | P0       | A Member can create a named Item beneath any existing Item.                                                                                             |
| ITM-03 | P0       | Every Item except the Household root stores exactly one current Parent Item and no Parent Item history.                                                 |
| ITM-04 | P0       | Any Item can have zero or more child Items.                                                                                                             |
| ITM-05 | P0       | A member can view an Item's immediate children and full path from the Household root.                                                                   |
| ITM-06 | P0       | The Household root has no parent and cannot be moved or deleted using the Item delete action.                                                           |
| ITM-07 | P0       | A non-root Item has a required name and one optional photo, plus an optional description and optional Tags. Items do not have Categories or quantities. |
| ITM-08 | P0       | Selecting **Add item** opens the camera; after capture, the Member crops the photo before the Item details form is shown.                               |
| ITM-09 | P0       | A member can view and edit an Item's details.                                                                                                           |
| ITM-10 | P0       | A member can move an Item and all its descendants beneath a different parent Item.                                                                      |
| ITM-11 | P0       | The app prevents an Item from being moved beneath itself or any of its descendants.                                                                     |
| ITM-12 | P0       | Any Member can permanently delete an Item that has no Child Items.                                                                                      |
| ITM-13 | P0       | Any Member can permanently delete an Item and its entire subtree after an enhanced warning.                                                             |
| ITM-14 | P0       | Deleting an Item requires confirmation and removes the Item and any deleted descendants from the Inventory and search results.                          |
| ITM-15 | P1       | A member can duplicate an Item without duplicating its descendants.                                                                                     |
| ITM-16 | P1       | A member can reorder or favorite commonly used child Items.                                                                                             |

### Search and discovery

| ID     | Priority | Requirement                                                                                               |
| ------ | -------- | --------------------------------------------------------------------------------------------------------- |
| SRC-01 | P0       | A member can search Items by partial, case-insensitive name.                                              |
| SRC-02 | P0       | Search results show Item name, photo thumbnail when available, and the full path from the Household root. |
| SRC-04 | P1       | Search matches tags.                                                                                      |
| SRC-05 | P1       | The app shows recent searches and recently viewed items.                                                  |

### Sharing and synchronization

| ID     | Priority | Requirement                                                                                                      |
| ------ | -------- | ---------------------------------------------------------------------------------------------------------------- |
| SYN-01 | P0       | Changes made by a connected member become visible to other connected members without requiring a manual refresh. |
| SYN-02 | P0       | Previously loaded inventory remains readable when the device temporarily loses connectivity.                     |
| SYN-03 | P0       | The app clearly communicates whether a change is saved, still syncing, or failed.                                |
| SYN-04 | P0       | A failed change can be retried without the member re-entering its data.                                          |
| SYN-05 | P1       | Members can add and edit items while offline and have changes synchronized later.                                |

## Information model

### Household

The Household is the root Item of the shared tree. It has an Item identity and name, plus:

- Owner
- Members
- Created date

It has no parent and there is exactly one Household root in each inventory.

### Member

- Display name
- Google Account identity
- Memberships in one or more Households
- Household role: owner or member
- Membership status

### Item

- Name
- Parent Item, required except for the Household root
- No previous Parent Item or Parent Item history
- One optional photo, except for the Household root
- Optional description
- Optional tags
- Created and last-updated dates
- Created and last-updated member

Child Items are derived from their parent relationship. An Item can have any number of children.

The Item relationships form one tree:

- The Household is the only Item without a parent.
- Every other Item has exactly one parent.
- Every Item is reachable from the Household by following child relationships.
- An Item cannot be its own ancestor or descendant.
- Moving an Item changes its parent and preserves its entire subtree.

## UX requirements

- After sign-in, show a Household selector when the Member has access to multiple Households.
- Clearly show which Household is currently open and provide a way to switch to another available Household.
- The home screen prioritizes global search and the immediate children of the Household root.
- The primary **Add item** action is reachable from the home, search, and Item detail screens and opens the camera directly.
- After a photo is taken, present an accessible crop screen before showing the Item details form.
- User can opt out of taking the photo.
- The Item details form requires a name, allows an optional description and optional Tags, and identifies the selected Parent Item.
- An Item path uses breadcrumbs or another compact visual treatment that remains understandable when deeply nested.
- An Item detail screen clearly distinguishes the selected Item from its child Items.
- Empty states explain the next useful action, such as adding a first child Item.
- Destructive actions require confirmation and explain their effect on contained data. The enhanced warning for subtree deletion states that the selected Item and all its descendants will be permanently deleted.
- The app uses Google Material Design specifications and familiar Android navigation patterns and supports system back behavior.
- Forms preserve entered data after a validation error, temporary connection failure, or accidental backgrounding.

## Accessibility requirements

- Meet WCAG 2.2 Level AA where applicable to a native Android app.
- Support Android font scaling without hiding controls or truncating essential information.
- Provide meaningful labels for controls, item photos, status indicators, and navigation.
- Do not use color as the only way to convey state.
- Interactive targets are at least 48 × 48 dp.
- Core workflows are usable with TalkBack and switch access.

## Privacy and security requirements

- Household inventory is private to current household members.
- Authentication credentials and session tokens are stored using Android-recommended secure storage.
- Invitation links are time-limited and cannot be reused after acceptance or revocation.
- Removed members lose access to household data.
- The app requests camera permission only when needed for the user-initiated **Add item** flow.
- A user can request deletion of their account. Household ownership must be transferred or the household explicitly deleted first.
- The app does not need to collect product analytics or success metrics.

## Performance and reliability requirements

- On the target device and a typical connection, the home screen becomes usable within 2 seconds after launch when local data is available.
- Search results begin appearing within 500 ms for a household containing up to 10,000 Items.
- The app supports at least 12,000 Items and 20 members in one Household.
- Saved data is not silently lost after app termination, network interruption, or synchronization failure.
- User-visible failures explain what happened and provide a recovery action where possible.
- The v1 target environment is a Google Pixel 8 Pro running Android 17.

## Success metrics

No product success metrics need to be collected for v1.

## Release acceptance criteria

The first public release is ready when:

- Members can sign in with their Google Account through Firebase Authentication, and no other authentication method is offered.
- A Member can belong to multiple Households and must select one after sign-in when multiple Households are available.
- A new user can create a Household root and build a multi-level Item tree beneath it.
- Selecting **Add item** opens the camera, then presents photo cropping, followed by the details form for a required name, optional description, optional Tags, and Parent Item.
- The owner can invite another account, and both members see the same inventory.
- Either member can search for and move an Item beneath a different parent, with the entire subtree and changed paths appearing on the other device.
- Loss of connectivity does not prevent a member from viewing previously loaded inventory.
- Failed synchronization is visible and recoverable.
- A childless Item can be permanently deleted and no longer appears in the tree or search results.
- Any Member can permanently delete an Item with children and all its descendants after an enhanced warning.
- Automated tests cover permission boundaries, tree invariants, Item creation, subtree movement, childless and subtree deletion, searching, and synchronization failure recovery.
- Tests prove that the root cannot be moved or deleted through the Item flow and that an Item cannot become its own ancestor.
- TalkBack can complete Household setup, Item creation, tree browsing, search, and Item movement.
- No open release-blocking security, privacy, data-loss, accessibility, or crash defects remain.

## Risks and mitigations

| Risk                                             | Impact                                    | Mitigation                                                                                                                    |
| ------------------------------------------------ | ----------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| Initial data entry feels like too much work      | Families abandon setup                    | Make the camera-to-save flow fast, preserve the most recently selected Parent Item, and allow optional details to be skipped. |
| The Item tree becomes too complicated            | Members cannot predict where Items belong | Show full paths consistently and test tree-building with families who organize differently.                                   |
| Inventory becomes stale                          | Search results lose trust                 | Make moving and editing fast and make stale information easy for Members to correct.                                          |
| A Member deletes an Item or subtree accidentally | Inventory data is permanently lost        | Require confirmation for deletion and an enhanced warning that clearly identifies all descendants as permanently deleted.     |
| Simultaneous edits overwrite information         | Members lose changes                      | Define conflict behavior before implementation and make unresolved synchronization failures visible.                          |
| Sensitive household details are exposed          | Privacy and physical-security harm        | Keep Households private by default, avoid product analytics, secure invitations, and test authorization boundaries.           |

## Future opportunities

- QR labels that open a specific Item or subtree
- Barcode-assisted entry
- Receipts, warranty reminders, and purchase information
- Insurance export and valuation
- Borrowing and lending
- Low-stock reminders for consumables
- Bulk import and export
- Web and iOS clients
