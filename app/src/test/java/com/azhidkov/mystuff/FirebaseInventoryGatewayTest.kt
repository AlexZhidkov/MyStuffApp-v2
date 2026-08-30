package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FirebaseInventoryGatewayTest {
    @Test
    fun `moving an Item updates only its Parent Item and attribution`() {
        val documents = FakeInventoryDocumentStore()
        val gateway = FirebaseInventoryGateway(
            documents,
            FakeInventoryPhotoStore(),
            FakeItemPhotoAttachmentGateway(),
        )
        val item = inventoryItem("item-1", "Cabinet", "garage")
        var result: Result<Item>? = null

        gateway.moveItem(
            householdId = "household-1",
            item = item,
            newParentItemId = "shed",
            updater = inventoryIdentity(),
        ) { result = it }

        assertEquals("shed", result?.getOrThrow()?.parentItemId)
        assertEquals(
            mapOf(
                "parentItemId" to "shed",
                "updatedAt" to documents.serverTimestamp,
                "updatedById" to "member-1",
                "updatedByDisplayName" to "Alex",
            ),
            documents.updatedData,
        )
        assertEquals(item.copy(parentItemId = "shed"), result?.getOrThrow())
    }

    @Test
    fun `moving the root Item is rejected before persistence`() {
        val documents = FakeInventoryDocumentStore()
        val gateway = FirebaseInventoryGateway(
            documents,
            FakeInventoryPhotoStore(),
            FakeItemPhotoAttachmentGateway(),
        )
        var result: Result<Item>? = null

        gateway.moveItem(
            householdId = "household-1",
            item = inventoryItem("household-1", "Our Home", null),
            newParentItemId = "shed",
            updater = inventoryIdentity(),
        ) { result = it }

        assertTrue(result?.isFailure == true)
        assertNull(documents.updatedData)
    }

    @Test
    fun `creating an Item with a photo creates and projects one Item Attachment`() {
        val documents = FakeInventoryDocumentStore()
        val photos = FakeInventoryPhotoStore()
        val attachments = FakeItemPhotoAttachmentGateway()
        val gateway = FirebaseInventoryGateway(documents, photos, attachments)
        var result: Result<Item>? = null

        gateway.createItem(
            householdId = "household-1",
            parentItemId = "garage",
            creator = inventoryIdentity(),
            details = inventoryDetails(),
            photos = listOf(ItemPhoto("content://full.webp", "content://thumb.webp")),
        ) { result = it }

        val item = result?.getOrThrow()
        assertEquals("attachment-1", item?.photoAttachmentId)
        assertEquals(
            "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp",
            item?.photoUrl,
        )
        assertEquals(
            "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1-thumb.webp",
            item?.photoThumbnailUrl,
        )
        assertEquals("attachment-1", attachments.createdAttachment?.id)
        assertEquals(item?.photoUrl, attachments.createdDisplayUrl)
        assertEquals(item?.photoAttachmentId, documents.updatedData?.get("photoAttachmentId"))
    }

    @Test
    fun `creating an Item with multiple photos creates ordered attachments and uploads displays`() {
        val documents = FakeInventoryDocumentStore()
        val photos = FakeInventoryPhotoStore()
        val attachments = FakeItemPhotoAttachmentGateway()
        val gateway = FirebaseInventoryGateway(documents, photos, attachments)
        var result: Result<Item>? = null

        gateway.createItem(
            householdId = "household-1",
            parentItemId = "garage",
            creator = inventoryIdentity(),
            details = inventoryDetails(),
            photos = listOf(
                ItemPhoto("content://first.webp", "content://first-thumb.webp"),
                ItemPhoto("content://second.webp", "content://second-thumb.webp"),
                ItemPhoto("content://third.webp", "content://third-thumb.webp"),
            ),
        ) { result = it }

        val item = result?.getOrThrow()
        assertEquals(
            listOf("attachment-1", "attachment-2", "attachment-3"),
            attachments.createdIds,
        )
        assertEquals("attachment-1", item?.photoAttachmentId)
        assertEquals(item?.photoUrl, documents.updatedData?.get("photoUrl"))
        assertEquals(
            listOf(
                QueuedPhotoUpload(
                    ItemPhotoVariant.Full,
                    "content://first.webp",
                    "households/household-1/items/item-1/attachments/attachment-1.webp",
                ),
                QueuedPhotoUpload(
                    ItemPhotoVariant.Thumbnail,
                    "content://first-thumb.webp",
                    "households/household-1/items/item-1/attachments/attachment-1-thumb.webp",
                ),
                QueuedPhotoUpload(
                    ItemPhotoVariant.Full,
                    "content://second.webp",
                    "households/household-1/items/item-1/attachments/attachment-2.webp",
                ),
                QueuedPhotoUpload(
                    ItemPhotoVariant.Full,
                    "content://third.webp",
                    "households/household-1/items/item-1/attachments/attachment-3.webp",
                ),
            ),
            photos.uploads,
        )
    }

    @Test
    fun `replacing an attachment-backed Item Photo creates a new attachment and removes the old logical attachment`() {
        val documents = FakeInventoryDocumentStore()
        val photos = FakeInventoryPhotoStore()
        val attachments = FakeItemPhotoAttachmentGateway()
        val gateway = FirebaseInventoryGateway(documents, photos, attachments)
        var result: Result<Item>? = null

        gateway.updateItemWithAttachments(
            householdId = "household-1",
            item = inventoryItem(
                "item-1",
                "Drill",
                "garage",
                photoAttachmentId = "old-attachment",
                photoUrl = "gs://mystuff/old.webp",
                photoThumbnailUrl = "gs://mystuff/old-thumb.webp",
            ),
            updater = inventoryIdentity(),
            details = inventoryDetails(),
            additionalPhotos = listOf(ItemPhoto("content://new.webp")),
            existingAttachments = listOf(
                attachment("old-attachment", "gs://mystuff/old.webp", creationOrder = 0),
            ),
            attachmentToDelete = attachment("old-attachment", "gs://mystuff/old.webp"),
        ) { result = it }

        assertEquals("attachment-1", result?.getOrThrow()?.photoAttachmentId)
        assertEquals("attachment-1", attachments.createdAttachment?.id)
        assertEquals(listOf("old-attachment"), attachments.deletedAttachmentIds)
        assertEquals("attachment-1", documents.updatedData?.get("photoAttachmentId"))
    }

    @Test
    fun `adding attachments while editing preserves the current Item Photo and uploads displays`() {
        val documents = FakeInventoryDocumentStore()
        val photos = FakeInventoryPhotoStore()
        val attachments = FakeItemPhotoAttachmentGateway()
        val gateway = FirebaseInventoryGateway(documents, photos, attachments)
        val existing = inventoryItem(
            "item-1",
            "Drill",
            "garage",
            photoAttachmentId = "old-attachment",
            photoUrl = "gs://mystuff/old.webp",
            photoThumbnailUrl = "gs://mystuff/old-thumb.webp",
        )
        var result: Result<Item>? = null

        gateway.updateItemWithAttachments(
            householdId = "household-1",
            item = existing,
            updater = inventoryIdentity(),
            details = inventoryDetails(),
            additionalPhotos = listOf(
                ItemPhoto("content://receipt.webp", "content://receipt-thumb.webp"),
                ItemPhoto("content://manual.webp", "content://manual-thumb.webp"),
            ),
            existingAttachments = listOf(
                ItemAttachment(
                    id = "old-attachment",
                    itemId = "item-1",
                    createdAt = Instant.EPOCH,
                    contentType = OPTIMIZED_ATTACHMENT_IMAGE_CONTENT_TYPE,
                    displayUrl = "gs://mystuff/old.webp",
                    creationOrder = 0,
                ),
            ),
        ) { result = it }

        assertEquals(existing.photoAttachmentId, result?.getOrThrow()?.photoAttachmentId)
        assertEquals(listOf("attachment-1", "attachment-2"), attachments.createdIds)
        assertEquals(listOf(1L, 2L), attachments.createdOrders)
        assertTrue(attachments.deletedAttachmentIds.isEmpty())
        assertEquals(
            listOf(
                QueuedPhotoUpload(
                    ItemPhotoVariant.Full,
                    "content://receipt.webp",
                    "households/household-1/items/item-1/attachments/attachment-1.webp",
                ),
                QueuedPhotoUpload(
                    ItemPhotoVariant.Full,
                    "content://manual.webp",
                    "households/household-1/items/item-1/attachments/attachment-2.webp",
                ),
            ),
            photos.uploads,
        )
    }

    @Test
    fun `adding an attachment to an Item without a photo projects the first attachment`() {
        val documents = FakeInventoryDocumentStore()
        val photos = FakeInventoryPhotoStore()
        val attachments = FakeItemPhotoAttachmentGateway()
        val gateway = FirebaseInventoryGateway(documents, photos, attachments)
        var result: Result<Item>? = null

        gateway.updateItemWithAttachments(
            householdId = "household-1",
            item = inventoryItem("item-1", "Drill", "garage"),
            updater = inventoryIdentity(),
            details = inventoryDetails(),
            additionalPhotos = listOf(ItemPhoto("content://receipt.webp")),
            existingAttachments = emptyList(),
        ) { result = it }

        assertEquals("attachment-1", result?.getOrThrow()?.photoAttachmentId)
        assertEquals(
            "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp",
            result?.getOrThrow()?.photoUrl,
        )
        assertEquals("attachment-1", documents.updatedData?.get("photoAttachmentId"))
        assertEquals(2, photos.uploads.size)
    }

    @Test
    fun `removing an attachment-backed Item Photo deletes its attachment and clears the projection`() {
        val documents = FakeInventoryDocumentStore()
        val photos = FakeInventoryPhotoStore()
        val attachments = FakeItemPhotoAttachmentGateway()
        val gateway = FirebaseInventoryGateway(documents, photos, attachments)
        var result: Result<Item>? = null

        gateway.updateItemWithAttachments(
            householdId = "household-1",
            item = inventoryItem(
                "item-1",
                "Drill",
                "garage",
                photoAttachmentId = "attachment-1",
                photoUrl = "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp",
                photoThumbnailUrl = "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1-thumb.webp",
            ),
            updater = inventoryIdentity(),
            details = inventoryDetails(),
            additionalPhotos = emptyList(),
            existingAttachments = listOf(
                attachment(
                    "attachment-1",
                    "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp",
                    creationOrder = 0,
                ),
            ),
            attachmentToDelete = attachment(
                "attachment-1",
                "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp",
            ),
        ) { result = it }

        assertNull(result?.getOrThrow()?.photoAttachmentId)
        assertNull(result?.getOrThrow()?.photoUrl)
        assertEquals(listOf("attachment-1"), attachments.deletedAttachmentIds)
        assertNull(documents.updatedData?.get("photoAttachmentId"))
    }

    @Test
    fun `creating an Item finishes before its two photo variants upload`() {
        val documents = FakeInventoryDocumentStore()
        var result: Result<Item>? = null
        val photos = FakeInventoryPhotoStore()
        val gateway = FirebaseInventoryGateway(documents, photos, FakeItemPhotoAttachmentGateway())

        gateway.createItem(
            householdId = "household-1",
            parentItemId = "garage",
            creator = inventoryIdentity(),
            details = ItemDetails(
                name = "Drill",
                description = "18V cordless",
                tags = listOf("Power Tools"),
            ),
            photos = listOf(ItemPhoto(
                uri = "content://mystuff/cropped.webp",
                thumbnailUri = "content://mystuff/cropped-thumb.webp",
            )),
        ) { result = it }

        val created = result?.getOrThrow()
        assertEquals(
            "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp",
            created?.photoUrl,
        )
        assertEquals(
            "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1-thumb.webp",
            created?.photoThumbnailUrl,
        )
        assertEquals(created?.photoUrl, documents.updatedData?.get("photoUrl"))
        assertEquals(
            created?.photoThumbnailUrl,
            documents.updatedData?.get("photoThumbnailUrl"),
        )
        assertEquals(
            listOf(
                QueuedPhotoUpload(
                    ItemPhotoVariant.Full,
                    "content://mystuff/cropped.webp",
                    "households/household-1/items/item-1/attachments/attachment-1.webp",
                ),
                QueuedPhotoUpload(
                    ItemPhotoVariant.Thumbnail,
                    "content://mystuff/cropped-thumb.webp",
                    "households/household-1/items/item-1/attachments/attachment-1-thumb.webp",
                ),
            ),
            photos.uploads,
        )
    }

    @Test
    fun `background scheduling failure does not block completed Item creation`() {
        val photos = FakeInventoryPhotoStore(enqueueFailure = IllegalStateException("scheduler"))
        val gateway = FirebaseInventoryGateway(
            FakeInventoryDocumentStore(),
            photos,
            FakeItemPhotoAttachmentGateway(),
        )
        var result: Result<Item>? = null

        gateway.createItem(
            householdId = "household-1",
            parentItemId = "garage",
            creator = inventoryIdentity(),
            details = ItemDetails(
                name = "Drill",
                description = "18V cordless",
                tags = listOf("Power Tools"),
            ),
            photos = listOf(ItemPhoto("content://full.webp", "content://thumb.webp")),
        ) { result = it }

        assertEquals("item-1", result?.getOrThrow()?.id)
        assertEquals(
            "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp",
            result?.getOrThrow()?.photoUrl,
        )
    }

    @Test
    fun `observed Item documents become one connected Inventory`() {
        val household = inventoryHousehold()
        val store = FakeInventoryDocumentStore(
            documents = listOf(
                itemDocument("household-1", "Our Home", null),
                itemDocument("garage", "Garage", "household-1"),
                itemDocument("cabinet", "Cabinet", "garage"),
            ),
        )
        val gateway = FirebaseInventoryGateway(
            store,
            FakeInventoryPhotoStore(),
            FakeItemPhotoAttachmentGateway(),
        )
        var result: Result<Inventory>? = null

        gateway.observe(household) { result = it }

        assertEquals(
            listOf("Our Home", "Garage", "Cabinet"),
            result?.getOrThrow()?.pathTo("cabinet")?.map(Item::name),
        )
    }

    @Test
    fun `observed legacy photo locations load without migration`() {
        val legacyPhoto = "gs://mystuff/households/household-1/items/item-1.webp"
        val legacyThumbnail = "gs://mystuff/households/household-1/items/item-1-thumb.webp"
        val household = inventoryHousehold()
        val store = FakeInventoryDocumentStore(
            documents = listOf(
                itemDocument("household-1", "Our Home", null),
                itemDocument(
                    id = "item-1",
                    name = "Drill",
                    parentItemId = "household-1",
                    photoUrl = legacyPhoto,
                    photoThumbnailUrl = legacyThumbnail,
                ),
            ),
        )
        val gateway = FirebaseInventoryGateway(
            store,
            FakeInventoryPhotoStore(),
            FakeItemPhotoAttachmentGateway(),
        )
        var result: Result<Inventory>? = null

        gateway.observe(household) { result = it }

        val observed = result?.getOrThrow()?.item("item-1")
        assertEquals(legacyPhoto, observed?.photoUrl)
        assertEquals(legacyThumbnail, observed?.photoThumbnailUrl)
    }

    @Test
    fun `observed Item documents retain optional web URLs`() {
        val household = inventoryHousehold()
        val store = FakeInventoryDocumentStore(
            documents = listOf(
                itemDocument("household-1", "Our Home", null),
                itemDocument(
                    id = "item-1",
                    name = "Drill",
                    parentItemId = "household-1",
                    webUrl = "https://example.com/drill",
                ),
            ),
        )
        val gateway = FirebaseInventoryGateway(
            store,
            FakeInventoryPhotoStore(),
            FakeItemPhotoAttachmentGateway(),
        )
        var result: Result<Inventory>? = null

        gateway.observe(household) { result = it }

        assertEquals(
            "https://example.com/drill",
            result?.getOrThrow()?.item("item-1")?.webUrl,
        )
        assertNull(result?.getOrThrow()?.item("household-1")?.webUrl)
    }

    @Test
    fun `creating an Item writes its generated identity current Parent Item and attribution`() {
        val timestamp = Any()
        val store = FakeInventoryDocumentStore(serverTimestamp = timestamp)
        val gateway = FirebaseInventoryGateway(
            store,
            FakeInventoryPhotoStore(),
            FakeItemPhotoAttachmentGateway(),
        )
        var result: Result<Item>? = null

        gateway.createItem(
            householdId = "household-1",
            parentItemId = "garage",
            creator = inventoryIdentity(),
            details = ItemDetails(
                name = "Drill",
                description = "18V cordless",
                tags = listOf("Power Tools"),
                webUrl = "https://example.com/drill",
            ),
            photos = emptyList(),
        ) { result = it }

        assertEquals("item-1", store.createdItemId)
        assertEquals(
            mapOf(
                "householdId" to "household-1",
                "name" to "Drill",
                "parentItemId" to "garage",
                "photoAttachmentId" to null,
                "photoUrl" to null,
                "photoThumbnailUrl" to null,
                "description" to "18V cordless",
                "tags" to listOf("Power Tools"),
                "webUrl" to "https://example.com/drill",
                "createdAt" to timestamp,
                "updatedAt" to timestamp,
                "createdById" to "member-1",
                "createdByDisplayName" to "Alex",
                "updatedById" to "member-1",
                "updatedByDisplayName" to "Alex",
            ),
            store.createdData,
        )
        assertEquals(
            inventoryItem(
                "item-1",
                "Drill",
                "garage",
                description = "18V cordless",
                tags = listOf("Power Tools"),
                webUrl = "https://example.com/drill",
            ),
            result?.getOrThrow(),
        )
    }

    @Test
    fun `updating an Item writes editable details and last-updating Member attribution`() {
        val timestamp = Any()
        val store = FakeInventoryDocumentStore(serverTimestamp = timestamp)
        val gateway = FirebaseInventoryGateway(
            store,
            FakeInventoryPhotoStore(),
            FakeItemPhotoAttachmentGateway(),
        )
        var result: Result<Item>? = null

        gateway.updateItemWithAttachments(
            householdId = "household-1",
            item = inventoryItem("item-1", "Drill", "garage"),
            updater = inventoryIdentity().copy(id = "member-2", displayName = "Sam"),
            details = ItemDetails(
                name = "Hammer Drill",
                description = "18V cordless",
                tags = listOf("Power Tools"),
                webUrl = "https://example.com/hammer-drill",
            ),
            additionalPhotos = emptyList(),
            existingAttachments = emptyList(),
        ) { result = it }

        assertEquals(
            mapOf(
                "name" to "Hammer Drill",
                "photoAttachmentId" to null,
                "photoUrl" to null,
                "photoThumbnailUrl" to null,
                "description" to "18V cordless",
                "tags" to listOf("Power Tools"),
                "webUrl" to "https://example.com/hammer-drill",
                "updatedAt" to timestamp,
                "updatedById" to "member-2",
                "updatedByDisplayName" to "Sam",
            ),
            store.updatedData,
        )
        assertEquals("Hammer Drill", result?.getOrThrow()?.name)
        assertEquals(listOf("Power Tools"), result?.getOrThrow()?.tags)
        assertEquals("https://example.com/hammer-drill", result?.getOrThrow()?.webUrl)
    }

    @Test
    fun `persistence rejects Tags duplicated after Unicode normalization`() {
        val store = FakeInventoryDocumentStore()
        val gateway = FirebaseInventoryGateway(
            store,
            FakeInventoryPhotoStore(),
            FakeItemPhotoAttachmentGateway(),
        )
        var result: Result<Item>? = null

        gateway.createItem(
            householdId = "household-1",
            parentItemId = "garage",
            creator = inventoryIdentity(),
            details = ItemDetails(
                name = "Drill",
                description = null,
                tags = listOf("Power Tools", "powér tools"),
            ),
            photos = emptyList(),
        ) { result = it }

        assertTrue(result?.isFailure == true)
        assertNull(store.createdData)
    }

    @Test
    fun `replacing an Item photo publishes a new revision without deleting the old revision`() {
        val store = FakeInventoryDocumentStore()
        val photos = FakeInventoryPhotoStore()
        val gateway = FirebaseInventoryGateway(
            store,
            photos,
            FakeItemPhotoAttachmentGateway(),
        )
        var result: Result<Item>? = null

        gateway.updateItemWithAttachments(
            householdId = "household-1",
            item = inventoryItem(
                "item-1",
                "Drill",
                "garage",
                photoUrl = "gs://old/full.webp",
                photoThumbnailUrl = "gs://old/thumb.webp",
            ),
            updater = inventoryIdentity(),
            details = inventoryDetails(),
            additionalPhotos = listOf(ItemPhoto("content://new.webp", "content://new-thumb.webp")),
            existingAttachments = listOf(
                attachment("old", "gs://old/full.webp", creationOrder = 0),
            ),
            attachmentToDelete = attachment("old", "gs://old/full.webp"),
        ) { result = it }

        assertEquals(
            "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp",
            result?.getOrThrow()?.photoUrl,
        )
        assertEquals(
            result?.getOrThrow()?.photoUrl,
            store.updatedData?.get("photoUrl"),
        )
        assertEquals(
            result?.getOrThrow()?.photoThumbnailUrl,
            store.updatedData?.get("photoThumbnailUrl"),
        )
        assertEquals(2, photos.uploads.size)
        assertEquals("content://new.webp", photos.uploads.first().sourceUri)
        assertTrue(photos.deletedLocations.isEmpty())
    }

    @Test
    fun `removing an Item photo clears its locations and deletes both stored variants`() {
        val store = FakeInventoryDocumentStore()
        val photos = FakeInventoryPhotoStore()
        val gateway = FirebaseInventoryGateway(
            store,
            photos,
            FakeItemPhotoAttachmentGateway(),
        )
        var result: Result<Item>? = null

        gateway.deleteItemAttachment(
            householdId = "household-1",
            item = inventoryItem(
                "item-1",
                "Drill",
                "garage",
                photoAttachmentId = "legacy",
                photoUrl = "gs://old/full.webp",
                photoThumbnailUrl = "gs://old/thumb.webp",
            ),
            updater = inventoryIdentity(),
            attachment = attachment("legacy", "gs://old/full.webp"),
            remainingAttachments = emptyList(),
        ) { result = it }

        assertNull(result?.getOrThrow()?.photoUrl)
        assertNull(result?.getOrThrow()?.photoThumbnailUrl)
        assertEquals(
            listOf("gs://old/full.webp", "gs://old/thumb.webp"),
            photos.deletedLocations,
        )
    }

    @Test
    fun `designating an attachment updates the projection generates its thumbnail and removes only the old thumbnail`() {
        val store = FakeInventoryDocumentStore()
        val photos = FakeInventoryPhotoStore()
        val attachments = FakeItemPhotoAttachmentGateway()
        val gateway = FirebaseInventoryGateway(store, photos, attachments)
        val item = inventoryItem(
            id = "item-1",
            name = "Drill",
            parentItemId = "garage",
            photoAttachmentId = "old",
            photoUrl = "gs://mystuff/old.webp",
            photoThumbnailUrl = "gs://mystuff/old-thumb.webp",
        )
        val replacement = attachment("replacement", "gs://mystuff/replacement.webp")

        gateway.designateItemPhoto(
            householdId = "household-1",
            item = item,
            updater = inventoryIdentity(),
            attachment = replacement,
        ) { it.getOrThrow() }

        assertEquals("replacement", store.updatedData?.get("photoAttachmentId"))
        assertEquals(replacement.displayUrl, store.updatedData?.get("photoUrl"))
        assertEquals(
            listOf("gs://mystuff/old-thumb.webp"),
            photos.deletedLocations,
        )
        assertEquals(
            listOf(replacement.displayUrl),
            photos.generatedThumbnailSources,
        )
    }

    @Test
    fun `deleting the designated attachment promotes the oldest remaining attachment and removes its files`() {
        val store = FakeInventoryDocumentStore()
        val photos = FakeInventoryPhotoStore()
        val attachments = FakeItemPhotoAttachmentGateway()
        val gateway = FirebaseInventoryGateway(store, photos, attachments)
        val item = inventoryItem(
            id = "item-1",
            name = "Drill",
            parentItemId = "garage",
            photoAttachmentId = "photo",
            photoUrl = "gs://mystuff/photo.webp",
            photoThumbnailUrl = "gs://mystuff/photo-thumb.webp",
        )
        val deleted = attachment("photo", item.photoUrl!!, creationOrder = 0)
        val remaining = attachment("receipt", "gs://mystuff/receipt.webp", creationOrder = 1)

        gateway.deleteItemAttachment(
            householdId = "household-1",
            item = item,
            updater = inventoryIdentity(),
            attachment = deleted,
            remainingAttachments = listOf(remaining),
        ) { it.getOrThrow() }

        assertEquals("receipt", store.updatedData?.get("photoAttachmentId"))
        assertEquals(remaining.displayUrl, store.updatedData?.get("photoUrl"))
        assertEquals(listOf("photo"), attachments.deletedAttachmentIds)
        assertEquals(
            listOf("gs://mystuff/photo.webp", "gs://mystuff/photo-thumb.webp"),
            photos.deletedLocations,
        )
        assertEquals(listOf(remaining.displayUrl), photos.generatedThumbnailSources)
    }

    private fun attachment(
        id: String,
        displayUrl: String,
        creationOrder: Long = 0,
    ) = ItemAttachment(
        id = id,
        itemId = "item-1",
        createdAt = Instant.ofEpochSecond(creationOrder),
        contentType = OPTIMIZED_ATTACHMENT_IMAGE_CONTENT_TYPE,
        displayUrl = displayUrl,
        creationOrder = creationOrder,
    )
}

