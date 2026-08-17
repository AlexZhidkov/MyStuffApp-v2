package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryControllerTest {
    @Test
    fun `camera permission denial continues Item creation without a photo`() {
        val controller = InventoryController(household(), identity(), FakeInventoryGateway(inventory()))

        controller.beginAddItem()
        assertEquals(ItemCreationStage.CameraPermission, controller.state.itemCreationStage)

        controller.resolveCameraPermission(granted = false)

        assertEquals(ItemCreationStage.Details, controller.state.itemCreationStage)
        assertNull(controller.state.itemDraft?.photo)
    }

    @Test
    fun `unavailable camera continues Item creation without requesting capture`() {
        val controller = InventoryController(household(), identity(), FakeInventoryGateway(inventory()))

        controller.beginAddItem()
        controller.cameraUnavailable()

        assertEquals(ItemCreationStage.Details, controller.state.itemCreationStage)
        assertNull(controller.state.itemDraft?.photo)
    }

    @Test
    fun `camera capture failure continues Item creation without a photo`() {
        val controller = InventoryController(household(), identity(), FakeInventoryGateway(inventory()))

        controller.beginAddItem()
        controller.resolveCameraPermission(granted = true)
        assertEquals(ItemCreationStage.Camera, controller.state.itemCreationStage)

        controller.photoCaptureFailed()

        assertEquals(ItemCreationStage.Details, controller.state.itemCreationStage)
        assertNull(controller.state.itemDraft?.photo)
    }

    @Test
    fun `successful capture opens cropping and retake reopens the camera`() {
        val controller = InventoryController(household(), identity(), FakeInventoryGateway(inventory()))

        controller.beginAddItem()
        controller.resolveCameraPermission(granted = true)
        controller.photoCaptured(ItemPhoto("content://mystuff/captured.jpg"))

        assertEquals(ItemCreationStage.Crop, controller.state.itemCreationStage)
        assertEquals("content://mystuff/captured.jpg", controller.state.itemDraft?.photo?.uri)

        controller.retakePhoto()

        assertEquals(ItemCreationStage.Camera, controller.state.itemCreationStage)
        assertNull(controller.state.itemDraft?.photo)
    }

    @Test
    fun `using a crop continues to Item details with the cropped photo`() {
        val controller = InventoryController(household(), identity(), FakeInventoryGateway(inventory()))
        controller.beginAddItem()
        controller.resolveCameraPermission(granted = true)
        controller.photoCaptured(ItemPhoto("content://mystuff/captured.jpg"))

        controller.useCroppedPhoto(
            ItemPhoto(
                "content://mystuff/cropped.webp",
                "content://mystuff/cropped-thumb.webp",
            ),
        )

        assertEquals(ItemCreationStage.Details, controller.state.itemCreationStage)
        assertEquals("content://mystuff/cropped.webp", controller.state.itemDraft?.photo?.uri)
    }

    @Test
    fun `Member can omit a successfully captured photo`() {
        val controller = InventoryController(household(), identity(), FakeInventoryGateway(inventory()))
        controller.beginAddItem()
        controller.resolveCameraPermission(granted = true)
        controller.photoCaptured(ItemPhoto("content://mystuff/captured.jpg"))

        controller.continueWithoutPhoto()

        assertEquals(ItemCreationStage.Details, controller.state.itemCreationStage)
        assertNull(controller.state.itemDraft?.photo)
    }

    @Test
    fun `saved Item keeps its cropped photo`() {
        val gateway = FakeInventoryGateway(inventory())
        val controller = InventoryController(household(), identity(), gateway)
        controller.beginAddItem()
        controller.resolveCameraPermission(granted = true)
        controller.photoCaptured(ItemPhoto("content://mystuff/captured.jpg"))
        controller.useCroppedPhoto(
            ItemPhoto(
                "content://mystuff/cropped.webp",
                "content://mystuff/cropped-thumb.webp",
            ),
        )
        controller.changeItemName("Drill")

        controller.saveItem()

        assertEquals(
            ItemPhoto(
                "content://mystuff/cropped.webp",
                "content://mystuff/cropped-thumb.webp",
            ),
            gateway.createdPhoto,
        )
        assertEquals(
            "gs://mystuff/households/household-1/items/item-1.webp",
            controller.state.childItems.single().photoUrl,
        )
        assertEquals(
            "gs://mystuff/households/household-1/items/item-1-thumb.webp",
            controller.state.childItems.single().photoThumbnailUrl,
        )
    }

    @Test
    fun `Member browses immediate Child Items and a complete deep Item Path`() {
        val household = household()
        val inventory = Inventory.from(
            household = household,
            items = listOf(
                household.rootItem,
                item("garage", "Garage", household.id),
                item("cabinet", "Cabinet", "garage"),
                item("drill", "Drill", "cabinet"),
            ),
        )
        val gateway = FakeInventoryGateway(inventory)
        val controller = InventoryController(household, identity(), gateway)

        assertEquals(listOf("Garage"), controller.state.childItems.map(Item::name))

        controller.openItem("garage")
        assertEquals("Garage", controller.state.selectedItem.name)
        assertEquals(listOf("Cabinet"), controller.state.childItems.map(Item::name))

        controller.openItem("cabinet")
        controller.openItem("drill")
        assertEquals(
            listOf("Our Home", "Garage", "Cabinet", "Drill"),
            controller.state.itemPath.map(Item::name),
        )
    }

    @Test
    fun `Add item always uses the displayed Item as its Parent Item`() {
        val household = household()
        val inventory = Inventory.from(
            household,
            listOf(
                household.rootItem,
                item("garage", "Garage", household.id),
            ),
        )
        val gateway = FakeInventoryGateway(inventory)
        val controller = InventoryController(household, identity(), gateway)

        controller.beginAddItem()
        assertEquals("household-1", controller.state.itemDraft?.parentItemId)
        controller.cancelAddItem()

        controller.openItem("garage")
        controller.beginAddItem()
        assertEquals("garage", controller.state.itemDraft?.parentItemId)

        controller.changeItemName("  Drill  ")
        controller.saveItem()

        assertEquals("garage", gateway.createdParentItemId)
        assertEquals("Drill", gateway.createdName)
        assertNull(controller.state.itemDraft)
        assertEquals("garage", controller.state.selectedItem.id)
        assertEquals(listOf("Drill"), controller.state.childItems.map(Item::name))
    }

    @Test
    fun `Item names are trimmed and contain one to one hundred Unicode characters`() {
        val gateway = FakeInventoryGateway(inventory())
        val controller = InventoryController(household(), identity(), gateway)

        controller.beginAddItem()
        controller.changeItemName("   ")
        controller.saveItem()
        assertEquals("Enter an Item name.", controller.state.itemDraft?.nameError)

        controller.changeItemName("🏠".repeat(101))
        controller.saveItem()
        assertEquals(
            "Item names can contain at most 100 characters.",
            controller.state.itemDraft?.nameError,
        )

        controller.changeItemName("  ${"🏠".repeat(100)}  ")
        controller.saveItem()
        assertEquals("🏠".repeat(100), gateway.createdName)
    }

    @Test
    fun `duplicate Item names are allowed beneath the same Parent Item`() {
        val gateway = FakeInventoryGateway(inventory())
        val controller = InventoryController(household(), identity(), gateway)

        repeat(2) {
            controller.beginAddItem()
            controller.changeItemName("Box")
            controller.saveItem()
        }

        assertEquals(listOf("Box", "Box"), controller.state.childItems.map(Item::name))
    }

    @Test
    fun `Member creates an Item with a description and normalized unique Tags`() {
        val gateway = FakeInventoryGateway(inventory())
        val controller = InventoryController(household(), identity(), gateway)

        controller.beginAddItem()
        controller.cameraUnavailable()
        controller.changeItemName("Drill")
        controller.changeItemDescription("Cordless hammer drill")
        controller.changeTagInput("  Powér Tools  ")
        controller.addTag()
        controller.changeTagInput("power tools")
        controller.addTag()

        assertEquals(listOf("Powér Tools"), controller.state.itemDraft?.tags)
        assertEquals("That Tag is already on this Item.", controller.state.itemDraft?.tagError)

        controller.saveItem()

        assertEquals("Cordless hammer drill", gateway.createdDetails?.description)
        assertEquals(listOf("Powér Tools"), gateway.createdDetails?.tags)
    }

    @Test
    fun `Item descriptions and Tags enforce their Unicode character and count limits`() {
        val controller = InventoryController(household(), identity(), FakeInventoryGateway(inventory()))
        controller.beginAddItem()
        controller.cameraUnavailable()
        controller.changeItemName("Drill")

        controller.changeItemDescription("🏠".repeat(2_001))
        controller.saveItem()
        assertEquals(
            "Descriptions can contain at most 2,000 characters.",
            controller.state.itemDraft?.descriptionError,
        )

        controller.changeItemDescription("🏠".repeat(2_000))
        controller.changeTagInput("🏠".repeat(41))
        controller.addTag()
        assertEquals("Tags can contain at most 40 characters.", controller.state.itemDraft?.tagError)

        repeat(20) { index ->
            controller.changeTagInput("Tag $index")
            controller.addTag()
        }
        controller.changeTagInput("One too many")
        controller.addTag()
        assertEquals("An Item can have at most 20 Tags.", controller.state.itemDraft?.tagError)
    }

    @Test
    fun `Item form suggests existing Household Tags without requiring an exact accent match`() {
        val household = household()
        val controller = InventoryController(
            household,
            identity(),
            FakeInventoryGateway(
                Inventory.from(
                    household,
                    listOf(
                        household.rootItem,
                        item(
                            id = "drill",
                            name = "Drill",
                            parentItemId = household.id,
                            tags = listOf("Powér Tools", "Cordless"),
                        ),
                    ),
                ),
            ),
        )
        controller.beginAddItem()
        controller.cameraUnavailable()

        controller.changeTagInput("power")

        assertEquals(listOf("Powér Tools"), controller.state.tagSuggestions)
        controller.addSuggestedTag("Powér Tools")
        assertEquals(listOf("Powér Tools"), controller.state.itemDraft?.tags)
    }

    @Test
    fun `failed Item edit keeps every field open and can be retried successfully`() {
        val household = household()
        val existing = item(
            id = "drill",
            name = "Drill",
            parentItemId = household.id,
            photoUrl = "gs://mystuff/households/household-1/items/drill.webp",
            photoThumbnailUrl = "gs://mystuff/households/household-1/items/drill-thumb.webp",
            description = "Old description",
            tags = listOf("Corded"),
        )
        val gateway = FakeInventoryGateway(
            Inventory.from(household, listOf(household.rootItem, existing)),
        )
        val controller = InventoryController(household, identity(), gateway)
        controller.openItem(existing.id)
        controller.beginEditItem()
        assertEquals("Old description", controller.state.itemDraft?.description)
        assertEquals(listOf("Corded"), controller.state.itemDraft?.tags)

        controller.changeItemName("Hammer Drill")
        controller.changeItemDescription("New description")
        controller.removeTag("corded")
        controller.changeTagInput("Power Tools")
        controller.addTag()
        controller.beginReplaceItemPhoto()
        controller.resolveCameraPermission(granted = true)
        controller.photoCaptured(ItemPhoto("content://captured.jpg"))
        controller.useCroppedPhoto(ItemPhoto("content://new.webp", "content://new-thumb.webp"))
        gateway.nextUpdateFailure = IllegalStateException("No connection")

        controller.saveItem()

        assertFalse(controller.state.operationInProgress)
        assertEquals("No connection", controller.state.errorMessage)
        assertEquals("Hammer Drill", controller.state.itemDraft?.name)
        assertEquals(1, gateway.updateAttempts)

        controller.saveItem()

        assertNull(controller.state.itemDraft)
        assertEquals("Hammer Drill", controller.state.selectedItem.name)
        assertEquals("New description", controller.state.selectedItem.description)
        assertEquals(listOf("Power Tools"), controller.state.selectedItem.tags)
        assertEquals("Item saved.", controller.state.successMessage)
        assertEquals(2, gateway.updateAttempts)
    }

    @Test
    fun `Item form reports save progress until creation succeeds`() {
        val gateway = FakeInventoryGateway(inventory()).apply { deferCreates = true }
        val controller = InventoryController(household(), identity(), gateway)
        controller.beginAddItem()
        controller.cameraUnavailable()
        controller.changeItemName("Drill")

        controller.saveItem()

        assertTrue(controller.state.operationInProgress)
        assertEquals("Drill", controller.state.itemDraft?.name)

        gateway.completeCreate()

        assertFalse(controller.state.operationInProgress)
        assertNull(controller.state.itemDraft)
        assertEquals("Item saved.", controller.state.successMessage)
    }

    @Test(expected = InvalidInventoryException::class)
    fun `Inventory rejects a disconnected Item`() {
        Inventory.from(
            household(),
            listOf(
                household().rootItem,
                item("lost", "Lost", "missing-parent"),
            ),
        )
    }

    @Test(expected = InvalidInventoryException::class)
    fun `Inventory rejects another parentless Item`() {
        Inventory.from(
            household(),
            listOf(
                household().rootItem,
                item("another-root", "Another root", null),
            ),
        )
    }
}

