# Household Inventory Android App

Status: Draft
Last updated: 2026-07-28

## Product summary

MyStuff is an Android app that gives a family one shared tree of the things in their home. The Household is the root Item, and every other Item appears beneath exactly one parent Item. Family members can add, browse, search, edit, and move Items within this tree.

## Problem

Families accumulate belongings across rooms, cupboards, drawers, boxes, sheds, and garages. The path to an infrequently used Item is often remembered by only one person—or forgotten entirely. This causes repeated searching, duplicate purchases, and frustration.

Existing approaches such as memory, notes, and spreadsheets are difficult to keep organized and inconvenient to update while moving around the house.

## Product vision

Any family member should be able to answer “Do we have this?” and “Where is it?” in a few seconds.

## Assumptions to validate

- The initial product supports one shared household per user.
- Every household member uses their own account and Android device.
- All members can view and maintain the inventory. Only the household owner manages membership and household settings.
- The Household itself is the root Item and has no parent.
- Every other Item has exactly one parent Item.
- Any Item can have zero or more child Items; there are no separate types for rooms, containers, or storage places.
- The Item tree can represent paths such as `Our Home → Garage → Metal cabinet → Drill`.
- Deleting an Item is permanent. An Item must have no children before it can be deleted.
- An item record may represent one object or a counted group of interchangeable objects, such as `AA batteries, quantity 12`.
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
- Establish a data model that can later support photos, receipts, warranties, and other item details.
- Learn whether families maintain the inventory after initial setup.

## Non-goals for v1

- Home insurance reports or certified valuations
- Purchase receipts, warranties, depreciation, or financial reporting
- Barcode or QR-code scanning
- Lending items to people outside the household
- Shopping lists or consumable stock alerts
- Smart-home or retailer integrations
- Web or iOS clients
- Multiple homes or multiple households per user
- Exact position tracking using Bluetooth, GPS, or other sensors

## Users

### Household owner

The person who initially sets up the Household tree, adds its first child Items, and invites family members. They value structure and want the inventory to stay useful over time.

### Household member

A family member who mainly searches for items but occasionally adds, edits, or moves them. They need the app to be understandable without learning the owner's filing system.

## Core user journeys

### Set up a household

1. A new user creates an account.
2. The user creates and names a household.
3. The app creates the Household as the root Item.
4. The user adds child Items such as Kitchen, Garage, and Shed beneath the Household.
5. The user optionally adds more child Items such as cupboards, shelves, boxes, tools, or belongings.
6. The user invites family members.

### Add an item

1. A member selects **Add item**.
2. They enter an item name.
3. They choose a parent Item.
4. They optionally add a photo, quantity, category, description, and tags.
5. They save the Item and see it beneath the selected parent Item.

Target: a basic item can be added in under 30 seconds.

### Find an item

1. A member opens the app and searches by item name, category, or tag.
2. Matching results show the Item's name, photo when available, quantity, and full Item path.
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

## Functional requirements

Priorities use:

- **P0** — required for the first usable release
- **P1** — important if time allows; may follow immediately after launch
- **P2** — explicitly deferred

### Account and household

| ID | Priority | Requirement |
| --- | --- | --- |
| ACC-01 | P0 | A person can create an account, sign in, sign out, and recover access to their account. |
| ACC-02 | P0 | A signed-in person can create a named Household root Item and becomes its owner. |
| ACC-03 | P0 | The household owner can invite a family member using a shareable invitation. |
| ACC-04 | P0 | An invited person can join the household using their own account. |
| ACC-05 | P0 | All household members can view, add, edit, move, and delete Items. |
| ACC-06 | P0 | The household owner can view and remove household members. |
| ACC-07 | P1 | A member can leave a household, provided ownership is transferred first when applicable. |

### Items

