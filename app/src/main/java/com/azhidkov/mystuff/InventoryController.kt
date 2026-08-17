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
        details: ItemDetails,
        photo: ItemPhoto?,
        onResult: (Result<Item>) -> Unit,
    )

    fun updateItem(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        details: ItemDetails,
        photoUpdate: ItemPhotoUpdate,
        onResult: (Result<Item>) -> Unit,
    )
}

data class ItemDetails(
    val name: String,
    val description: String?,
    val tags: List<String>,
)

internal object ItemFormPolicy {
    const val MAX_ITEM_NAME_LENGTH = 100
    const val MAX_DESCRIPTION_LENGTH = 2_000
    const val MAX_TAG_COUNT = 20
    const val MAX_TAG_LENGTH = 40
}

sealed interface ItemPhotoUpdate {
    data object Unchanged : ItemPhotoUpdate
    data object Removed : ItemPhotoUpdate
    data class Replaced(val photo: ItemPhoto) : ItemPhotoUpdate
}

interface InventoryActions {
    fun openItem(itemId: String)
    fun openParentItem()
    fun beginAddItem()
    fun beginEditItem()
    fun beginReplaceItemPhoto()
    fun removeItemPhoto()
    fun cameraUnavailable()
    fun resolveCameraPermission(granted: Boolean)
    fun photoCaptureFailed()
    fun photoCaptured(photo: ItemPhoto)
    fun retakePhoto()
    fun useCroppedPhoto(photo: ItemPhoto)
    fun continueWithoutPhoto()
    fun closeItemForm()
    fun changeItemName(name: String)
    fun changeItemDescription(description: String)
    fun changeTagInput(tag: String)
    fun addTag()
    fun addSuggestedTag(tag: String)
    fun removeTag(tag: String)
    fun saveItem()
}

enum class ItemFormStage {
    CameraPermission,
    Camera,
    Crop,
    Details,
}

data class ItemFormState(
    val name: String = "",
    val parentItemId: String,
    val stage: ItemFormStage = ItemFormStage.CameraPermission,
    val photo: ItemPhoto? = null,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val tagInput: String = "",
    val nameError: String? = null,
    val descriptionError: String? = null,
    val tagError: String? = null,
    val editingItemId: String? = null,
    val photoRemoved: Boolean = false,
    val saveSucceeded: Boolean = false,
)