private class FakeInventoryGateway(
    initialInventory: Inventory,
) : InventoryGateway {
    private var inventory = initialInventory
    private var observer: ((Result<Inventory>) -> Unit)? = null
    private var nextId = 1

    var createdParentItemId: String? = null
        private set
    var createdName: String? = null
        private set
    var createdPhoto: ItemPhoto? = null
        private set
    var createdDetails: ItemDetails? = null
        private set
    var nextUpdateFailure: Throwable? = null
    var updateAttempts: Int = 0
        private set
    var deferCreates: Boolean = false
    private var pendingCreate: (() -> Unit)? = null

    override fun observe(
        household: Household,
        onResult: (Result<Inventory>) -> Unit,
    ): InventorySubscription {
        observer = onResult
        onResult(Result.success(inventory))
        return InventorySubscription { observer = null }
    }

    override fun createItem(
        householdId: String,
        parentItemId: String,
        creator: AuthenticatedIdentity,
        details: ItemDetails,
        photo: ItemPhoto?,
        onResult: (Result<Item>) -> Unit,
    ) {
        createdParentItemId = parentItemId
        createdName = details.name
        createdDetails = details
        createdPhoto = photo
        val created = item(
            id = "created-${nextId++}",
            name = details.name,
            parentItemId = parentItemId,
            photoUrl = photo?.let {
                "gs://mystuff/households/household-1/items/item-1.webp"
            },
            photoThumbnailUrl = photo?.let {
                "gs://mystuff/households/household-1/items/item-1-thumb.webp"
            },
            description = details.description,
            tags = details.tags,
        )
        val complete: () -> Unit = {
            inventory = inventory.withItem(created)
            onResult(Result.success(created))
            observer?.invoke(Result.success(inventory))
        }
        if (deferCreates) {
            pendingCreate = complete
        } else {
            complete()
        }
    }

    fun completeCreate() {
        requireNotNull(pendingCreate).also { pendingCreate = null }.invoke()
    }

    override fun updateItem(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        details: ItemDetails,
        photoUpdate: ItemPhotoUpdate,
        onResult: (Result<Item>) -> Unit,
    ) {
        updateAttempts += 1
        nextUpdateFailure?.let { failure ->
            nextUpdateFailure = null
            onResult(Result.failure(failure))
            return
        }
        val updated = item.copy(
            name = details.name,
            description = details.description,
            tags = details.tags,
            photoUrl = when (photoUpdate) {
                ItemPhotoUpdate.Unchanged -> item.photoUrl
                ItemPhotoUpdate.Removed -> null
                is ItemPhotoUpdate.Replaced ->
                    "gs://mystuff/households/household-1/items/${item.id}.webp"
            },
            photoThumbnailUrl = when (photoUpdate) {
                ItemPhotoUpdate.Unchanged -> item.photoThumbnailUrl
                ItemPhotoUpdate.Removed -> null
                is ItemPhotoUpdate.Replaced ->
                    "gs://mystuff/households/household-1/items/${item.id}-thumb.webp"
            },
        )
        inventory = inventory.withItem(updated)
        onResult(Result.success(updated))
    }
}

private fun household() = Household(
    id = "household-1",
    ownerMemberId = "member-1",
    rootItem = item("household-1", "Our Home", null),
)

private fun identity() = AuthenticatedIdentity(
    id = "member-1",
    displayName = "Alex",
    email = "alex@example.com",
)

private fun inventory(): Inventory {
    val household = household()
    return Inventory.from(household, listOf(household.rootItem))
}

private fun item(
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
    description = description,
    tags = tags,
    photoThumbnailUrl = photoThumbnailUrl,
)