| ID | Priority | Requirement |
| --- | --- | --- |
| ITM-01 | P0 | Creating a Household creates its root Item with the Household's name. |
| ITM-02 | P0 | A member can create a named Item beneath any existing Item. |
| ITM-03 | P0 | Every Item except the Household root has exactly one parent Item. |
| ITM-04 | P0 | Any Item can have zero or more child Items. |
| ITM-05 | P0 | A member can view an Item's immediate children and full path from the Household root. |
| ITM-06 | P0 | The Household root has no parent and cannot be moved or deleted using the Item delete action. |
| ITM-07 | P0 | An Item can store an optional quantity, photo, description, category, and tags. |
| ITM-08 | P0 | Quantity is a positive whole number and defaults to 1. |
| ITM-09 | P0 | A member can view and edit an Item's details. |
| ITM-10 | P0 | A member can move an Item and all its descendants beneath a different parent Item. |
| ITM-11 | P0 | The app prevents an Item from being moved beneath itself or any of its descendants. |
| ITM-12 | P0 | A member can permanently delete an Item that has no child Items. |
| ITM-13 | P0 | An Item with children cannot be deleted until those children are moved or deleted. |
| ITM-14 | P0 | Deleting an Item requires confirmation and removes it from the tree and search results. |
| ITM-15 | P1 | A member can duplicate an Item without duplicating its descendants. |
| ITM-16 | P1 | A member can reorder or favorite commonly used child Items. |

### Search and discovery

| ID | Priority | Requirement |
| --- | --- | --- |
| SRC-01 | P0 | A member can search Items by partial, case-insensitive name. |
| SRC-02 | P0 | Search results show Item name, quantity, photo thumbnail when available, and the full path from the Household root. |
| SRC-03 | P0 | A member can limit search to an Item's subtree, including that Item and all its descendants. |
| SRC-04 | P1 | Search matches categories and tags. |
| SRC-05 | P1 | The app shows recent searches and recently viewed items. |
| SRC-06 | P1 | The app suggests likely matches for minor spelling mistakes. |

### Sharing and synchronization

| ID | Priority | Requirement |
| --- | --- | --- |
| SYN-01 | P0 | Changes made by a connected member become visible to other connected members without requiring a manual refresh. |
| SYN-02 | P0 | Previously loaded inventory remains readable when the device temporarily loses connectivity. |
| SYN-03 | P0 | The app clearly communicates whether a change is saved, still syncing, or failed. |
| SYN-04 | P0 | A failed change can be retried without the member re-entering its data. |
| SYN-05 | P1 | Members can add and edit items while offline and have changes synchronized later. |

## Information model

### Household

The Household is the root Item of the shared tree. It has an Item identity and name, plus:

- Owner
- Members
- Created date

It has no parent and there is exactly one Household root in each inventory.

### Member

- Display name
- Account identity
- Household role: owner or member
- Membership status

### Item

- Name
- Parent Item, required except for the Household root
- Quantity
- Optional photo
- Optional description
- Optional category
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

- The home screen prioritizes global search, recently viewed Items, and the immediate children of the Household root.
- The primary **Add item** action is reachable from the home, search, and Item detail screens.
- An Item path uses breadcrumbs or another compact visual treatment that remains understandable when deeply nested.
- An Item detail screen clearly distinguishes the selected Item from its child Items.
- Empty states explain the next useful action, such as adding a first child Item.
- Destructive actions require confirmation and explain their effect on contained data.
- The app uses familiar Android navigation patterns and supports system back behavior.
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
- Data is encrypted in transit and at rest.
- Authentication credentials and session tokens are stored using Android-recommended secure storage.
- Invitation links are time-limited and cannot be reused after acceptance or revocation.
- Removed members lose access to household data.
- The app requests only permissions necessary for a user-initiated feature, such as camera or photo access when adding a photo.
- A user can request deletion of their account. Household ownership must be transferred or the household explicitly deleted first.

## Performance and reliability requirements