private fun inventoryDetails() = ItemDetails(
    name = "Drill",
    description = null,
    tags = emptyList(),
)

private data class QueuedPhotoUpload(
    val variant: ItemPhotoVariant,
    val sourceUri: String,
    val storagePath: String,
)

private class FakeInventoryPhotoStore(
    private val enqueueFailure: Throwable? = null,
) : InventoryPhotoStore {
    val uploads = mutableListOf<QueuedPhotoUpload>()
    val deletedLocations = mutableListOf<String>()
    val generatedThumbnailSources = mutableListOf<String>()

    override fun newAttachmentRevision(
        householdId: String,
        itemId: String,
        attachmentId: String,
    ) = ItemPhotoRevision(
        locations = ItemPhotoLocations(
            full = "gs://mystuff/households/$householdId/items/$itemId/attachments/$attachmentId.webp",
            thumbnail = "gs://mystuff/households/$householdId/items/$itemId/attachments/$attachmentId-thumb.webp",
        ),
        fullStoragePath = "households/$householdId/items/$itemId/attachments/$attachmentId.webp",
        thumbnailStoragePath = "households/$householdId/items/$itemId/attachments/$attachmentId-thumb.webp",
    )

    override fun uploadAttachmentInBackground(
        revision: ItemPhotoRevision,
        photo: ItemPhoto,
        failure: AttachmentUploadFailure?,
    ) {
        enqueueFailure?.let { throw it }
        uploads += QueuedPhotoUpload(
            ItemPhotoVariant.Full,
            photo.uri,
            revision.fullStoragePath,
        )
        uploads += QueuedPhotoUpload(
            ItemPhotoVariant.Thumbnail,
            photo.thumbnailUri,
            revision.thumbnailStoragePath,
        )
    }

    override fun uploadDisplayInBackground(
        revision: ItemPhotoRevision,
        photo: ItemPhoto,
    ) {
        enqueueFailure?.let { throw it }
        uploads += QueuedPhotoUpload(
            ItemPhotoVariant.Full,
            photo.uri,
            revision.fullStoragePath,
        )
    }

    override fun uploadThumbnailInBackground(
        revision: ItemPhotoRevision,
        photo: ItemPhoto,
    ) {
        enqueueFailure?.let { throw it }
        uploads += QueuedPhotoUpload(
            ItemPhotoVariant.Thumbnail,
            photo.thumbnailUri,
            revision.thumbnailStoragePath,
        )
    }

    override fun generateAttachmentThumbnailInBackground(
        revision: ItemPhotoRevision,
        sourceLocation: String,
    ) {
        generatedThumbnailSources += sourceLocation
    }

    override fun deleteInBackground(locations: StoredItemPhotoLocations) {
        deletedLocations += locations.presentLocations()
    }
}

