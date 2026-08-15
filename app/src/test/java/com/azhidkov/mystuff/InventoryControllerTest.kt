package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        controller.useCroppedPhoto(ItemPhoto("content://mystuff/cropped.jpg"))

        assertEquals(ItemCreationStage.Details, controller.state.itemCreationStage)
        assertEquals("content://mystuff/cropped.jpg", controller.state.itemDraft?.photo?.uri)
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
        controller.useCroppedPhoto(ItemPhoto("content://mystuff/cropped.jpg"))
        controller.changeItemName("Drill")

        controller.saveItem()

        assertEquals(ItemPhoto("content://mystuff/cropped.jpg"), gateway.createdPhoto)
        assertEquals(
            "gs://mystuff/households/household-1/items/item-1/photo.jpg",
            controller.state.childItems.single().photoUrl,
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
        name: String,
        photo: ItemPhoto?,
        onResult: (Result<Item>) -> Unit,
    ) {
        createdParentItemId = parentItemId
        createdName = name
        createdPhoto = photo
        val created = item(
            id = "created-${nextId++}",
            name = name,
            parentItemId = parentItemId,
            photoUrl = photo?.let {
                "gs://mystuff/households/household-1/items/item-1/photo.jpg"
            },
        )
        inventory = inventory.withItem(created)
        onResult(Result.success(created))
        observer?.invoke(Result.success(inventory))
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
) = Item(
    id = id,
    name = name,
    parentItemId = parentItemId,
    photoUrl = photoUrl,
    description = null,
    tags = emptyList(),
)