data class InventoryUiState(
    val inventory: Inventory,
    val selectedItemId: String,
    val itemDraft: ItemFormState? = null,
    val loading: Boolean = false,
    val operationInProgress: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
) {
    val itemFormStage: ItemFormStage?
        get() = itemDraft?.stage
    val selectedItem: Item
        get() = inventory.item(selectedItemId)
    val childItems: List<Item>
        get() = inventory.childrenOf(selectedItemId)
    val itemPath: List<Item>
        get() = inventory.pathTo(selectedItemId)
    val tagSuggestions: List<String>
        get() {
            val draft = itemDraft ?: return emptyList()
            val selectedTags = draft.tags.mapTo(mutableSetOf(), String::tagKey)
            val query = draft.tagInput.trimUnicodeWhitespace().tagKey().value
            return inventory.allItems
                .flatMap(Item::tags)
                .distinctBy(String::tagKey)
                .filter { suggestion ->
                    suggestion.tagKey() !in selectedTags &&
                        (query.isEmpty() || query in suggestion.tagKey().value)
                }
        }
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
        updateState(
            state.copy(selectedItemId = itemId, errorMessage = null, successMessage = null),
        )
    }

    override fun openParentItem() {
        val parentItemId = state.selectedItem.parentItemId ?: return
        openItem(parentItemId)
    }

    override fun beginAddItem() {
        if (state.itemDraft != null || state.operationInProgress) return
        updateState(
            state.copy(
                itemDraft = ItemFormState(parentItemId = state.selectedItemId),
                errorMessage = null,
                successMessage = null,
            ),
        )
    }

    override fun beginEditItem() {
        if (
            state.itemDraft != null ||
            state.operationInProgress ||
            state.selectedItemId == state.inventory.rootItemId
        ) {
            return
        }
        val item = state.selectedItem
        updateState(
            state.copy(
                itemDraft = ItemFormState(
                    name = item.name,
                    parentItemId = item.parentItemId ?: return,
                    stage = ItemFormStage.Details,
                    description = item.description.orEmpty(),
                    tags = item.tags,
                    editingItemId = item.id,
                ),
                errorMessage = null,
                successMessage = null,
            ),
        )
    }

    override fun beginReplaceItemPhoto() {
        val draft = state.itemDraft ?: return
        if (
            draft.stage != ItemFormStage.Details ||
            state.operationInProgress ||
            draft.saveSucceeded
        ) {
            return
        }
        updateState(
            state.copy(
                itemDraft = draft.copy(
                    stage = ItemFormStage.CameraPermission,
                    photo = null,
                ),
                errorMessage = null,
            ),
        )
    }

    override fun removeItemPhoto() {
        val draft = state.itemDraft ?: return
        if (
            draft.stage != ItemFormStage.Details ||
            state.operationInProgress ||
            draft.saveSucceeded
        ) {
            return
        }
        updateState(
            state.copy(
                itemDraft = draft.copy(photo = null, photoRemoved = true),
                errorMessage = null,
            ),
        )
    }

    override fun cameraUnavailable() {
        transitionItemFormState(ItemFormStage.CameraPermission) {
            it.copy(stage = ItemFormStage.Details)
        }
    }

    override fun resolveCameraPermission(granted: Boolean) {
        transitionItemFormState(ItemFormStage.CameraPermission) {
            it.copy(stage = if (granted) ItemFormStage.Camera else ItemFormStage.Details)
        }
    }

    override fun photoCaptureFailed() {
        transitionItemFormState(ItemFormStage.Camera) {
            it.copy(stage = ItemFormStage.Details)
        }
    }

    override fun photoCaptured(photo: ItemPhoto) {
        transitionItemFormState(ItemFormStage.Camera) {
            it.copy(stage = ItemFormStage.Crop, photo = photo)
        }
    }

    override fun retakePhoto() {
        transitionItemFormState(ItemFormStage.Crop) {
            it.copy(stage = ItemFormStage.Camera, photo = null)
        }
    }

    override fun useCroppedPhoto(photo: ItemPhoto) {
        transitionItemFormState(ItemFormStage.Crop) {
            it.copy(stage = ItemFormStage.Details, photo = photo, photoRemoved = false)
        }
    }

    override fun continueWithoutPhoto() {
        transitionItemFormState(ItemFormStage.Crop) {
            it.copy(
                stage = ItemFormStage.Details,
                photo = null,
                photoRemoved = it.editingItemId != null,
            )
        }
    }

    override fun closeItemForm() {
        if (state.operationInProgress) return
        updateState(state.copy(itemDraft = null, errorMessage = null))
    }

    override fun changeItemName(name: String) {
        val draft = state.itemDraft ?: return
        if (draft.saveSucceeded) return
        updateState(state.copy(itemDraft = draft.copy(name = name, nameError = null)))
    }

    override fun changeItemDescription(description: String) {
        val draft = state.itemDraft ?: return
        if (draft.saveSucceeded) return
        updateState(
            state.copy(itemDraft = draft.copy(description = description, descriptionError = null)),
        )
    }

    override fun changeTagInput(tag: String) {
        val draft = state.itemDraft ?: return
        if (draft.saveSucceeded) return
        updateState(state.copy(itemDraft = draft.copy(tagInput = tag, tagError = null)))
    }

    override fun addTag() {
        val draft = state.itemDraft ?: return
        if (draft.saveSucceeded) return
        val tag = draft.tagInput.trimUnicodeWhitespace()
        val tagError = when {
            tag.isEmpty() -> "Enter a Tag."
            tag.codePointCount(0, tag.length) > ItemFormPolicy.MAX_TAG_LENGTH ->
                "Tags can contain at most ${ItemFormPolicy.MAX_TAG_LENGTH} characters."
            draft.tags.size >= ItemFormPolicy.MAX_TAG_COUNT ->
                "An Item can have at most ${ItemFormPolicy.MAX_TAG_COUNT} Tags."
            draft.tags.any { it.tagKey() == tag.tagKey() } ->
                "That Tag is already on this Item."
            else -> null
        }
        updateState(
            state.copy(
                itemDraft = if (tagError == null) {
                    draft.copy(tags = draft.tags + tag, tagInput = "", tagError = null)
                } else {
                    draft.copy(tagError = tagError)
                },
            ),
        )
    }

    override fun addSuggestedTag(tag: String) {
        changeTagInput(tag)
        addTag()
    }

    override fun removeTag(tag: String) {
        val draft = state.itemDraft ?: return
        if (draft.saveSucceeded) return
        updateState(
            state.copy(
                itemDraft = draft.copy(
                    tags = draft.tags.filterNot { it.tagKey() == tag.tagKey() },
                    tagError = null,
                ),
            ),
        )
    }

    override fun saveItem() {
        val draft = state.itemDraft ?: return
        if (state.operationInProgress || draft.saveSucceeded) return
        val name = draft.name.trimUnicodeWhitespace()
        val nameError = when {
            name.isEmpty() -> "Enter an Item name."
            name.codePointCount(0, name.length) > ItemFormPolicy.MAX_ITEM_NAME_LENGTH ->
                "Item names can contain at most " +
                    "${ItemFormPolicy.MAX_ITEM_NAME_LENGTH} characters."
            else -> null
        }
        if (nameError != null) {
            updateState(state.copy(itemDraft = draft.copy(nameError = nameError)))
            return
        }
        val descriptionError = if (
            draft.description.codePointCount(0, draft.description.length) >
                ItemFormPolicy.MAX_DESCRIPTION_LENGTH
        ) {
            "Descriptions can contain at most " +
                "${java.lang.String.format(
                    java.util.Locale.ROOT,
                    "%,d",
                    ItemFormPolicy.MAX_DESCRIPTION_LENGTH,
                )} characters."
        } else {
            null
        }
        if (descriptionError != null) {
            updateState(state.copy(itemDraft = draft.copy(descriptionError = descriptionError)))
            return
        }
        if (!state.inventory.contains(draft.parentItemId)) {
            updateState(
                state.copy(errorMessage = "The Parent Item is no longer in this Household."),
            )
            return
        }

        updateState(state.copy(operationInProgress = true, errorMessage = null))
        val details = ItemDetails(
            name = name,
            description = draft.description.takeIf(String::isNotEmpty),
            tags = draft.tags,
        )
        val onResult: (Result<Item>) -> Unit = { result ->
            result.onSuccess { savedItem ->
                updateState(
                    state.copy(
                        inventory = state.inventory.withItem(savedItem),
                        selectedItemId = if (draft.editingItemId == null) {
                            savedItem.parentItemId ?: throw InvalidInventoryException()
                        } else {
                            savedItem.id
                        },
                        itemDraft = draft.copy(
                            name = details.name,
                            description = details.description.orEmpty(),
                            saveSucceeded = true,
                        ),
                        operationInProgress = false,
                        errorMessage = null,
                        successMessage = "Item saved.",
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operationInProgress = false,
                        errorMessage = failure.message ?: "Couldn't save the Item.",
                        successMessage = null,
                    ),
                )
            }
        }
        val editingItemId = draft.editingItemId
        if (editingItemId == null) {
            gateway.createItem(
                householdId = household.id,
                parentItemId = draft.parentItemId,
                creator = identity,
                details = details,
                photo = draft.photo,
                onResult = onResult,
            )
        } else {
            val item = state.inventory.item(editingItemId)
            val photoUpdate = when {
                draft.photo != null -> ItemPhotoUpdate.Replaced(draft.photo)
                draft.photoRemoved -> ItemPhotoUpdate.Removed
                else -> ItemPhotoUpdate.Unchanged
            }
            gateway.updateItem(
                householdId = household.id,
                item = item,
                updater = identity,
                details = details,
                photoUpdate = photoUpdate,
                onResult = onResult,
            )
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

    private fun transitionItemFormState(
        expectedStage: ItemFormStage,
        transition: (ItemFormState) -> ItemFormState,
    ) {
        val draft = state.itemDraft ?: return
        if (draft.stage != expectedStage) return
        updateState(state.copy(itemDraft = transition(draft)))
    }

}

private fun String.trimUnicodeWhitespace(): String = trim { character ->
    character.isWhitespace() || Character.isSpaceChar(character)
}

@JvmInline
internal value class TagKey(val value: String)

internal fun String.tagKey(): TagKey = TagKey(
    java.text.Normalizer
        .normalize(
            uppercase(java.util.Locale.ROOT).lowercase(java.util.Locale.ROOT),
            java.text.Normalizer.Form.NFD,
        )
        .replace("\\p{M}+".toRegex(), ""),
)

internal fun ItemDetails.tagKeys(): List<String> = tags.map { it.tagKey().value }