private class FakeInventoryDocumentStore(
    private val documents: List<InventoryItemDocument> = emptyList(),
    override val serverTimestamp: Any = Any(),
) : InventoryDocumentStore {
    var createdItemId: String? = null
        private set
    var createdData: Map<String, Any?>? = null
        private set
    var updatedData: Map<String, Any?>? = null
        private set

    override fun observeItems(
        householdId: String,
        onResult: (Result<List<InventoryItemDocument>>) -> Unit,
    ): InventorySubscription {
        onResult(Result.success(documents))
        return InventorySubscription {}
    }

    override fun newItemId(householdId: String): String = "item-1"

    override fun createItem(
        householdId: String,
        itemId: String,
        data: Map<String, Any?>,
        onResult: (Result<Unit>) -> Unit,
    ) {
        createdItemId = itemId
        createdData = data
        onResult(Result.success(Unit))
    }

    override fun updateItem(
        householdId: String,
        itemId: String,
        data: Map<String, Any?>,
        onResult: (Result<Unit>) -> Unit,
    ) {
        updatedData = data
        onResult(Result.success(Unit))
    }
}

private fun itemDocument(
    id: String,
    name: String,
    parentItemId: String?,
    photoUrl: String? = null,
    photoThumbnailUrl: String? = null,
    webUrl: String? = null,
) = InventoryItemDocument(
    id = id,
    data = buildMap {
        put("householdId", "household-1")
        put("name", name)
        put("parentItemId", parentItemId)
        put("photoUrl", photoUrl)
        put("photoThumbnailUrl", photoThumbnailUrl)
        put("description", null)
        put("tags", emptyList<String>())
        webUrl?.let { put("webUrl", it) }
    },
)

