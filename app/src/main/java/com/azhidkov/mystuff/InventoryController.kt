package com.azhidkov.mystuff

fun interface InventorySubscription {
    fun cancel()
}

interface InventoryGateway {
    fun observe(
        household: Household,
        onResult: (Result<Inventory>) -> Unit,
    ): InventorySubscription

    fun createItem(
        householdId: String,
        parentItemId: String,
        creator: AuthenticatedIdentity,
        name: String,
        photo: ItemPhoto?,
        onResult: (Result<Item>) -> Unit,
    )
}

interface InventoryActions {
    fun openItem(itemId: String)
    fun openParentItem()
    fun beginAddItem()
    fun cameraUnavailable()
    fun resolveCameraPermission(granted: Boolean)
    fun photoCaptureFailed()
    fun photoCaptured(photo: ItemPhoto)
    fun retakePhoto()
    fun useCroppedPhoto(photo: ItemPhoto)
    fun continueWithoutPhoto()
    fun cancelAddItem()
    fun changeItemName(name: String)
    fun saveItem()
}

enum class ItemCreationStage {
    CameraPermission,
    Camera,
    Crop,
    Details,
}

data class ItemPhoto(
    val uri: String,
)

data class ItemDraft(
    val name: String = "",
    val parentItemId: String,
    val stage: ItemCreationStage = ItemCreationStage.CameraPermission,
    val photo: ItemPhoto? = null,
    val nameError: String? = null,
)

data class InventoryUiState(
    val inventory: Inventory,
    val selectedItemId: String,
    val itemDraft: ItemDraft? = null,
    val loading: Boolean = false,
    val operationInProgress: Boolean = false,
    val errorMessage: String? = null,
) {
    val itemCreationStage: ItemCreationStage?
        get() = itemDraft?.stage
    val selectedItem: Item
        get() = inventory.item(selectedItemId)
    val childItems: List<Item>
        get() = inventory.childrenOf(selectedItemId)
    val itemPath: List<Item>
        get() = inventory.pathTo(selectedItemId)
}

class InventoryController(
    private val household: Household,
    private val identity: AuthenticatedIdentity,
    private val gateway: InventoryGateway,
) : InventoryActions, AutoCloseable {
    var state = InventoryUiState(
        inventory = Inventory.from(household, listOf(household.rootItem)),
        selectedItemId = household.rootItem.id,
        loading = true,
    )
        private set

    var onStateChanged: (InventoryUiState) -> Unit = {}

    private val subscription = gateway.observe(household) { result ->
        result.onSuccess { inventory ->
            val selectedItemId = state.selectedItemId.takeIf(inventory::contains)
                ?: inventory.rootItemId
            updateState(
                state.copy(
                    inventory = inventory,
                    selectedItemId = selectedItemId,
                    loading = false,
                    errorMessage = null,
                ),
            )
        }.onFailure { failure ->
            updateState(
                state.copy(
                    loading = false,
                    errorMessage = failure.message ?: "Couldn't load your Inventory.",
                ),
            )
        }
    }

    override fun openItem(itemId: String) {
        if (!state.inventory.contains(itemId) || state.itemDraft != null) return
        updateState(state.copy(selectedItemId = itemId, errorMessage = null))
    }

    override fun openParentItem() {
        val parentItemId = state.selectedItem.parentItemId ?: return
        openItem(parentItemId)
    }

    override fun beginAddItem() {
        if (state.itemDraft != null || state.operationInProgress) return
        updateState(state.copy(itemDraft = ItemDraft(parentItemId = state.selectedItemId)))
    }

    override fun cameraUnavailable() {
        transitionItemDraft(ItemCreationStage.CameraPermission) {
            it.copy(stage = ItemCreationStage.Details)
        }
    }

    override fun resolveCameraPermission(granted: Boolean) {
        transitionItemDraft(ItemCreationStage.CameraPermission) {
            it.copy(stage = if (granted) ItemCreationStage.Camera else ItemCreationStage.Details)
        }
    }

    override fun photoCaptureFailed() {
        transitionItemDraft(ItemCreationStage.Camera) {
            it.copy(stage = ItemCreationStage.Details)
        }
    }

    override fun photoCaptured(photo: ItemPhoto) {
        transitionItemDraft(ItemCreationStage.Camera) {
            it.copy(stage = ItemCreationStage.Crop, photo = photo)
        }
    }

    override fun retakePhoto() {
        transitionItemDraft(ItemCreationStage.Crop) {
            it.copy(stage = ItemCreationStage.Camera, photo = null)
        }
    }

    override fun useCroppedPhoto(photo: ItemPhoto) {
        transitionItemDraft(ItemCreationStage.Crop) {
            it.copy(stage = ItemCreationStage.Details, photo = photo)
        }
    }

    override fun continueWithoutPhoto() {
        transitionItemDraft(ItemCreationStage.Crop) {
            it.copy(stage = ItemCreationStage.Details, photo = null)
        }
    }

    override fun cancelAddItem() {
        if (state.operationInProgress) return
        updateState(state.copy(itemDraft = null, errorMessage = null))
    }

    override fun changeItemName(name: String) {
        val draft = state.itemDraft ?: return
        updateState(state.copy(itemDraft = draft.copy(name = name, nameError = null)))
    }

    override fun saveItem() {
        val draft = state.itemDraft ?: return
        if (state.operationInProgress) return
        val name = draft.name.trim(Char::isWhitespace)
        val nameError = when {
            name.isEmpty() -> "Enter an Item name."
            name.codePointCount(0, name.length) > MAX_ITEM_NAME_LENGTH ->
                "Item names can contain at most 100 characters."
            else -> null
        }
        if (nameError != null) {
            updateState(state.copy(itemDraft = draft.copy(nameError = nameError)))
            return
        }
        if (!state.inventory.contains(draft.parentItemId)) {
            updateState(
                state.copy(errorMessage = "The Parent Item is no longer in this Household."),
            )
            return
        }

        updateState(state.copy(operationInProgress = true, errorMessage = null))
        gateway.createItem(
            householdId = household.id,
            parentItemId = draft.parentItemId,
            creator = identity,
            name = name,
            photo = draft.photo,
        ) { result ->
            result.onSuccess { created ->
                updateState(
                    state.copy(
                        inventory = state.inventory.withItem(created),
                        selectedItemId = created.parentItemId
                            ?: throw InvalidInventoryException(),
                        itemDraft = null,
                        operationInProgress = false,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operationInProgress = false,
                        errorMessage = failure.message ?: "Couldn't add the Item.",
                    ),
                )
            }
        }
    }

    override fun close() {
        subscription.cancel()
        onStateChanged = {}
    }

    private fun updateState(newState: InventoryUiState) {
        state = newState
        onStateChanged(newState)
    }

    private fun transitionItemDraft(
        expectedStage: ItemCreationStage,
        transition: (ItemDraft) -> ItemDraft,
    ) {
        val draft = state.itemDraft ?: return
        if (draft.stage != expectedStage) return
        updateState(state.copy(itemDraft = transition(draft)))
    }

    private companion object {
        const val MAX_ITEM_NAME_LENGTH = 100
    }
}
