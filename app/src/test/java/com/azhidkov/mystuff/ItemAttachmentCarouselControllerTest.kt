package com.azhidkov.mystuff

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemAttachmentCarouselControllerTest {
    @Test
    fun `opening carousel exposes loading then complete attachment collection`() {
        val gateway = RecordingItemAttachmentGateway()
        val controller = InventoryController(
            household = carouselHousehold(),
            identity = carouselIdentity(),
            gateway = CarouselInventoryGateway(),
            rootChildItemCache = NoRootChildItemCache,
            itemAttachmentGateway = gateway,
        )

        controller.openItem("item-1")
        controller.openItemAttachmentCarousel()

        assertTrue(controller.state.itemAttachmentCarousel?.loading == true)
        assertEquals("item-1", gateway.observedItem?.id)

        gateway.complete(
            listOf(
                carouselControllerAttachment("photo", 1),
                carouselControllerAttachment("receipt", 2),
            ),
        )

        assertFalse(controller.state.itemAttachmentCarousel?.loading == true)
        assertEquals(
            listOf("photo", "receipt"),
            controller.state.itemAttachmentCarousel?.attachments?.map(ItemAttachment::id),
        )
    }

    @Test
    fun `attachment loading failure closes with an actionable error state`() {
        val gateway = RecordingItemAttachmentGateway()
        val controller = InventoryController(
            household = carouselHousehold(),
            identity = carouselIdentity(),
            gateway = CarouselInventoryGateway(),
            rootChildItemCache = NoRootChildItemCache,
            itemAttachmentGateway = gateway,
        )

        controller.openItem("item-1")
        controller.openItemAttachmentCarousel()
        gateway.fail(IllegalStateException("offline"))

        assertEquals("offline", controller.state.itemAttachmentCarousel?.errorMessage)
        assertFalse(controller.state.itemAttachmentCarousel?.loading == true)

        controller.closeItemAttachmentCarousel()

        assertNull(controller.state.itemAttachmentCarousel)
    }
}

private class RecordingItemAttachmentGateway : ItemAttachmentGateway {
    var observedItem: Item? = null
    private var callback: ((Result<List<ItemAttachment>>) -> Unit)? = null

    override fun observe(
        household: Household,
        item: Item,
        onResult: (Result<List<ItemAttachment>>) -> Unit,
    ): InventorySubscription {
        observedItem = item
        callback = onResult
        return InventorySubscription {}
    }

    fun complete(attachments: List<ItemAttachment>) {
        callback?.invoke(Result.success(attachments))
    }

    fun fail(failure: Throwable) {
        callback?.invoke(Result.failure(failure))
    }

    override fun newAttachmentId(householdId: String, itemId: String): String = "new"

    override fun create(
        household: Household,
        item: Item,
        attachmentId: String,
        contentType: String,
        displayUrl: String,
        onResult: (Result<ItemAttachment>) -> Unit,
    ) = error("Not used")

    override fun delete(
        household: Household,
        item: Item,
        attachment: ItemAttachment,
        onResult: (Result<Unit>) -> Unit,
    ) = error("Not used")
}

private class CarouselInventoryGateway : InventoryGateway {
    private val household = carouselHousehold()

    override fun observe(
        household: Household,
        onResult: (Result<Inventory>) -> Unit,
    ): InventorySubscription {
        onResult(Result.success(Inventory.from(household, listOf(household.rootItem, carouselItem()))))
        return InventorySubscription {}
    }

    override fun newItemId(householdId: String): String = "new"

    override fun createItem(
        householdId: String,
        parentItemId: String,
        creator: AuthenticatedIdentity,
        details: ItemDetails,
        photo: ItemPhoto?,
        onResult: (Result<Item>) -> Unit,
    ) = error("Not used")

    override fun updateItem(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        details: ItemDetails,
        photoUpdate: ItemPhotoUpdate,
        onResult: (Result<Item>) -> Unit,
    ) = error("Not used")
}

private fun carouselHousehold() = Household(
    id = "household-1",
    ownerMemberId = "member-1",
    rootItem = Item(
        id = "household-1",
        name = "Our Home",
        parentItemId = null,
        photoUrl = null,
        description = null,
        tags = emptyList(),
    ),
)

private fun carouselItem() = Item(
    id = "item-1",
    name = "Drill",
    parentItemId = "household-1",
    photoUrl = "gs://mystuff/photo.webp",
    description = null,
    tags = emptyList(),
    photoAttachmentId = "photo",
)

private fun carouselIdentity() = AuthenticatedIdentity(
    id = "member-1",
    email = "alex@example.com",
    displayName = "Alex",
)

private fun carouselControllerAttachment(id: String, seconds: Long) = ItemAttachment(
    id = id,
    itemId = "item-1",
    createdAt = Instant.ofEpochSecond(seconds),
    contentType = OPTIMIZED_ATTACHMENT_IMAGE_CONTENT_TYPE,
    displayUrl = "gs://mystuff/$id.webp",
)
