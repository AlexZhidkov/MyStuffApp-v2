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
        val inventoryGateway = CarouselInventoryGateway()
        val controller = InventoryController(
            household = carouselHousehold(),
            identity = carouselIdentity(),
            gateway = inventoryGateway,
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

    @Test
    fun `designating an attachment updates the Item Photo projection`() {
        val gateway = RecordingItemAttachmentGateway()
        val inventoryGateway = CarouselInventoryGateway()
        val controller = InventoryController(
            household = carouselHousehold(),
            identity = carouselIdentity(),
            gateway = inventoryGateway,
            rootChildItemCache = NoRootChildItemCache,
            itemAttachmentGateway = gateway,
        )
        val attachment = carouselControllerAttachment("receipt", 2)

        controller.openItem("item-1")
        controller.openItemAttachmentCarousel()
        gateway.complete(listOf(carouselControllerAttachment("photo", 1), attachment))
        controller.designateItemPhoto(attachment)

        assertEquals("receipt", controller.state.selectedItem.photoAttachmentId)
        assertEquals(attachment.displayUrl, controller.state.selectedItem.photoUrl)
        assertEquals("receipt", inventoryGateway.designatedAttachment?.id)
    }

    @Test
    fun `deleting an Item Photo promotes the oldest remaining attachment`() {
        val gateway = RecordingItemAttachmentGateway()
        val inventoryGateway = CarouselInventoryGateway()
        val controller = InventoryController(
            household = carouselHousehold(),
            identity = carouselIdentity(),
            gateway = inventoryGateway,
            rootChildItemCache = NoRootChildItemCache,
            itemAttachmentGateway = gateway,
        )
        val photo = carouselControllerAttachment("photo", 1)
        val receipt = carouselControllerAttachment("receipt", 2)

        controller.openItem("item-1")
        controller.openItemAttachmentCarousel()
        gateway.complete(listOf(photo, receipt))
        controller.deleteItemAttachment(photo)

        assertEquals("receipt", controller.state.selectedItem.photoAttachmentId)
        assertEquals(
            listOf("receipt"),
            controller.state.itemAttachmentCarousel?.attachments?.map(ItemAttachment::id),
        )
        assertEquals("photo", inventoryGateway.deletedAttachment?.id)
    }

    @Test
    fun `deleting the last attachment clears the Item Photo and closes the carousel`() {
        val attachmentGateway = RecordingItemAttachmentGateway()
        val inventoryGateway = CarouselInventoryGateway()
        val controller = InventoryController(
            household = carouselHousehold(),
            identity = carouselIdentity(),
            gateway = inventoryGateway,
            rootChildItemCache = NoRootChildItemCache,
            itemAttachmentGateway = attachmentGateway,
        )
        val photo = carouselControllerAttachment("photo", 1)

        controller.openItem("item-1")
        controller.openItemAttachmentCarousel()
        attachmentGateway.complete(listOf(photo))
        controller.deleteItemAttachment(photo)

        assertNull(controller.state.selectedItem.photoAttachmentId)
        assertNull(controller.state.selectedItem.photoUrl)
        assertNull(controller.state.itemAttachmentCarousel)
    }
}

private class RecordingItemAttachmentGateway : ItemAttachmentGateway {
    var observedItem: Item? = null
    private var callback: ((Result<List<ItemAttachment>>) -> Unit)? = null
    var designatedAttachment: ItemAttachment? = null
    var deletedAttachment: ItemAttachment? = null

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
    ) {
        deletedAttachment = attachment
        onResult(Result.success(Unit))
    }
}

private class CarouselInventoryGateway : InventoryGateway {
    private val household = carouselHousehold()
    var designatedAttachment: ItemAttachment? = null
    var deletedAttachment: ItemAttachment? = null

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

    override fun designateItemPhoto(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        attachment: ItemAttachment,
        onResult: (Result<Item>) -> Unit,
    ) = onResult(
        Result.success(
            item.copy(
                photoAttachmentId = attachment.id,
                photoUrl = attachment.displayUrl,
                photoThumbnailUrl = "gs://mystuff/${attachment.id}-thumb.webp",
            ),
        ),
    ).also { designatedAttachment = attachment }

    override fun deleteItemAttachment(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        attachment: ItemAttachment,
        remainingAttachments: List<ItemAttachment>,
        onResult: (Result<Item>) -> Unit,
    ) {
        deletedAttachment = attachment
        val promoted = remainingAttachments.minWithOrNull(
            compareBy<ItemAttachment>({ it.creationOrder ?: Long.MAX_VALUE }, ItemAttachment::createdAt),
        ).takeIf { item.photoAttachmentId == attachment.id }
        onResult(
            Result.success(
                item.copy(
                    photoAttachmentId = promoted?.id,
                    photoUrl = promoted?.displayUrl,
                    photoThumbnailUrl = promoted?.let { "gs://mystuff/${it.id}-thumb.webp" },
                ),
            ),
        )
    }
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