private fun inventoryHousehold() = Household(
    id = "household-1",
    ownerMemberId = "member-1",
    rootItem = inventoryItem("household-1", "Our Home", null),
)

private fun inventoryIdentity() = AuthenticatedIdentity(
    id = "member-1",
    displayName = "Alex",
    email = "alex@example.com",
)

private fun inventoryItem(
    id: String,
    name: String,
    parentItemId: String?,
    photoUrl: String? = null,
    photoThumbnailUrl: String? = null,
    photoAttachmentId: String? = null,
    description: String? = null,
    tags: List<String> = emptyList(),
    webUrl: String? = null,
) = Item(
    id = id,
    name = name,
    parentItemId = parentItemId,
    photoUrl = photoUrl,
    photoThumbnailUrl = photoThumbnailUrl,
    description = description,
    tags = tags,
    webUrl = webUrl,
    photoAttachmentId = photoAttachmentId,
)

private class FakeItemPhotoAttachmentGateway : ItemAttachmentGateway {
    var createdAttachment: ItemAttachment? = null
        private set
    var createdDisplayUrl: String? = null
        private set
    val createdIds = mutableListOf<String>()
    val createdOrders = mutableListOf<Long?>()
    val deletedAttachmentIds = mutableListOf<String>()
    private var nextId = 1

