# Household Inventory

This context describes a family's shared tree of household Items.

## Language

**Household**:
A private group of Members represented by the root Item of their shared Inventory. The Household is the only Item without a Parent Item.
_Avoid_: Account, family account, workspace

**Member**:
A person who has access to a Household through their own identity.
_Avoid_: User, collaborator

**Household Access**:
An ongoing authorization, managed by the Household Owner, for one Google email address to access a Household. It binds to that Google identity on first sign-in and lasts until the Household Owner removes it.
_Avoid_: Invitation, invite, access link

**Household Owner**:
The Member responsible for Household Access and ownership.
_Avoid_: Administrator, superuser

**Item**:
One named node in the Household tree. An Item may represent an area, container, belonging, or counted group; every Item except the Household has exactly one Parent Item and any Item may have Child Items.
_Avoid_: Stuff, asset, product, location

**Item Photo**:
An optional image Item Attachment designated to represent an Item other than the Household.
_Avoid_: Item image, full-size image

**Photo**:
The Member-facing name for any image Item Attachment, whether or not it is the Item Photo.
_Avoid_: Attachment, image attachment (in Member-facing UI)

**Item Attachment**:
One supporting file owned by an Item other than the Household, such as a photo, receipt, or instructions. An Item Attachment is not an Item and does not occupy a place in the Inventory tree.
_Avoid_: Child Item, document Item

**Description Generation**:
A Member-requested replacement for an Item's Description inferred by an LLM from the Item Photo while preserving facts supplied in the existing Description.
_Avoid_: AI description, description suggestion

**Parent Item**:
The single Item directly above another Item in the Household tree.
_Avoid_: Location, folder, container

**Child Item**:
An Item directly beneath a Parent Item in the Household tree.
_Avoid_: Content, nested item

**Item Path**:
The ordered Item names from the Household root to a specific Item, such as `Our Home → Garage → Cabinet → Drill`.
_Avoid_: Location path, file path, breadcrumb

**Inventory**:
The tree of Items rooted at a Household.
_Avoid_: Catalogue, database

**Search**:
A Member's attempt to find Items in their Household by words or meaning expressed in Item names, Tags, and Descriptions. Search may relate wording through synonyms, categories, and obvious purposes, but does not infer unstated properties or subjective judgments; Item Paths and Item Photos are not part of Search.
_Avoid_: Semantic search, vector search

**Move**:
A change to an Item's Parent Item that preserves the Item and all its descendants.
_Avoid_: Transfer, relocate

**Delete**:
Permanent removal of an Item from the Inventory.
_Avoid_: Trash
