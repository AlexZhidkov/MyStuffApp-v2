package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirebaseInventoryGatewayTest {
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
            "gs://mystuff/households/household-1/items/item-1.webp",
            created?.photoUrl,
        )
        assertEquals(
            "gs://mystuff/households/household-1/items/item-1-thumb.webp",
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
                    "households/household-1/items/item-1.webp",
                ),
                QueuedPhotoUpload(
                    ItemPhotoVariant.Thumbnail,
                    "content://mystuff/cropped-thumb.webp",
                    "households/household-1/items/item-1-thumb.webp",
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
            "gs://mystuff/households/household-1/items/item-1.webp",
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
            ),
            photo = null,
        ) { result = it }

        assertEquals("item-1", store.createdItemId)
        assertEquals(
            mapOf(
                "householdId" to "household-1",
                "name" to "Drill",
                "parentItemId" to "garage",
                "photoUrl" to null,
                "photoThumbnailUrl" to null,
                "description" to "18V cordless",
                "tags" to listOf("Power Tools"),
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
            ),
            photoUpdate = ItemPhotoUpdate.Unchanged,
        ) { result = it }

        assertEquals(
            mapOf(
                "name" to "Hammer Drill",
                "photoUrl" to null,
                "photoThumbnailUrl" to null,
                "description" to "18V cordless",
                "tags" to listOf("Power Tools"),
                "updatedAt" to timestamp,
                "updatedById" to "member-2",
                "updatedByDisplayName" to "Sam",
            ),
            store.updatedData,
        )
        assertEquals("Hammer Drill", result?.getOrThrow()?.name)
        assertEquals(listOf("Power Tools"), result?.getOrThrow()?.tags)
    }

    @Test
    fun `replacing an Item photo supersedes both stored variants`() {
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
            "gs://mystuff/households/household-1/items/item-1.webp",
            result?.getOrThrow()?.photoUrl,
        )
        assertEquals(2, photos.uploads.size)
        assertEquals("content://new.webp", photos.uploads.first().sourceUri)
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
        assertEquals(listOf("item-1"), photos.deletedItemIds)
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
    val deletedItemIds = mutableListOf<String>()

    override fun locations(householdId: String, itemId: String) = ItemPhotoLocations(
        full = "gs://mystuff/households/$householdId/items/$itemId.webp",
        thumbnail = "gs://mystuff/households/$householdId/items/$itemId-thumb.webp",
    )

    override fun uploadInBackground(
        householdId: String,
        itemId: String,
        photo: ItemPhoto,
    ) {
        enqueueFailure?.let { throw it }
        uploads += QueuedPhotoUpload(
            ItemPhotoVariant.Full,
            photo.uri,
            "households/$householdId/items/$itemId.webp",
        )
        uploads += QueuedPhotoUpload(
            ItemPhotoVariant.Thumbnail,
            photo.thumbnailUri,
            "households/$householdId/items/$itemId-thumb.webp",
        )
    }

    override fun deleteInBackground(householdId: String, itemIds: Collection<String>) {
        deletedItemIds += itemIds
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
) = InventoryItemDocument(
    id = id,
    data = mapOf(
        "householdId" to "household-1",
        "name" to name,
        "parentItemId" to parentItemId,
        "photoUrl" to null,
        "photoThumbnailUrl" to null,
        "description" to null,
        "tags" to emptyList<String>(),
    ),
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
    description: String? = null,
    tags: List<String> = emptyList(),
) = Item(
    id = id,
    name = name,
    parentItemId = parentItemId,
    photoUrl = photoUrl,
    photoThumbnailUrl = photoThumbnailUrl,
    description = description,
    tags = tags,
)