    override fun observe(
        household: Household,
        item: Item,
        onResult: (Result<List<ItemAttachment>>) -> Unit,
    ): InventorySubscription = InventorySubscription {}

    override fun newAttachmentId(householdId: String, itemId: String): String =
        "attachment-${nextId++}"

    override fun create(
        household: Household,
        item: Item,
        attachmentId: String,
        contentType: String,
        displayUrl: String,
        onResult: (Result<ItemAttachment>) -> Unit,
    ) {
        createdOrders += null
        createdDisplayUrl = displayUrl
        createdIds += attachmentId
        createdAttachment = ItemAttachment(
            id = attachmentId,
            itemId = item.id,
            createdAt = Instant.EPOCH,
            contentType = contentType,
            displayUrl = displayUrl,
        )
        onResult(Result.success(createdAttachment!!))
    }

    override fun createInOrder(
        household: Household,
        item: Item,
        attachmentId: String,
        creationOrder: Long,
        contentType: String,
        displayUrl: String,
        onResult: (Result<ItemAttachment>) -> Unit,
    ) {
        createdOrders += creationOrder
        createdDisplayUrl = displayUrl
        createdIds += attachmentId
        createdAttachment = ItemAttachment(
            id = attachmentId,
            itemId = item.id,
            createdAt = Instant.EPOCH,
            contentType = contentType,
            displayUrl = displayUrl,
            creationOrder = creationOrder,
        )
        onResult(Result.success(createdAttachment!!))
    }

    override fun delete(
        household: Household,
        item: Item,
        attachment: ItemAttachment,
        onResult: (Result<Unit>) -> Unit,
    ) {
        deletedAttachmentIds += attachment.id
        onResult(Result.success(Unit))
    }
}
