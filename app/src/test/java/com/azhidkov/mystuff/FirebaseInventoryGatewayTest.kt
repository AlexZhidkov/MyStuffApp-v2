package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FirebaseInventoryGatewayTest {
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
            photo = ItemPhoto("content://full.webp", "content://thumb.webp"),
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
    fun `replacing an attachment-backed Item Photo creates a new attachment and removes the old logical attachment`() {
        val documents = FakeInventoryDocumentStore()
        val photos = FakeInventoryPhotoStore()
        val attachments = FakeItemPhotoAttachmentGateway()
        val gateway = FirebaseInventoryGateway(documents, photos, attachments)
        var result: Result<Item>? = null

        gateway.updateItem(
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
            photoUpdate = ItemPhotoUpdate.Replaced(ItemPhoto("content://new.webp")),
        ) { result = it }

        assertEquals("attachment-1", result?.getOrThrow()?.photoAttachmentId)
        assertEquals("attachment-1", attachments.createdAttachment?.id)
        assertEquals(listOf("old-attachment"), attachments.deletedAttachmentIds)
        assertEquals("attachment-1", documents.updatedData?.get("photoAttachmentId"))
    }

    @Test
    fun `removing an attachment-backed Item Photo deletes its attachment and clears the projection`() {
        val documents = FakeInventoryDocumentStore()
        val photos = FakeInventoryPhotoStore()
        val attachments = FakeItemPhotoAttachmentGateway()
        val gateway = FirebaseInventoryGateway(documents, photos, attachments)
        var result: Result<Item>? = null

        gateway.updateItem(
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
            photoUpdate = ItemPhotoUpdate.Removed,
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
        val gateway = FirebaseInventoryGateway(documents, photos)

        gateway.createItem(
            householdId = "household-1",
            parentItemId = "garage",
            creator = inventoryIdentity(),
            details = ItemDetails(
                name = "Drill",
                description = "18V cordless",
                tags = listOf("Power Tools"),
            ),
            photo = ItemPhoto(
                uri = "content://mystuff/cropped.webp",
                thumbnailUri = "content://mystuff/cropped-thumb.webp",
            ),
        ) { result = it }

        val created = result?.getOrThrow()
        assertEquals(
            "gs://mystuff/households/household-1/items/item-1-11111111-1111-1111-1111-111111111111.webp",
            created?.photoUrl,
        )
        assertEquals(
            "gs://mystuff/households/household-1/items/item-1-11111111-1111-1111-1111-111111111111-thumb.webp",
            created?.photoThumbnailUrl,
        )
        assertEquals(created?.photoUrl, documents.createdData?.get("photoUrl"))
        assertEquals(
            created?.photoThumbnailUrl,
            documents.createdData?.get("photoThumbnailUrl"),
        )
        assertEquals(
            listOf(
                QueuedPhotoUpload(
                    ItemPhotoVariant.Full,
                    "content://mystuff/cropped.webp",
                    "households/household-1/items/item-1-11111111-1111-1111-1111-111111111111.webp",
                ),
                QueuedPhotoUpload(
                    ItemPhotoVariant.Thumbnail,
                    "content://mystuff/cropped-thumb.webp",
                    "households/household-1/items/item-1-11111111-1111-1111-1111-111111111111-thumb.webp",
                ),
            ),
            photos.uploads,
        )
    }

    @Test
    fun `background scheduling failure does not block completed Item creation`() {
        val photos = FakeInventoryPhotoStore(enqueueFailure = IllegalStateException("scheduler"))
        val gateway = FirebaseInventoryGateway(FakeInventoryDocumentStore(), photos)
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
            photo = ItemPhoto("content://full.webp", "content://thumb.webp"),
        ) { result = it }

        assertEquals("item-1", result?.getOrThrow()?.id)
        assertEquals(
            "gs://mystuff/households/household-1/items/item-1-11111111-1111-1111-1111-111111111111.webp",
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
        val gateway = FirebaseInventoryGateway(store, FakeInventoryPhotoStore())
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
        val gateway = FirebaseInventoryGateway(store, FakeInventoryPhotoStore())
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
        val gateway = FirebaseInventoryGateway(store, FakeInventoryPhotoStore())
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
        val gateway = FirebaseInventoryGateway(store, FakeInventoryPhotoStore())
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
            photo = null,
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
        val gateway = FirebaseInventoryGateway(store, FakeInventoryPhotoStore())
        var result: Result<Item>? = null

        gateway.updateItem(
            householdId = "household-1",
            item = inventoryItem("item-1", "Drill", "garage"),
            updater = inventoryIdentity().copy(id = "member-2", displayName = "Sam"),
            details = ItemDetails(
                name = "Hammer Drill",
                description = "18V cordless",
                tags = listOf("Power Tools"),
                webUrl = "https://example.com/hammer-drill",
            ),
            photoUpdate = ItemPhotoUpdate.Unchanged,
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
        val gateway = FirebaseInventoryGateway(store, FakeInventoryPhotoStore())
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
            photo = null,
        ) { result = it }

        assertTrue(result?.isFailure == true)
        assertNull(store.createdData)
    }

    @Test
    fun `replacing an Item photo publishes a new revision without deleting the old revision`() {
        val store = FakeInventoryDocumentStore()
        val photos = FakeInventoryPhotoStore()
        val gateway = FirebaseInventoryGateway(store, photos)
        var result: Result<Item>? = null

        gateway.updateItem(
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
            photoUpdate = ItemPhotoUpdate.Replaced(
                ItemPhoto("content://new.webp", "content://new-thumb.webp"),
            ),
        ) { result = it }

        assertEquals(
            "gs://mystuff/households/household-1/items/item-1-11111111-1111-1111-1111-111111111111.webp",
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
        val gateway = FirebaseInventoryGateway(store, photos)
        var result: Result<Item>? = null

        gateway.updateItem(
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
            photoUpdate = ItemPhotoUpdate.Removed,
        ) { result = it }

        assertNull(result?.getOrThrow()?.photoUrl)
        assertNull(result?.getOrThrow()?.photoThumbnailUrl)
        assertEquals(
            listOf("gs://old/full.webp", "gs://old/thumb.webp"),
            photos.deletedLocations,
        )
    }
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

    override fun newRevision(householdId: String, itemId: String) = ItemPhotoRevision(
        locations = ItemPhotoLocations(
            full = "gs://mystuff/households/$householdId/items/$itemId-$REVISION.webp",
            thumbnail = "gs://mystuff/households/$householdId/items/$itemId-$REVISION-thumb.webp",
        ),
        fullStoragePath = "households/$householdId/items/$itemId-$REVISION.webp",
        thumbnailStoragePath = "households/$householdId/items/$itemId-$REVISION-thumb.webp",
    )

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

    override fun uploadInBackground(
        revision: ItemPhotoRevision,
        photo: ItemPhoto,
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

private const val REVISION = "11111111-1111-1111-1111-111111111111"

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
    val deletedAttachmentIds = mutableListOf<String>()

    override fun observe(
        household: Household,
        item: Item,
        onResult: (Result<List<ItemAttachment>>) -> Unit,
    ): InventorySubscription = InventorySubscription {}

    override fun newAttachmentId(householdId: String, itemId: String): String = "attachment-1"

    override fun create(
        household: Household,
        item: Item,
        attachmentId: String,
        contentType: String,
        displayUrl: String,
        onResult: (Result<ItemAttachment>) -> Unit,
    ) {
        createdDisplayUrl = displayUrl
        createdAttachment = ItemAttachment(
            id = attachmentId,
            itemId = item.id,
            createdAt = Instant.EPOCH,
            contentType = contentType,
            displayUrl = displayUrl,
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