- On a typical supported device and connection, the home screen becomes usable within 2 seconds after launch when local data is available.
- Search results begin appearing within 500 ms for a household containing up to 10,000 Items.
- The app supports at least 12,000 Items and 20 members in one Household.
- Saved data is not silently lost after app termination, network interruption, or synchronization failure.
- User-visible failures explain what happened and provide a recovery action where possible.
- The app supports the current Android version and the four preceding major versions at launch.

## Success metrics

Metrics must be collected with privacy-preserving product analytics and must not include Item names, photos, descriptions, tags, or Item paths.

### Activation

- Percentage of new Households that create at least three direct children of the root and ten Items in total within seven days
- Percentage of household owners who invite at least one member within seven days

### Engagement

- Weekly active households
- Percentage of active households with contributions from more than one member
- Median number of successful searches per active household
- Percentage of households that add or move an item after the first week

### Quality

- Crash-free user sessions
- Searches that return at least one result
- Synchronization failure rate
- Median time to add a basic item

Initial targets should be set after internal testing or a small beta establishes a baseline.

## Release acceptance criteria

The first public release is ready when:

- A new user can create a Household root and build a multi-level Item tree beneath it.
- The owner can invite another account, and both members see the same inventory.
- Either member can search for and move an Item beneath a different parent, with the entire subtree and changed paths appearing on the other device.
- Loss of connectivity does not prevent a member from viewing previously loaded inventory.
- Failed synchronization is visible and recoverable.
- A childless Item can be permanently deleted and no longer appears in the tree or search results.
- An Item with children cannot be deleted until those children are moved or deleted.
- Automated tests cover permission boundaries, tree invariants, Item creation, subtree movement, deletion, searching, and synchronization failure recovery.
- Tests prove that the root cannot be moved or deleted through the Item flow and that an Item cannot become its own ancestor.
- TalkBack can complete Household setup, Item creation, tree browsing, search, and Item movement.
- No open release-blocking security, privacy, data-loss, accessibility, or crash defects remain.

## Risks and mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Initial data entry feels like too much work | Families abandon setup | Make Item creation fast, preserve the most recently selected parent, and allow photos and optional details to be skipped. |
| The Item tree becomes too complicated | Members cannot predict where Items belong | Show full paths consistently and test tree-building with families who organize differently. |
| Inventory becomes stale | Search results lose trust | Make moving and editing fast; measure continued maintenance after setup. |
| A member deletes an Item accidentally | Inventory data is permanently lost | Allow deletion only for childless Items, require confirmation, and clearly state that deletion cannot be undone. |
| Simultaneous edits overwrite information | Members lose changes | Define conflict behavior before implementation and make unresolved synchronization failures visible. |
| Sensitive household details are exposed | Privacy and physical-security harm | Keep households private by default, minimize analytics, secure invitations, and test authorization boundaries. |
| Counted groups are mistaken for individually tracked objects | Quantity and tree placement become misleading | State that one Item record has one parent; split the record when its objects need different parents. |

## Open product questions

1. Should the first release require accounts and family sharing, or should it first prove the single-device inventory experience?
2. Should the Household owner be the only person allowed to change the Item tree, or should all members retain equal editing rights?
3. Should deletion remain limited to Items without children, or should a member be allowed to delete an entire subtree after an enhanced warning?
4. Is one photo per item sufficient for v1?
5. Should categories be a fixed list, household-defined, or omitted in favor of tags?
6. Does quantity need units such as pieces, boxes, or litres, or are whole-number counts sufficient?
7. Should the product remember an Item's parent history, or only its current parent?
8. What is the minimum Android version and intended device range?

## Future opportunities

- QR labels that open a specific Item or subtree
- Barcode-assisted entry
- Receipts, warranty reminders, and purchase information
- Insurance export and valuation
- Item parent history
- Borrowing and lending
- Low-stock reminders for consumables
- Bulk import and export
- Multiple homes or households
- Web and iOS clients
