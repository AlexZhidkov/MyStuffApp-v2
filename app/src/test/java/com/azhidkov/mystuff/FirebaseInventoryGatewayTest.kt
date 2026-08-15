package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
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
            name = "Drill",
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
            name = "Drill",
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
            name = "Drill",
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
                "description" to null,
                "tags" to emptyList<String>(),
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
            inventoryItem("item-1", "Drill", "garage"),
            result?.getOrThrow(),
        )
    }
}

private data class QueuedPhotoUpload(
    val variant: ItemPhotoVariant,
    val sourceUri: String,
    val storagePath: String,
)

private class FakeInventoryPhotoStore(
    private val enqueueFailure: Throwable? = null,
) : InventoryPhotoStore {
    val uploads = mutableListOf<QueuedPhotoUpload>()

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
        error("Not used")
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

private fun inventoryItem(id: String, name: String, parentItemId: String?) = Item(
    id = id,
    name = name,
    parentItemId = parentItemId,
    photoUrl = null,
    photoThumbnailUrl = null,
    description = null,
    tags = emptyList(),
)
