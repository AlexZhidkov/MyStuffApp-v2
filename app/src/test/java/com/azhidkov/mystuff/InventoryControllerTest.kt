package com.azhidkov.mystuff

import com.azhidkov.mystuff.ui.presentDeferredInventoryError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryControllerTest {
    @Test
    fun `cached root Child Items are available before the live Inventory loads`() {
        val household = household()
        val cachedGarage = item(
            id = "garage",
            name = "Garage",
            parentItemId = household.id,
            photoThumbnailUrl = "gs://mystuff/garage-thumb.webp",
            description = "West side",
            tags = listOf("Storage"),
        )
        val controller = InventoryController(
            household = household,
            identity = identity(),
            gateway = FakeInventoryGateway(inventory(), emitInitialInventory = false),
            rootChildItemCache = RecordingRootChildItemCache(listOf(cachedGarage)),
        )

        assertEquals(listOf(cachedGarage), controller.state.childItems)
        assertTrue(controller.state.loading)
    }

    @Test
    fun `live Inventory refresh caches only root Child Items`() {
        val household = household()
        val garage = item("garage", "Garage", household.id)
        val cabinet = item("cabinet", "Cabinet", garage.id)
        val cache = RecordingRootChildItemCache()

        InventoryController(
            household = household,
            identity = identity(),
            gateway = FakeInventoryGateway(
                Inventory.from(household, listOf(household.rootItem, garage, cabinet)),
            ),
            rootChildItemCache = cache,
        )

        assertEquals(listOf(garage), cache.storedItems)
    }

    @Test
    fun `Household search matches normalized substrings and excludes its root Item`() {
        val household = household()
        val controller = InventoryController(
            household,
            identity(),
            FakeInventoryGateway(
                Inventory.from(
                    household,
                    listOf(
                        household.rootItem,
                        item("cafe-table", "Café Table", household.id),
                        item("drill", "Drill", household.id, tags = listOf("Powér Tools")),
                        item(
                            "charger",
                            "Charger",
                            household.id,
                            description = "Spare BATTERY pack",
                        ),
                    ),
                ),
            ),
        )

        controller.changeSearchQuery("CAFE")

        assertEquals(listOf("cafe-table"), controller.state.searchResults.map { it.item.id })

        controller.changeSearchQuery("power")
        assertEquals(listOf("drill"), controller.state.searchResults.map { it.item.id })

        controller.changeSearchQuery("battery")
        assertEquals(listOf("charger"), controller.state.searchResults.map { it.item.id })

        controller.changeSearchQuery("our home")
        assertTrue(controller.state.searchResults.isEmpty())
    }

    @Test
    fun `Household search ranks field priority before exact prefix and substring matches`() {
        val household = household()
        val controller = InventoryController(
            household,
            identity(),
            FakeInventoryGateway(
                Inventory.from(
                    household,
                    listOf(
                        household.rootItem,
                        item("description-substring", "Manual", household.id, description = "Using a drill safely"),
                        item("tag-prefix", "Bit Set", household.id, tags = listOf("Drill bits")),
                        item("name-substring", "Cordless Drill Kit", household.id),
                        item("description-exact", "Reference", household.id, description = "Drill"),
                        item("tag-substring", "Toolbox", household.id, tags = listOf("Cordless drill set")),
                        item("name-prefix", "Drill Press", household.id),
                        item("description-prefix", "Instructions", household.id, description = "Drill maintenance"),
                        item("tag-exact", "Impact Driver", household.id, tags = listOf("Drill")),
                        item("name-exact", "Drill", household.id),
                    ),
                ),
            ),
        )

        controller.changeSearchQuery("drill")

        assertEquals(
            listOf(
                "name-exact",
                "name-prefix",
                "name-substring",
                "tag-exact",
                "tag-prefix",
                "tag-substring",
                "description-exact",
                "description-prefix",
                "description-substring",
            ),
            controller.state.searchResults.map { it.item.id },
        )

        controller.changeSearchQuery("drilx")
        assertTrue(controller.state.searchResults.isEmpty())
    }

    @Test
    fun `Search adds vector results beneath precise literal matches after its debounce`() {
        val household = household()
        val searchGateway = RecordingSearchGateway()
        val searchDebouncer = RecordingSearchDebouncer()
        val controller = InventoryController(
            household = household,
            identity = identity(),
            gateway = FakeInventoryGateway(
                Inventory.from(
                    household,
                    listOf(
                        household.rootItem,
                        item("watch-box", "Watch Box", household.id),
                        item("clock", "Clock", household.id),
                        item(
                            "manual",
                            "Warranty Manual",
                            household.id,
                            description = "Watch the battery indicator",
                        ),
                    ),
                ),
            ),
            rootChildItemCache = NoRootChildItemCache,
            searchGateway = searchGateway,
            searchDebouncer = searchDebouncer,
        )

        controller.changeSearchQuery("watch")

        assertEquals(listOf("watch-box", "manual"), controller.state.searchResults.map { it.item.id })
        assertEquals(500L, searchDebouncer.delayMillis)
        assertTrue(searchGateway.queries.isEmpty())

        searchDebouncer.runPending()

        assertTrue(controller.state.search.isConceptualSearchLoading)
        assertEquals(listOf("watch"), searchGateway.queries)

        searchGateway.complete(listOf("clock", "watch-box"))

        assertFalse(controller.state.search.isConceptualSearchLoading)
        assertEquals(
            listOf("watch-box", "clock"),
            controller.state.searchResults.map { it.item.id },
        )
    }

    @Test
    fun `Search sends only queries with at least three Unicode letters or digits`() {
        val household = household()
        val searchGateway = RecordingSearchGateway()
        val searchDebouncer = RecordingSearchDebouncer()
        val controller = InventoryController(
            household = household,
            identity = identity(),
            gateway = FakeInventoryGateway(inventory()),
            rootChildItemCache = NoRootChildItemCache,
            searchGateway = searchGateway,
            searchDebouncer = searchDebouncer,
        )

        controller.changeSearchQuery("!?é2")
        assertNull(searchDebouncer.delayMillis)

        controller.changeSearchQuery("!?é23")
        searchDebouncer.runPending()

        assertEquals(listOf("!?é23"), searchGateway.queries)
    }

    @Test
    fun `failed conceptual Search silently restores every literal result`() {
        val household = household()
        val searchGateway = RecordingSearchGateway()
        val searchDebouncer = RecordingSearchDebouncer()
        val controller = InventoryController(
            household = household,
            identity = identity(),
            gateway = FakeInventoryGateway(
                Inventory.from(
                    household,
                    listOf(
                        household.rootItem,
                        item("name", "Watch Box", household.id),
                        item("description", "Manual", household.id, description = "Watch care"),
                    ),
                ),
            ),
            rootChildItemCache = NoRootChildItemCache,
            searchGateway = searchGateway,
            searchDebouncer = searchDebouncer,
        )

        controller.changeSearchQuery("watch")
        searchDebouncer.runPending()
        searchGateway.fail()

        assertFalse(controller.state.search.isConceptualSearchLoading)
        assertNull(controller.state.search.conceptualResultIds)
        assertEquals(
            listOf("name", "description"),
            controller.state.searchResults.map { it.item.id },
        )
        assertNull(controller.state.errorMessage)
    }

    @Test
    fun `successful conceptual Search ranks precise matches then deduplicated vector results`() {
        val household = household()
        val searchGateway = RecordingSearchGateway()
        val searchDebouncer = RecordingSearchDebouncer()
        val controller = InventoryController(
            household = household,
            identity = identity(),
            gateway = FakeInventoryGateway(
                Inventory.from(
                    household,
                    listOf(
                        household.rootItem,
                        item("exact", "Watch", household.id),
                        item("prefix", "Watch Box", household.id),
                        item("tag", "Timepiece", household.id, tags = listOf("Watch")),
                        item("description", "Manual", household.id, description = "Watch care"),
                        item("clock", "Clock", household.id),
                    ),
                ),
            ),
            rootChildItemCache = NoRootChildItemCache,
            searchGateway = searchGateway,
            searchDebouncer = searchDebouncer,
        )

        controller.changeSearchQuery("watch")
        searchDebouncer.runPending()
        searchGateway.complete(
            listOf("description", "tag", "clock", "exact", "missing", household.id, "clock"),
        )

        assertEquals(
            listOf("exact", "prefix", "tag", "description", "clock"),
            controller.state.searchResults.map { it.item.id },
        )
    }

    @Test
    fun `late conceptual response cannot replace results for a newer query`() {
        val household = household()
        val searchGateway = UncooperativeSearchGateway()
        val searchDebouncer = RecordingSearchDebouncer()
        val controller = InventoryController(
            household = household,
            identity = identity(),
            gateway = FakeInventoryGateway(
                Inventory.from(
                    household,
                    listOf(
                        household.rootItem,
                        item("clock", "Clock", household.id),
                        item("drill", "Drill", household.id),
                    ),
                ),
            ),
            rootChildItemCache = NoRootChildItemCache,
            searchGateway = searchGateway,
            searchDebouncer = searchDebouncer,
        )

        controller.changeSearchQuery("watch")
        searchDebouncer.runPending()
        controller.changeSearchQuery("drill")
        searchGateway.complete("watch", listOf("clock"))

        assertNull(controller.state.search.conceptualResultIds)
        assertEquals(listOf("drill"), controller.state.searchResults.map { it.item.id })
    }

    @Test
    fun `opening a search result exposes its parent Item Path details and Child Items`() {
        val household = household()
        val controller = InventoryController(
            household,
            identity(),
            FakeInventoryGateway(
                Inventory.from(
                    household,
                    listOf(
                        household.rootItem,
                        item("garage", "Garage", household.id),
                        item("cabinet", "Cabinet", "garage"),
                        item(
                            "drill",
                            "Drill",
                            "cabinet",
                            photoThumbnailUrl = "gs://mystuff/drill-thumb.webp",
                        ),
                        item("battery", "Battery", "drill"),
                        item("saw", "Saw", "garage"),
                    ),
                ),
            ),
        )
        controller.changeSearchQuery("drill")

        val result = controller.state.searchResults.single()

        assertEquals("gs://mystuff/drill-thumb.webp", result.item.photoThumbnailUrl)
        assertEquals(
            listOf("Garage", "Cabinet"),
            result.itemPath.map(Item::name),
        )
        assertEquals("Garage → Cabinet", result.itemPathText)

        controller.openSearchResult(result.item.id)

        assertEquals("Drill", controller.state.selectedItem.name)
        assertEquals(listOf("Battery"), controller.state.childItems.map(Item::name))

        controller.beginAddItem()
        assertEquals("cabinet", controller.state.itemDraft?.parentItemId)

        controller.closeItemForm()
        controller.changeSearchQuery("saw")
        controller.beginAddItem()
        assertEquals(household.id, controller.state.itemDraft?.parentItemId)
    }

    @Test
    fun `camera permission denial continues Item creation without a photo`() {
        val controller = InventoryController(household(), identity(), FakeInventoryGateway(inventory()))

        controller.beginAddItem()
        assertEquals(ItemFormStage.CameraPermission, controller.state.itemFormStage)

        controller.resolveCameraPermission(granted = false)

        assertEquals(ItemFormStage.Details, controller.state.itemFormStage)
        assertNull(controller.state.itemDraft?.photo)
    }

    @Test
    fun `unavailable camera continues Item creation without requesting capture`() {
        val controller = InventoryController(household(), identity(), FakeInventoryGateway(inventory()))

        controller.beginAddItem()
        controller.cameraUnavailable()

        assertEquals(ItemFormStage.Details, controller.state.itemFormStage)
        assertNull(controller.state.itemDraft?.photo)
    }

    @Test
    fun `camera capture failure continues Item creation without a photo`() {
        val controller = InventoryController(household(), identity(), FakeInventoryGateway(inventory()))

        controller.beginAddItem()
        controller.resolveCameraPermission(granted = true)
        assertEquals(ItemFormStage.Camera, controller.state.itemFormStage)

        controller.photoCaptureFailed()

        assertEquals(ItemFormStage.Details, controller.state.itemFormStage)
        assertNull(controller.state.itemDraft?.photo)
    }

    @Test
    fun `successful capture opens cropping and retake reopens the camera`() {
        val controller = InventoryController(household(), identity(), FakeInventoryGateway(inventory()))

        controller.beginAddItem()
        controller.resolveCameraPermission(granted = true)
        controller.photoCaptured(ItemPhoto("content://mystuff/captured.jpg"))

        assertEquals(ItemFormStage.Crop, controller.state.itemFormStage)
        assertEquals("content://mystuff/captured.jpg", controller.state.itemDraft?.photo?.uri)

        controller.retakePhoto()

        assertEquals(ItemFormStage.Camera, controller.state.itemFormStage)
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

        assertEquals(ItemFormStage.Details, controller.state.itemFormStage)
        assertEquals("content://mystuff/cropped.webp", controller.state.itemDraft?.photo?.uri)
    }

    @Test
    fun `Member can omit a successfully captured photo`() {
        val controller = InventoryController(household(), identity(), FakeInventoryGateway(inventory()))
        controller.beginAddItem()
        controller.resolveCameraPermission(granted = true)
        controller.photoCaptured(ItemPhoto("content://mystuff/captured.jpg"))

        controller.continueWithoutPhoto()

        assertEquals(ItemFormStage.Details, controller.state.itemFormStage)
        assertNull(controller.state.itemDraft?.photo)
    }

    @Test
    fun `photo picker selections are accepted in order and saved as multiple photos`() {
        val gateway = FakeInventoryGateway(inventory())
        val controller = InventoryController(household(), identity(), gateway)
        val firstSource = ItemPhoto("content://picker/first.jpg")
        val secondSource = ItemPhoto("content://picker/second.jpg")

        controller.beginAddItem()
        controller.resolveCameraPermission(granted = false)
        controller.photoPickerSelected(listOf(firstSource, secondSource))

        assertEquals(ItemFormStage.Crop, controller.state.itemFormStage)
        assertEquals(firstSource, controller.state.itemDraft?.photo)

        val firstAccepted = ItemPhoto("content://processed/first.webp")
        val secondAccepted = ItemPhoto("content://processed/second.webp")
        controller.usePhotoWithoutCropping(firstAccepted)

        assertEquals(ItemFormStage.Crop, controller.state.itemFormStage)
        assertEquals(listOf(firstAccepted), controller.state.itemDraft?.photos)
        assertEquals(secondSource, controller.state.itemDraft?.photo)

        controller.useCroppedPhoto(secondAccepted)

        assertEquals(ItemFormStage.Details, controller.state.itemFormStage)
        assertEquals(
            listOf(firstAccepted, secondAccepted),
            controller.state.itemDraft?.photos,
        )
        controller.changeItemName("Receipts")
        controller.saveItem()

        assertEquals(listOf(firstAccepted, secondAccepted), gateway.createdPhotos)
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
    fun `Member browses immediate Child Items and a deep parent Item Path`() {
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
            listOf("Garage", "Cabinet"),
            controller.state.itemPath.map(Item::name),
        )
    }

    @Test
    fun `saved Item returns to its Parent Item details`() {
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
        controller.closeItemForm()

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
            controller.closeItemForm()
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
    fun `Member can create an Item with a web URL and blank URLs stay absent`() {
        val gateway = FakeInventoryGateway(inventory())
        val controller = InventoryController(household(), identity(), gateway)
        controller.beginAddItem()
        controller.cameraUnavailable()
        controller.changeItemName("Drill")
        controller.changeItemWebUrl("  https://example.com/drill  ")

        controller.saveItem()

        assertEquals("https://example.com/drill", gateway.createdDetails?.webUrl)
        assertEquals("https://example.com/drill", controller.state.childItems.single().webUrl)

        controller.beginAddItem()
        controller.cameraUnavailable()
        controller.changeItemName("Saw")
        controller.saveItem()

        assertNull(gateway.createdDetails?.webUrl)
    }

    @Test
    fun `Item form rejects a web URL that a browser should not open`() {
        val gateway = FakeInventoryGateway(inventory())
        val controller = InventoryController(household(), identity(), gateway)
        controller.beginAddItem()
        controller.cameraUnavailable()
        controller.changeItemName("Drill")
        controller.changeItemWebUrl("javascript:alert(1)")

        controller.saveItem()

        assertEquals(
            "Enter a valid web URL beginning with http:// or https://.",
            controller.state.itemDraft?.webUrlError,
        )
        assertNull(gateway.createdDetails)
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
    fun `Tag comparison uses Unicode caseless matching`() {
        val controller = InventoryController(household(), identity(), FakeInventoryGateway(inventory()))
        controller.beginAddItem()
        controller.cameraUnavailable()

        controller.changeTagInput("ς")
        controller.addTag()
        controller.changeTagInput("σ")
        controller.addTag()

        assertEquals(listOf("ς"), controller.state.itemDraft?.tags)
        assertEquals("That Tag is already on this Item.", controller.state.itemDraft?.tagError)
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
            webUrl = "https://example.com/old-drill",
        )
        val gateway = FakeInventoryGateway(
            Inventory.from(household, listOf(household.rootItem, existing)),
        )
        val controller = InventoryController(household, identity(), gateway)
        controller.openItem(existing.id)
        controller.beginEditItem()
        assertEquals("Old description", controller.state.itemDraft?.description)
        assertEquals(listOf("Corded"), controller.state.itemDraft?.tags)
        assertEquals("https://example.com/old-drill", controller.state.itemDraft?.webUrl)

        controller.changeItemName("Hammer Drill")
        controller.changeItemDescription("New description")
        controller.changeItemWebUrl("https://example.com/hammer-drill")
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
        assertEquals("https://example.com/hammer-drill", controller.state.selectedItem.webUrl)
        assertEquals("Item saved.", controller.state.successMessage)
        assertEquals(2, gateway.updateAttempts)
    }

    @Test
    fun `Item form reports save progress then returns to details`() {
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

    @Test
    fun `Member submits a new photo Item for Description Generation`() {
        val work = RecordingDescriptionGenerationWork()
        val controller = InventoryController(
            household = household(),
            identity = identity(),
            gateway = FakeInventoryGateway(inventory()),
            rootChildItemCache = NoRootChildItemCache,
            descriptionGenerationWork = work,
            deviceLanguage = { "en-AU" },
        )
        val photo = ItemPhoto("content://new.webp", "content://new-thumb.webp")
        controller.beginAddItem()
        controller.resolveCameraPermission(granted = true)
        controller.photoCaptured(ItemPhoto("content://captured.jpg"))
        controller.useCroppedPhoto(photo)
        controller.changeItemName("  Drill  ")
        controller.changeItemDescription("Member facts")
        controller.changeItemWebUrl(" https://example.com/drill ")
        controller.changeTagInput("Power Tools")
        controller.addTag()

        assertTrue(controller.state.canGenerateDescription)

        controller.saveAndGenerateDescription()

        val request = work.requests.single()
        assertEquals(DescriptionGenerationSaveMode.Create, request.saveMode)
        assertEquals("created-1", request.item.id)
        assertEquals(household().id, request.item.parentItemId)
        assertEquals(listOf(photo), work.replacementPhotos)
        assertNull(controller.state.itemDraft)
        assertEquals("Drill", controller.state.selectedItem.name)
        assertEquals("Member facts", controller.state.selectedItem.description)
        assertEquals(listOf("Power Tools"), controller.state.selectedItem.tags)
        assertEquals("https://example.com/drill", controller.state.selectedItem.webUrl)
    }

    @Test
    fun `pending new Item stays overlaid until its background create is observed`() {
        val household = household()
        val gateway = FakeInventoryGateway(inventory())
        val work = RecordingDescriptionGenerationWork()
        val controller = InventoryController(
            household = household,
            identity = identity(),
            gateway = gateway,
            rootChildItemCache = NoRootChildItemCache,
            descriptionGenerationWork = work,
        )
        controller.beginAddItem()
        controller.resolveCameraPermission(granted = true)
        controller.photoCaptured(ItemPhoto("content://captured.jpg"))
        controller.useCroppedPhoto(ItemPhoto("content://new.webp", "content://new-thumb.webp"))
        controller.changeItemName("Drill")
        controller.saveAndGenerateDescription()
        val submitted = work.requests.single().item

        gateway.emit(Inventory.from(household, listOf(household.rootItem)))

        assertEquals(submitted, controller.state.inventory.item(submitted.id))
        assertEquals(submitted.id, controller.state.selectedItemId)
    }

    @Test
    fun `Member submits a complete existing-photo draft and sees it optimistically`() {
        val household = household()
        val existing = item(
            id = "drill",
            name = "Drill",
            parentItemId = household.id,
            photoUrl = "gs://mystuff/households/household-1/items/drill-revision.webp",
            photoThumbnailUrl = "gs://mystuff/households/household-1/items/drill-revision-thumb.webp",
            description = "Old description",
            tags = listOf("Corded"),
            webUrl = "https://example.com/old-drill",
        )
        val work = RecordingDescriptionGenerationWork()
        val controller = InventoryController(
            household = household,
            identity = identity(),
            gateway = FakeInventoryGateway(
                Inventory.from(household, listOf(household.rootItem, existing)),
            ),
            rootChildItemCache = NoRootChildItemCache,
            descriptionGenerationWork = work,
            deviceLanguage = { "en-AU" },
        )
        controller.openItem(existing.id)
        controller.beginEditItem()
        controller.changeItemName("  Hammer Drill  ")
        controller.changeItemDescription("Member facts")
        controller.changeItemWebUrl("  https://example.com/hammer-drill  ")
        controller.removeTag("Corded")
        controller.changeTagInput("Power Tools")
        controller.addTag()

        assertTrue(controller.state.canGenerateDescription)

        controller.saveAndGenerateDescription()

        assertEquals(
            DescriptionGenerationRequest(
                householdId = household.id,
                item = existing.copy(
                    name = "Hammer Drill",
                    description = "Member facts",
                    tags = listOf("Power Tools"),
                    webUrl = "https://example.com/hammer-drill",
                ),
                requestingMember = RequestingMemberAttribution("member-1", "Alex"),
                deviceLanguage = "en-AU",
            ),
            work.requests.single(),
        )
        assertNull(controller.state.itemDraft)
        assertFalse(controller.state.operationInProgress)
        assertEquals("Hammer Drill", controller.state.selectedItem.name)
        assertEquals("Member facts", controller.state.selectedItem.description)
        assertEquals(listOf("Power Tools"), controller.state.selectedItem.tags)
        assertEquals("https://example.com/hammer-drill", controller.state.selectedItem.webUrl)
        assertNull(controller.state.successMessage)
        assertNull(controller.state.errorMessage)
    }

    @Test
    fun `pending Description Generation stays overlaid across live Inventory refreshes`() {
        val household = household()
        val existing = item(
            id = "drill",
            name = "Drill",
            parentItemId = household.id,
            photoUrl = "gs://mystuff/drill.webp",
            description = "Old description",
        )
        val gateway = FakeInventoryGateway(
            Inventory.from(household, listOf(household.rootItem, existing)),
        )
        val work = RecordingDescriptionGenerationWork()
        val controller = InventoryController(
            household = household,
            identity = identity(),
            gateway = gateway,
            rootChildItemCache = NoRootChildItemCache,
            descriptionGenerationWork = work,
        )
        controller.openItem(existing.id)
        controller.beginEditItem()
        controller.changeItemName("Hammer Drill")
        controller.changeItemDescription("Member facts")
        controller.changeItemWebUrl(" https://example.com/hammer-drill ")
        controller.changeTagInput("Power Tools")
        controller.addTag()

        controller.saveAndGenerateDescription()
        gateway.emit(
            Inventory.from(
                household,
                listOf(
                    household.rootItem,
                    existing,
                    item("saw", "Saw", household.id),
                ),
            ),
        )

        assertEquals("Hammer Drill", controller.state.selectedItem.name)
        assertEquals("Member facts", controller.state.selectedItem.description)
        assertEquals(listOf("Power Tools"), controller.state.selectedItem.tags)
        assertEquals(
            "https://example.com/hammer-drill",
            controller.state.selectedItem.webUrl,
        )
        assertEquals("Saw", controller.state.inventory.item("saw").name)
        assertNull(controller.state.deferredError)
        assertNull(controller.state.successMessage)
    }

    @Test
    fun `successful Description Generation reconciles with observed Inventory silently`() {
        val household = household()
        val existing = item(
            id = "drill",
            name = "Drill",
            parentItemId = household.id,
            photoUrl = "gs://mystuff/drill.webp",
            description = "Old description",
        )
        val gateway = FakeInventoryGateway(
            Inventory.from(household, listOf(household.rootItem, existing)),
        )
        val work = RecordingDescriptionGenerationWork()
        val controller = InventoryController(
            household = household,
            identity = identity(),
            gateway = gateway,
            rootChildItemCache = NoRootChildItemCache,
            descriptionGenerationWork = work,
        )
        controller.openItem(existing.id)
        controller.beginEditItem()
        controller.changeItemName("Hammer Drill")
        controller.changeItemDescription("Member facts")
        controller.saveAndGenerateDescription()
        val submission = work.pending.single()

        gateway.emit(
            Inventory.from(
                household,
                listOf(
                    household.rootItem,
                    submission.request.item.copy(
                        description = "A blue hammer drill.",
                        tags = listOf("Concurrent edit"),
                    ),
                ),
            ),
        )

        assertEquals("Member facts", controller.state.selectedItem.description)

        work.complete(
            submission.id,
            DescriptionGenerationOutcome.Success,
        )

        assertEquals("A blue hammer drill.", controller.state.selectedItem.description)
        assertEquals(listOf("Concurrent edit"), controller.state.selectedItem.tags)
        assertNull(controller.state.deferredError)
        assertNull(controller.state.successMessage)
        assertEquals(listOf(submission.id), work.consumedOutcomes)
    }

    @Test
    fun `permanent Save failure rolls back optimistic Item and is consumed once`() {
        val household = household()
        val existing = item(
            id = "drill",
            name = "Drill",
            parentItemId = household.id,
            photoUrl = "gs://mystuff/drill.webp",
            description = "Old description",
        )
        val gateway = FakeInventoryGateway(
            Inventory.from(household, listOf(household.rootItem, existing)),
        )
        val work = RecordingDescriptionGenerationWork()
        val controller = InventoryController(
            household = household,
            identity = identity(),
            gateway = gateway,
            rootChildItemCache = NoRootChildItemCache,
            descriptionGenerationWork = work,
        )
        controller.openItem(existing.id)
        controller.beginEditItem()
        controller.changeItemName("Hammer Drill")
        controller.saveAndGenerateDescription()
        val submission = work.pending.single()

        work.complete(submission.id, DescriptionGenerationOutcome.PermanentSaveFailure)

        assertEquals("Drill", controller.state.selectedItem.name)
        assertEquals("Couldn't save the Item.", controller.state.deferredError?.message)

        controller.consumeDeferredError(requireNotNull(controller.state.deferredError).id)

        assertNull(controller.state.deferredError)
        assertEquals(listOf(submission.id), work.consumedOutcomes)
    }

    @Test
    fun `post-Save failures preserve the saved draft and use stage-specific messages`() {
        val scenarios = listOf(
            DescriptionGenerationOutcome.PermanentPhotoFailure to
                "Item saved, but couldn't upload its photo.",
            DescriptionGenerationOutcome.PermanentGenerationFailure to
                "Item saved, but couldn't generate its description.",
            DescriptionGenerationOutcome.PermanentGenerationFailureWithErrorType(
                "RESOURCE_EXHAUSTED",
            ) to "Item saved, but couldn't generate its description. " +
                "RESOURCE_EXHAUSTED.",
        )

        scenarios.forEach { (outcome, message) ->
            val household = household()
            val existing = item(
                id = "drill",
                name = "Drill",
                parentItemId = household.id,
                photoUrl = "gs://mystuff/drill.webp",
                description = "Old description",
            )
            val gateway = FakeInventoryGateway(
                Inventory.from(household, listOf(household.rootItem, existing)),
            )
            val work = RecordingDescriptionGenerationWork()
            val controller = InventoryController(
                household = household,
                identity = identity(),
                gateway = gateway,
                rootChildItemCache = NoRootChildItemCache,
                descriptionGenerationWork = work,
            )
            controller.openItem(existing.id)
            controller.beginEditItem()
            controller.changeItemName("Hammer Drill")
            controller.changeItemDescription("Member facts")
            controller.saveAndGenerateDescription()
            val submission = work.pending.single()
            gateway.emit(
                Inventory.from(
                    household,
                    listOf(household.rootItem, submission.request.item),
                ),
            )

            work.complete(submission.id, outcome)

            assertEquals("Hammer Drill", controller.state.selectedItem.name)
            assertEquals("Member facts", controller.state.selectedItem.description)
            assertEquals(message, controller.state.deferredError?.message)
            assertNull(controller.state.successMessage)
        }
    }

    @Test
    fun `failure completed without an active controller appears once on next Inventory lifecycle`() {
        val household = household()
        val existing = item(
            id = "drill",
            name = "Drill",
            parentItemId = household.id,
            photoUrl = "gs://mystuff/drill.webp",
        )
        val inventory = Inventory.from(household, listOf(household.rootItem, existing))
        val work = RecordingDescriptionGenerationWork()
        val submission = work.submit(descriptionGenerationRequestFor(existing))
        work.complete(submission.id, DescriptionGenerationOutcome.PermanentGenerationFailure)

        val firstController = InventoryController(
            household = household,
            identity = identity(),
            gateway = FakeInventoryGateway(inventory),
            rootChildItemCache = NoRootChildItemCache,
            descriptionGenerationWork = work,
        )

        assertEquals(
            "Item saved, but couldn't generate its description.",
            firstController.state.deferredError?.message,
        )
        val presentedMessages = mutableListOf<String>()
        runBlocking {
            presentDeferredInventoryError(
                error = requireNotNull(firstController.state.deferredError),
                showSnackbar = { message -> presentedMessages += message },
                consume = firstController::consumeDeferredError,
            )
        }
        assertEquals(
            listOf("Item saved, but couldn't generate its description."),
            presentedMessages,
        )
        firstController.close()

        val nextController = InventoryController(
            household = household,
            identity = identity(),
            gateway = FakeInventoryGateway(inventory),
            rootChildItemCache = NoRootChildItemCache,
            descriptionGenerationWork = work,
        )

        assertNull(nextController.state.deferredError)
    }

    @Test
    fun `success completed without an active controller releases its retained outcome`() {
        val household = household()
        val submitted = item(
            id = "drill",
            name = "Hammer Drill",
            parentItemId = household.id,
            photoUrl = "gs://mystuff/drill.webp",
            description = "Member facts",
        )
        val work = RecordingDescriptionGenerationWork()
        val id = work.submit(descriptionGenerationRequestFor(submitted)).id
        work.complete(
            id,
            DescriptionGenerationOutcome.Success,
        )

        InventoryController(
            household = household,
            identity = identity(),
            gateway = FakeInventoryGateway(
                Inventory.from(
                    household,
                    listOf(
                        household.rootItem,
                        submitted.copy(description = "A blue hammer drill."),
                    ),
                ),
            ),
            rootChildItemCache = NoRootChildItemCache,
            descriptionGenerationWork = work,
        )

        assertEquals(listOf(id), work.consumedOutcomes)
    }

    @Test
    fun `replacement Item Photo enables Description Generation until it is removed`() {
        val household = household()
        val itemWithoutPhoto = item("drill", "Drill", household.id)
        val controller = InventoryController(
            household = household,
            identity = identity(),
            gateway = FakeInventoryGateway(
                Inventory.from(household, listOf(household.rootItem, itemWithoutPhoto)),
            ),
        )

        controller.beginAddItem()
        assertFalse(controller.state.canGenerateDescription)
        controller.closeItemForm()

        controller.openItem(itemWithoutPhoto.id)
        controller.beginEditItem()
        assertFalse(controller.state.canGenerateDescription)

        controller.beginReplaceItemPhoto()
        controller.resolveCameraPermission(granted = true)
        controller.photoCaptured(ItemPhoto("content://captured.jpg"))
        controller.useCroppedPhoto(ItemPhoto("content://new.webp", "content://new-thumb.webp"))
        assertTrue(controller.state.canGenerateDescription)

        controller.removeItemPhoto()

        assertFalse(controller.state.canGenerateDescription)
    }

    @Test
    fun `replacement photo submission closes Edit and presents one captured immutable revision`() {
        val household = household()
        val existing = item(
            id = "drill",
            name = "Drill",
            parentItemId = household.id,
            photoUrl = "gs://mystuff/households/household-1/items/drill-old.webp",
            photoThumbnailUrl =
                "gs://mystuff/households/household-1/items/drill-old-thumb.webp",
            description = "Old description",
        )
        val work = RecordingDescriptionGenerationWork()
        val controller = InventoryController(
            household = household,
            identity = identity(),
            gateway = FakeInventoryGateway(
                Inventory.from(household, listOf(household.rootItem, existing)),
            ),
            rootChildItemCache = NoRootChildItemCache,
            descriptionGenerationWork = work,
            deviceLanguage = { "en-AU" },
        )
        controller.openItem(existing.id)
        controller.beginEditItem()
        controller.changeItemName("Hammer Drill")
        controller.changeItemDescription("Member facts")
        controller.beginReplaceItemPhoto()
        controller.resolveCameraPermission(granted = true)
        controller.photoCaptured(ItemPhoto("content://captured.jpg"))
        controller.useCroppedPhoto(
            ItemPhoto("content://new-full.webp", "content://new-thumb.webp"),
        )

        controller.saveAndGenerateDescription()

        assertNull(controller.state.itemDraft)
        assertEquals("Hammer Drill", controller.state.selectedItem.name)
        assertEquals("Member facts", controller.state.selectedItem.description)
        assertEquals(
            "gs://mystuff/households/household-1/items/drill-$DESCRIPTION_REVISION.webp",
            controller.state.selectedItem.photoUrl,
        )
        assertEquals(
            "gs://mystuff/households/household-1/items/drill-$DESCRIPTION_REVISION-thumb.webp",
            controller.state.selectedItem.photoThumbnailUrl,
        )
        assertEquals(
            ItemPhoto("content://new-full.webp", "content://new-thumb.webp"),
            work.replacementPhotos.single(),
        )
        assertEquals(controller.state.selectedItem, work.requests.single().item)
    }

    @Test
    fun `Member can add several attachments from Edit without replacing the Item Photo`() {
        val household = household()
        val existing = item(
            id = "drill",
            name = "Drill",
            parentItemId = household.id,
            photoUrl = "gs://mystuff/drill.webp",
            photoThumbnailUrl = "gs://mystuff/drill-thumb.webp",
        )
        val gateway = FakeInventoryGateway(
            Inventory.from(household, listOf(household.rootItem, existing)),
        )
        val controller = InventoryController(household, identity(), gateway)

        controller.openItem(existing.id)
        controller.beginEditItem()
        controller.beginAddItemAttachments()
        controller.cameraUnavailable()
        controller.photoPickerSelected(
            listOf(
                ItemPhoto("content://receipt.webp"),
                ItemPhoto("content://manual.webp"),
            ),
        )
        controller.usePhotoWithoutCropping(ItemPhoto("content://receipt-optimized.webp"))
        controller.usePhotoWithoutCropping(ItemPhoto("content://manual-optimized.webp"))
        controller.saveItem()

        assertEquals(
            listOf(
                ItemPhoto("content://receipt-optimized.webp"),
                ItemPhoto("content://manual-optimized.webp"),
            ),
            gateway.updatedAdditionalPhotos,
        )
        assertEquals(ItemPhotoUpdate.Unchanged, gateway.updatedPhotoUpdate)
        assertEquals("gs://mystuff/drill.webp", controller.state.selectedItem.photoUrl)
        assertNull(controller.state.itemDraft)
    }

    @Test
    fun `first added attachment can supply a missing Item Photo`() {
        val household = household()
        val existing = item("drill", "Drill", household.id)
        val gateway = FakeInventoryGateway(
            Inventory.from(household, listOf(household.rootItem, existing)),
        )
        val controller = InventoryController(household, identity(), gateway)

        controller.openItem(existing.id)
        controller.beginEditItem()
        controller.beginAddItemAttachments()
        controller.resolveCameraPermission(granted = true)
        controller.photoCaptured(ItemPhoto("content://receipt.jpg"))
        controller.usePhotoWithoutCropping(ItemPhoto("content://receipt-optimized.webp"))
        controller.saveItem()

        assertEquals(
            ItemPhoto("content://receipt-optimized.webp"),
            gateway.updatedAdditionalPhotos.single(),
        )
        assertEquals(ItemPhotoUpdate.Unchanged, gateway.updatedPhotoUpdate)
        assertEquals(
            "gs://mystuff/households/household-1/items/drill.webp",
            controller.state.selectedItem.photoUrl,
        )
    }

    @Test
    fun `invalid Description Generation draft stays open and submits no work`() {
        val household = household()
        val existing = item(
            id = "drill",
            name = "Drill",
            parentItemId = household.id,
            photoUrl = "gs://mystuff/households/household-1/items/drill.webp",
        )
        val work = RecordingDescriptionGenerationWork()
        val controller = InventoryController(
            household = household,
            identity = identity(),
            gateway = FakeInventoryGateway(
                Inventory.from(household, listOf(household.rootItem, existing)),
            ),
            rootChildItemCache = NoRootChildItemCache,
            descriptionGenerationWork = work,
        )
        controller.openItem(existing.id)
        controller.beginEditItem()
        controller.changeItemDescription("🏠".repeat(2_001))

        controller.saveAndGenerateDescription()

        assertEquals(
            "Descriptions can contain at most 2,000 characters.",
            controller.state.itemDraft?.descriptionError,
        )
        assertTrue(work.requests.isEmpty())
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
    private val emitInitialInventory: Boolean = true,
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
    var createdPhotos: List<ItemPhoto> = emptyList()
        private set
    var createdDetails: ItemDetails? = null
        private set
    var nextUpdateFailure: Throwable? = null
    var updateAttempts: Int = 0
        private set
    var updatedPhotoUpdate: ItemPhotoUpdate? = null
        private set
    var updatedAdditionalPhotos: List<ItemPhoto> = emptyList()
        private set
    var deferCreates: Boolean = false
    private var pendingCreate: (() -> Unit)? = null

    override fun observe(
        household: Household,
        onResult: (Result<Inventory>) -> Unit,
    ): InventorySubscription {
        observer = onResult
        if (emitInitialInventory) onResult(Result.success(inventory))
        return InventorySubscription { observer = null }
    }

    override fun newItemId(householdId: String): String = "created-${nextId++}"

    override fun createItem(
        householdId: String,
        parentItemId: String,
        creator: AuthenticatedIdentity,
        details: ItemDetails,
        photo: ItemPhoto?,
        onResult: (Result<Item>) -> Unit,
    ) {
        createItem(
            householdId = householdId,
            parentItemId = parentItemId,
            creator = creator,
            details = details,
            photos = listOfNotNull(photo),
            onResult = onResult,
        )
    }

    override fun createItem(
        householdId: String,
        parentItemId: String,
        creator: AuthenticatedIdentity,
        details: ItemDetails,
        photos: List<ItemPhoto>,
        onResult: (Result<Item>) -> Unit,
    ) {
        createdParentItemId = parentItemId
        createdName = details.name
        createdDetails = details
        createdPhotos = photos
        createdPhoto = photos.firstOrNull()
        val created = item(
            id = newItemId(householdId),
            name = details.name,
            parentItemId = parentItemId,
            photoUrl = photos.firstOrNull()?.let {
                "gs://mystuff/households/household-1/items/item-1.webp"
            },
            photoThumbnailUrl = photos.firstOrNull()?.let {
                "gs://mystuff/households/household-1/items/item-1-thumb.webp"
            },
            description = details.description,
            tags = details.tags,
            webUrl = details.webUrl,
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

    fun emit(inventory: Inventory) {
        this.inventory = inventory
        observer?.invoke(Result.success(inventory))
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
            webUrl = details.webUrl,
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

    override fun updateItemWithAttachments(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        details: ItemDetails,
        photoUpdate: ItemPhotoUpdate,
        additionalPhotos: List<ItemPhoto>,
        onResult: (Result<Item>) -> Unit,
    ) {
        updatedPhotoUpdate = photoUpdate
        updatedAdditionalPhotos = additionalPhotos
        updateItem(
            householdId = householdId,
            item = item,
            updater = updater,
            details = details,
            photoUpdate = photoUpdate,
        ) { result ->
            val saved = result.getOrNull()
            if (saved != null && saved.photoUrl == null && additionalPhotos.isNotEmpty()) {
                val projected = saved.copy(
                    photoUrl = "gs://mystuff/households/$householdId/items/${item.id}.webp",
                    photoThumbnailUrl =
                        "gs://mystuff/households/$householdId/items/${item.id}-thumb.webp",
                )
                inventory = inventory.withItem(projected)
                onResult(Result.success(projected))
            } else {
                onResult(result)
            }
        }
    }
}

private class RecordingSearchGateway : SearchGateway {
    val queries = mutableListOf<String>()
    private var onResult: ((Result<List<String>>) -> Unit)? = null

    override fun search(
        query: String,
        onResult: (Result<List<String>>) -> Unit,
    ): SearchSubscription {
        queries += query
        this.onResult = onResult
        return SearchSubscription { this.onResult = null }
    }

    fun complete(itemIds: List<String>) {
        requireNotNull(onResult).invoke(Result.success(itemIds))
    }

    fun fail() {
        requireNotNull(onResult).invoke(Result.failure(IllegalStateException("offline")))
    }
}

private class UncooperativeSearchGateway : SearchGateway {
    private val callbacks = mutableMapOf<String, (Result<List<String>>) -> Unit>()

    override fun search(
        query: String,
        onResult: (Result<List<String>>) -> Unit,
    ): SearchSubscription {
        callbacks[query] = onResult
        return SearchSubscription {}
    }

    fun complete(query: String, itemIds: List<String>) {
        requireNotNull(callbacks[query]).invoke(Result.success(itemIds))
    }
}

private class RecordingSearchDebouncer : SearchDebouncer {
    var delayMillis: Long? = null
        private set
    private var pending: (() -> Unit)? = null

    override fun schedule(delayMillis: Long, action: () -> Unit): SearchSubscription {
        this.delayMillis = delayMillis
        pending = action
        return SearchSubscription { pending = null }
    }

    override fun close() {
        pending = null
    }

    fun runPending() {
        requireNotNull(pending).also { pending = null }.invoke()
    }
}

private class RecordingRootChildItemCache(
    private val loadedItems: List<Item>? = null,
) : RootChildItemCache {
    var storedItems: List<Item>? = null
        private set

    override fun load(householdId: String): List<Item>? = loadedItems

    override fun store(householdId: String, items: List<Item>) {
        storedItems = items
    }
}

private class RecordingDescriptionGenerationWork : InventoryDescriptionGenerationWork {
    val requests = mutableListOf<DescriptionGenerationRequest>()
    val replacementPhotos = mutableListOf<ItemPhoto>()
    val pending = mutableListOf<PendingDescriptionGeneration>()
    val outcomes = mutableListOf<CompletedDescriptionGeneration>()
    val consumedOutcomes = mutableListOf<String>()
    private var observer: ((DescriptionGenerationWorkState) -> Unit)? = null

    override fun submit(
        request: DescriptionGenerationRequest,
        replacementPhoto: ItemPhoto?,
    ): PendingDescriptionGeneration {
        val capturedRequest = if (replacementPhoto == null) {
            request
        } else {
            replacementPhotos += replacementPhoto
            request.copy(
                item = request.item.copy(
                    photoUrl = "gs://mystuff/households/${request.householdId}/items/" +
                        "${request.item.id}-$DESCRIPTION_REVISION.webp",
                    photoThumbnailUrl = "gs://mystuff/households/${request.householdId}/items/" +
                        "${request.item.id}-$DESCRIPTION_REVISION-thumb.webp",
                ),
            )
        }
        requests += capturedRequest
        val id = "request-${requests.size}"
        val submission = PendingDescriptionGeneration(id, capturedRequest)
        pending += submission
        emit()
        return submission
    }

    override fun observe(
        onChanged: (DescriptionGenerationWorkState) -> Unit,
    ): InventorySubscription {
        observer = onChanged
        emit()
        return InventorySubscription { observer = null }
    }

    override fun consumeOutcome(id: String) {
        outcomes.removeAll { it.id == id }
        consumedOutcomes += id
        emit()
    }

    fun complete(
        id: String,
        outcome: DescriptionGenerationOutcome,
    ) {
        val request = requireNotNull(pending.singleOrNull { it.id == id }).request
        pending.removeAll { it.id == id }
        outcomes += CompletedDescriptionGeneration(id, request.householdId, outcome)
        emit()
    }

    private fun emit() {
        observer?.invoke(DescriptionGenerationWorkState(pending.toList(), outcomes.toList()))
    }
}

private fun descriptionGenerationRequestFor(item: Item) = DescriptionGenerationRequest(
    householdId = "household-1",
    item = item,
    requestingMember = RequestingMemberAttribution("member-1", "Alex"),
    deviceLanguage = "en-AU",
)

private const val DESCRIPTION_REVISION = "11111111-1111-1111-1111-111111111111"

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
    webUrl: String? = null,
) = Item(
    id = id,
    name = name,
    parentItemId = parentItemId,
    photoUrl = photoUrl,
    description = description,
    tags = tags,
    photoThumbnailUrl = photoThumbnailUrl,
    webUrl = webUrl,
)
