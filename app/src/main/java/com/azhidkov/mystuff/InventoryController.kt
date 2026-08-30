package com.azhidkov.mystuff

import java.time.Instant

fun interface InventorySubscription {
    fun cancel()
}

interface InventoryGateway {
    fun observe(
        household: Household,
        onResult: (Result<Inventory>) -> Unit,
    ): InventorySubscription

    fun newItemId(householdId: String): String

    fun createItem(
        householdId: String,
        parentItemId: String,
        creator: AuthenticatedIdentity,
        details: ItemDetails,
        photos: List<ItemPhoto>,
        onResult: (Result<Item>) -> Unit,
    )

    /**
     * Saves an Item and applies its Item Attachment changes in one gateway
     * operation. New files are always published as Item Attachments; the
     * projection is updated only from attachment metadata.
     */
    fun updateItemWithAttachments(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        details: ItemDetails,
        additionalPhotos: List<ItemPhoto>,
        existingAttachments: List<ItemAttachment>,
        attachmentToDelete: ItemAttachment? = null,
        onResult: (Result<Item>) -> Unit,
    )

    fun moveItem(
        householdId: String,
        item: Item,
        newParentItemId: String,
        updater: AuthenticatedIdentity,
        onResult: (Result<Item>) -> Unit,
    ) = onResult(Result.failure(UnsupportedOperationException("Item move is unavailable.")))

    fun designateItemPhoto(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        attachment: ItemAttachment,
        onResult: (Result<Item>) -> Unit,
    ) = onResult(Result.failure(UnsupportedOperationException("Item Photo designation is unavailable.")))

    fun deleteItemAttachment(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        attachment: ItemAttachment,
        remainingAttachments: List<ItemAttachment>,
        onResult: (Result<Item>) -> Unit,
    ) = onResult(Result.failure(UnsupportedOperationException("Item Attachment deletion is unavailable.")))
}

data class ItemDetails(
    val name: String,
    val description: String?,
    val tags: List<String>,
    val webUrl: String? = null,
)

internal object ItemFormPolicy {
    const val MAX_ITEM_NAME_LENGTH = 100
    const val MAX_DESCRIPTION_LENGTH = 2_000
    const val MAX_TAG_COUNT = 20
    const val MAX_TAG_LENGTH = 40
    const val MAX_WEB_URL_LENGTH = 2_048
}

interface InventoryActions {
    fun changeSearchQuery(query: String)
    fun openSearchResult(itemId: String)
    fun openItem(itemId: String)
    fun openParentItem()
    fun beginAddItem()
    fun beginEditItem()
    fun beginMoveItem()
    fun selectMoveParentItem(itemId: String)
    fun confirmMoveItem()
    fun closeMoveItem()
    fun beginAddItemAttachments()
    fun beginChooseItemAttachments() = Unit
    fun beginReplaceItemPhoto()
    fun removeItemPhoto()
    fun cameraUnavailable()
    fun resolveCameraPermission(granted: Boolean)
    fun photoCaptureFailed()
    fun photoCaptured(photo: ItemPhoto)
    fun photoPickerSelected(photos: List<ItemPhoto>) = Unit
    fun retakePhoto()
    fun useCroppedPhoto(photo: ItemPhoto)
    fun usePhotoWithoutCropping(photo: ItemPhoto) = Unit
    fun continueWithoutPhoto()
    fun addAnotherPhoto() = Unit
    fun closeItemForm()
    fun changeItemName(name: String)
    fun changeItemDescription(description: String)
    fun changeItemWebUrl(webUrl: String)
    fun changeTagInput(tag: String)
    fun addTag()
    fun addSuggestedTag(tag: String)
    fun removeTag(tag: String)
    fun saveItem()
    fun saveAndGenerateDescription()
    fun consumeDeferredError(id: String)
    fun retryFailedAttachment(id: String) = Unit
    fun removeFailedAttachment(id: String) = Unit
    fun openItemAttachmentCarousel()
    fun closeItemAttachmentCarousel()
    fun designateItemPhoto(attachment: ItemAttachment)
    fun deleteItemAttachment(attachment: ItemAttachment)
}

data class ItemAttachmentState(
    val itemId: String,
    val attachments: List<ItemAttachment> = emptyList(),
    val loading: Boolean = true,
    val errorMessage: String? = null,
)

data class ItemMoveState(
    val itemId: String,
    val selectedParentItemId: String? = null,
)

data class InventorySearchResult(
    val item: Item,
    val itemPath: List<Item>,
) {
    val itemPathText: String
        get() = itemPath.joinToString(" → ", transform = Item::name)
}

data class InventorySearchState(
    val query: String = "",
    val openedResultId: String? = null,
    val conceptualResultIds: List<String>? = null,
    val isConceptualSearchLoading: Boolean = false,
)

enum class ItemFormStage {
    CameraPermission,
    Camera,
    Crop,
    Details,
}

enum class ItemPhotoSelectionPurpose {
    CreateAttachments,
    ReplaceItemPhoto,
    AddAttachments,
}

data class ItemFormState(
    val name: String = "",
    val parentItemId: String,
    val stage: ItemFormStage = ItemFormStage.CameraPermission,
    val photoSelectionPurpose: ItemPhotoSelectionPurpose =
        ItemPhotoSelectionPurpose.CreateAttachments,
    val photo: ItemPhoto? = null,
    val photos: List<ItemPhoto> = emptyList(),
    val pendingPhotoUris: List<String> = emptyList(),
    val description: String = "",
    val webUrl: String = "",
    val tags: List<String> = emptyList(),
    val tagInput: String = "",
    val nameError: String? = null,
    val descriptionError: String? = null,
    val webUrlError: String? = null,
    val tagError: String? = null,
    val editingItemId: String? = null,
    val photoRemoved: Boolean = false,
)

data class InventoryUiState(
    val inventory: Inventory,
    val selectedItemId: String,
    val search: InventorySearchState = InventorySearchState(),
    val itemDraft: ItemFormState? = null,
    val itemMove: ItemMoveState? = null,
    val loading: Boolean = false,
    val operationInProgress: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val deferredError: DeferredInventoryError? = null,
    val failedAttachmentDrafts: List<FailedItemAttachmentDraft> = emptyList(),
    val itemAttachments: ItemAttachmentState? = null,
    val itemAttachmentCarousel: ItemAttachmentState? = null,
) {
    val moveParentItems: List<Item>
        get() {
            val move = itemMove ?: return emptyList()
            val item = inventory.item(move.itemId)
            return inventory.allItems.filter { candidate ->
                candidate.id != item.id &&
                    candidate.id != item.parentItemId &&
                    inventory.pathTo(candidate.id).none { it.id == item.id }
            }
        }

    val canGenerateDescription: Boolean
        get() {
            val draft = itemDraft ?: return false
            if (draft.photoSelectionPurpose == ItemPhotoSelectionPurpose.AddAttachments) {
                return false
            }
            if (draft.photos.size > 1) return false
            if (draft.photoRemoved) return false
            if (draft.photo != null) return true
            val editingItemId = draft.editingItemId ?: return false
            return inventory.contains(editingItemId) &&
                inventory.item(editingItemId).photoUrl != null
        }
    val itemFormStage: ItemFormStage?
        get() = itemDraft?.stage
    val selectedItem: Item
        get() = inventory.item(selectedItemId)
    val childItems: List<Item>
        get() = inventory.childrenOf(selectedItemId)
    val itemPath: List<Item>
        get() = inventory.pathTo(selectedItemId).drop(1).dropLast(1)
    val searchQuery: String
        get() = search.query
    val openedSearchResultId: String?
        get() = search.openedResultId
    val searchResults: List<InventorySearchResult>
        get() {
            val query = SearchQuery(searchQuery.trimUnicodeWhitespace().tagKey().value)
            if (query.value.isEmpty()) return emptyList()
            val literalResults = inventory.allItems
                .asSequence()
                .filterNot { it.id == inventory.rootItemId }
                .mapNotNull { item ->
                    item.searchRank(query)?.let { rank -> item to rank }
                }
                .sortedWith(compareBy({ it.second.field }, { it.second.match }))
                .map { (item) ->
                    InventorySearchResult(
                        item = item,
                        itemPath = inventory.pathTo(item.id).drop(1).dropLast(1),
                    )
                }
                .toList()
            val conceptualResultIds = search.conceptualResultIds ?: return literalResults
            val preciseResults = inventory.allItems
                .asSequence()
                .filterNot { it.id == inventory.rootItemId }
                .mapNotNull { item -> item.preciseSearchRank(query)?.let { item to it } }
                .sortedBy { it.second }
                .map { (item) -> item.toSearchResult(inventory) }
                .toList()
            val preciseIds = preciseResults.mapTo(mutableSetOf()) { it.item.id }
            val conceptualResults = conceptualResultIds
                .asSequence()
                .distinct()
                .filterNot(preciseIds::contains)
                .mapNotNull { itemId ->
                    itemId.takeIf(inventory::contains)?.let(inventory::item)
                }
                .filterNot { it.id == inventory.rootItemId }
                .map { it.toSearchResult(inventory) }
                .toList()
            return preciseResults + conceptualResults
        }
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

data class DeferredInventoryError(
    val id: String,
    val message: String,
)

class InventoryController internal constructor(
    private val household: Household,
    private val identity: AuthenticatedIdentity,
    private val gateway: InventoryGateway,
    private val rootChildItemCache: RootChildItemCache,
    private val descriptionGenerationWork: InventoryDescriptionGenerationWork =
        NoInventoryDescriptionGenerationWork,
    private val deviceLanguage: () -> String = { java.util.Locale.getDefault().toLanguageTag() },
    private val searchGateway: SearchGateway = NoSearchGateway,
    private val searchDebouncer: SearchDebouncer = NoSearchDebouncer,
    private val itemAttachmentGateway: ItemAttachmentGateway = NoItemAttachmentGateway,
    private val attachmentUploadFailureRegistry: AttachmentUploadFailureRegistry =
        processAttachmentUploadFailures,
) : InventoryActions, AutoCloseable {
    constructor(
        household: Household,
        identity: AuthenticatedIdentity,
        gateway: InventoryGateway,
    ) : this(household, identity, gateway, NoRootChildItemCache)

    private val cachedInventory = runCatching {
        rootChildItemCache.load(household.id)?.let { rootChildItems ->
            Inventory.from(household, listOf(household.rootItem) + rootChildItems)
        }
    }.getOrNull()

    var state = InventoryUiState(
        inventory = cachedInventory ?: Inventory.from(household, listOf(household.rootItem)),
        selectedItemId = household.rootItem.id,
        loading = true,
    )
        private set

    var onStateChanged: (InventoryUiState) -> Unit = {}

    private var observedInventory = state.inventory
    private var pendingDescriptionGenerations = emptyMap<String, DescriptionGenerationRequest>()
    private var searchDebounceSubscription: SearchSubscription? = null
    private var searchRequestSubscription: SearchSubscription? = null
    private var itemAttachmentSubscription: InventorySubscription? = null

    private val attachmentUploadFailureSubscription =
        attachmentUploadFailureRegistry.observe { drafts ->
            updateState(
                state.copy(
                    failedAttachmentDrafts = drafts.filter {
                        it.householdId == household.id &&
                            it.originatingMemberId == identity.id
                    },
                ),
            )
        }

    private val subscription = gateway.observe(household) { result ->
        result.onSuccess { inventory ->
            observedInventory = inventory
            runCatching {
                rootChildItemCache.store(
                    householdId = household.id,
                    items = inventory.childrenOf(inventory.rootItemId),
                )
            }
            val displayedInventory = inventory.withDescriptionGenerationOverlays()
            val selectedItemId = state.selectedItemId.takeIf(displayedInventory::contains)
                ?: displayedInventory.rootItemId
            updateState(
                state.copy(
                    inventory = displayedInventory,
                    selectedItemId = selectedItemId,
                    itemMove = state.itemMove?.let { move ->
                        if (!displayedInventory.contains(move.itemId)) {
                            null
                        } else {
                            move.copy(
                                selectedParentItemId = move.selectedParentItemId?.takeIf {
                                    displayedInventory.contains(it)
                                },
                            )
                        }
                    },
                    search = state.search.copy(
                        openedResultId = state.openedSearchResultId
                            ?.takeIf(inventory::contains),
                    ),
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

    private val descriptionGenerationSubscription = descriptionGenerationWork.observe { workState ->
        pendingDescriptionGenerations = workState.pending
            .filter { it.request.householdId == household.id }
            .associate { it.id to it.request }
        workState.completed
            .filter { it.householdId == household.id }
            .filter { it.outcome == DescriptionGenerationOutcome.Success }
            .forEach { completed -> descriptionGenerationWork.consumeOutcome(completed.id) }
        updateState(
            state.copy(
                inventory = observedInventory.withDescriptionGenerationOverlays(),
                deferredError = workState.completed
                    .asSequence()
                    .filter { it.householdId == household.id }
                    .mapNotNull(CompletedDescriptionGeneration::deferredError)
                    .firstOrNull(),
            ),
        )
    }

    override fun changeSearchQuery(query: String) {
        cancelConceptualSearch()
        updateState(state.copy(search = InventorySearchState(query = query)))
        if (!query.isConceptualSearchEligible()) return
        val conceptualQuery = query.trimUnicodeWhitespace()
        searchDebounceSubscription = searchDebouncer.schedule(
            CONCEPTUAL_SEARCH_DEBOUNCE_MILLIS,
        ) {
            if (
                state.search.query.trimUnicodeWhitespace() != conceptualQuery ||
                state.search.openedResultId != null
            ) {
                return@schedule
            }
            updateState(
                state.copy(
                    search = state.search.copy(isConceptualSearchLoading = true),
                ),
            )
            searchRequestSubscription = searchGateway.search(conceptualQuery) { result ->
                if (
                    state.search.query.trimUnicodeWhitespace() != conceptualQuery ||
                    state.search.openedResultId != null
                ) {
                    return@search
                }
                updateState(
                    state.copy(
                        search = state.search.copy(
                            conceptualResultIds = result.getOrNull(),
                            isConceptualSearchLoading = false,
                        ),
                    ),
                )
            }
        }
    }

    override fun openSearchResult(itemId: String) {
        if (state.itemDraft != null || state.searchResults.none { it.item.id == itemId }) return
        cancelConceptualSearch()
        updateState(
            state.copy(
                selectedItemId = itemId,
                search = state.search.copy(
                    openedResultId = itemId,
                    isConceptualSearchLoading = false,
                ),
                errorMessage = null,
                successMessage = null,
            ),
        )
        observeItemAttachments(state.selectedItem)
    }

    override fun openItem(itemId: String) {
        if (!state.inventory.contains(itemId) || state.itemDraft != null) return
        cancelConceptualSearch()
        updateState(
            state.copy(
                selectedItemId = itemId,
                search = InventorySearchState(),
                errorMessage = null,
                successMessage = null,
            ),
        )
        observeItemAttachments(state.selectedItem)
    }

    override fun openParentItem() {
        val parentItemId = state.selectedItem.parentItemId ?: return
        openItem(parentItemId)
    }

    override fun beginAddItem() {
        if (state.itemDraft != null || state.operationInProgress) return
        val parentItemId = when {
            state.search.openedResultId != null ->
                state.selectedItem.parentItemId ?: state.inventory.rootItemId
            state.search.query.trimUnicodeWhitespace().isNotEmpty() -> state.inventory.rootItemId
            else -> state.selectedItemId
        }
        updateState(
            state.copy(
                itemDraft = ItemFormState(parentItemId = parentItemId),
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
                    photoSelectionPurpose = ItemPhotoSelectionPurpose.ReplaceItemPhoto,
                    description = item.description.orEmpty(),
                    webUrl = item.webUrl.orEmpty(),
                    tags = item.tags,
                    editingItemId = item.id,
                ),
                errorMessage = null,
                successMessage = null,
            ),
        )
    }

    override fun beginMoveItem() {
        if (
            state.itemDraft != null ||
            state.itemMove != null ||
            state.operationInProgress ||
            state.selectedItemId == state.inventory.rootItemId
        ) {
            return
        }
        updateState(
            state.copy(
                itemMove = ItemMoveState(itemId = state.selectedItemId),
                errorMessage = null,
                successMessage = null,
            ),
        )
    }

    override fun selectMoveParentItem(itemId: String) {
        val move = state.itemMove ?: return
        if (
            state.operationInProgress ||
            !state.inventory.contains(itemId) ||
            itemId == move.itemId ||
            runCatching { state.inventory.pathTo(itemId) }.getOrNull()
                ?.any { it.id == move.itemId } == true
        ) {
            return
        }
        updateState(state.copy(itemMove = move.copy(selectedParentItemId = itemId)))
    }

    override fun confirmMoveItem() {
        val move = state.itemMove ?: return
        if (state.operationInProgress) return
        val newParentItemId = move.selectedParentItemId ?: return
        val item = runCatching { state.inventory.item(move.itemId) }.getOrElse { failure ->
            updateState(
                state.copy(errorMessage = failure.message ?: "Couldn't move the Item."),
            )
            return
        }
        runCatching {
            state.inventory.moveItem(item.id, newParentItemId).item(item.id)
        }.getOrElse { failure ->
            updateState(
                state.copy(errorMessage = failure.message ?: "Couldn't move the Item."),
            )
            return
        }
        updateState(state.copy(operationInProgress = true, errorMessage = null))
        gateway.moveItem(
            householdId = household.id,
            item = item,
            newParentItemId = newParentItemId,
            updater = identity,
        ) { result ->
            result.onSuccess { savedItem ->
                observedInventory = observedInventory.withItem(savedItem)
                updateState(
                    state.copy(
                        inventory = state.inventory.withItem(savedItem),
                        selectedItemId = savedItem.id,
                        itemMove = null,
                        operationInProgress = false,
                        errorMessage = null,
                        successMessage = "Item moved.",
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operationInProgress = false,
                        errorMessage = failure.message ?: "Couldn't move the Item.",
                        successMessage = null,
                    ),
                )
            }
        }
    }

    override fun closeMoveItem() {
        if (state.operationInProgress) return
        updateState(state.copy(itemMove = null, errorMessage = null))
    }

    override fun beginAddItemAttachments() {
        val draft = state.itemDraft ?: return
        if (state.operationInProgress || draft.editingItemId == null) return
        updateState(
            state.copy(
                itemDraft = draft.copy(
                    stage = ItemFormStage.CameraPermission,
                    photoSelectionPurpose = ItemPhotoSelectionPurpose.AddAttachments,
                    photo = null,
                    photos = emptyList(),
                    pendingPhotoUris = emptyList(),
                    photoRemoved = false,
                ),
                errorMessage = null,
            ),
        )
    }

    override fun beginChooseItemAttachments() {
        val draft = state.itemDraft ?: return
        if (
            state.operationInProgress ||
            draft.editingItemId == null ||
            draft.stage != ItemFormStage.Details
        ) return
        updateState(
            state.copy(
                itemDraft = draft.copy(
                    photoSelectionPurpose = ItemPhotoSelectionPurpose.AddAttachments,
                    photo = null,
                    photos = draft.photos.takeIf {
                        draft.photoSelectionPurpose == ItemPhotoSelectionPurpose.AddAttachments
                    }.orEmpty(),
                    pendingPhotoUris = emptyList(),
                    photoRemoved = false,
                ),
                errorMessage = null,
            ),
        )
    }

    override fun beginReplaceItemPhoto() {
        val draft = state.itemDraft ?: return
        if (
            draft.stage != ItemFormStage.Details ||
            state.operationInProgress
        ) {
            return
        }
        updateState(
            state.copy(
                itemDraft = draft.copy(
                    stage = ItemFormStage.CameraPermission,
                    photoSelectionPurpose = ItemPhotoSelectionPurpose.ReplaceItemPhoto,
                    photo = null,
                    photos = emptyList(),
                    pendingPhotoUris = emptyList(),
                ),
                errorMessage = null,
            ),
        )
    }

    override fun removeItemPhoto() {
        val draft = state.itemDraft ?: return
        if (
            draft.stage != ItemFormStage.Details ||
            state.operationInProgress
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

    override fun photoPickerSelected(photos: List<ItemPhoto>) {
        val draft = state.itemDraft ?: return
        if (draft.stage != ItemFormStage.Details || state.operationInProgress) return
        val first = photos.firstOrNull() ?: return
        updateState(
            state.copy(
                itemDraft = draft.copy(
                    stage = ItemFormStage.Crop,
                    photo = first,
                    pendingPhotoUris = photos.drop(1).map(ItemPhoto::uri),
                ),
                errorMessage = null,
            ),
        )
    }

    override fun retakePhoto() {
        transitionItemFormState(ItemFormStage.Crop) {
            it.copy(stage = ItemFormStage.Camera, photo = null)
        }
    }

    override fun useCroppedPhoto(photo: ItemPhoto) {
        transitionItemFormState(ItemFormStage.Crop) {
            it.acceptPhoto(photo)
        }
    }

    override fun usePhotoWithoutCropping(photo: ItemPhoto) {
        transitionItemFormState(ItemFormStage.Crop) {
            it.acceptPhoto(photo)
        }
    }

    override fun continueWithoutPhoto() {
        transitionItemFormState(ItemFormStage.Crop) {
            it.advancePhotoSelection(accepted = null)
        }
    }

    override fun addAnotherPhoto() {
        val draft = state.itemDraft ?: return
        if (draft.stage != ItemFormStage.Details || state.operationInProgress) return
        updateState(
            state.copy(
                itemDraft = draft.copy(
                    stage = ItemFormStage.CameraPermission,
                    photo = null,
                    pendingPhotoUris = emptyList(),
                ),
                errorMessage = null,
            ),
        )
    }

    override fun closeItemForm() {
        if (state.operationInProgress) return
        updateState(state.copy(itemDraft = null, errorMessage = null))
    }

    override fun changeItemName(name: String) {
        val draft = state.itemDraft ?: return
        updateState(state.copy(itemDraft = draft.copy(name = name, nameError = null)))
    }

    override fun changeItemDescription(description: String) {
        val draft = state.itemDraft ?: return
        updateState(
            state.copy(itemDraft = draft.copy(description = description, descriptionError = null)),
        )
    }

    override fun changeItemWebUrl(webUrl: String) {
        val draft = state.itemDraft ?: return
        updateState(state.copy(itemDraft = draft.copy(webUrl = webUrl, webUrlError = null)))
    }

    override fun changeTagInput(tag: String) {
        val draft = state.itemDraft ?: return
        updateState(state.copy(itemDraft = draft.copy(tagInput = tag, tagError = null)))
    }

    override fun addTag() {
        val draft = state.itemDraft ?: return
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
        if (state.operationInProgress) return
        val details = validateAndNormalize(draft) ?: return

        updateState(state.copy(operationInProgress = true, errorMessage = null))
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
                        itemDraft = null,
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
                photos = draft.photos,
                onResult = onResult,
            )
        } else {
            val item = state.inventory.item(editingItemId)
            val changesItemPhoto = draft.photoSelectionPurpose ==
                ItemPhotoSelectionPurpose.ReplaceItemPhoto
            val observedAttachments = state.itemAttachments
                ?.takeIf {
                    it.itemId == item.id &&
                        !it.loading &&
                        it.errorMessage == null
                }
                ?.attachments
                .orEmpty()
            val existingAttachments = buildList {
                addAll(observedAttachments)
                if (
                    changesItemPhoto &&
                    item.photoAttachmentId != null &&
                    item.photoUrl != null &&
                    observedAttachments.none { it.id == item.photoAttachmentId }
                ) {
                    // The compact Item projection is enough to remove or replace its
                    // designated attachment even if the attachment collection has not
                    // finished loading. Backfilled flat storage remains deletable.
                    add(
                        ItemAttachment(
                            id = item.photoAttachmentId,
                            itemId = item.id,
                            createdAt = Instant.EPOCH,
                            contentType = OPTIMIZED_ATTACHMENT_IMAGE_CONTENT_TYPE,
                            displayUrl = item.photoUrl,
                        ),
                    )
                }
            }
            val attachmentToDelete = existingAttachments.firstOrNull {
                changesItemPhoto &&
                    (draft.photo != null || draft.photoRemoved) &&
                    it.id == item.photoAttachmentId
            }
            val additionalPhotos = when {
                draft.photoSelectionPurpose == ItemPhotoSelectionPurpose.AddAttachments ->
                    draft.photos
                changesItemPhoto -> listOfNotNull(draft.photo)
                else -> emptyList()
            }
            gateway.updateItemWithAttachments(
                householdId = household.id,
                item = item,
                updater = identity,
                details = details,
                additionalPhotos = additionalPhotos,
                existingAttachments = existingAttachments,
                attachmentToDelete = attachmentToDelete,
                onResult = onResult,
            )
        }
    }

    override fun saveAndGenerateDescription() {
        val draft = state.itemDraft ?: return
        if (state.operationInProgress || !state.canGenerateDescription) return
        val details = validateAndNormalize(draft) ?: return

        val editingItemId = draft.editingItemId
        val capturedItem = if (editingItemId == null) {
            Item(
                id = gateway.newItemId(household.id),
                name = details.name,
                parentItemId = draft.parentItemId,
                photoUrl = null,
                description = details.description,
                tags = details.tags,
                webUrl = details.webUrl,
            )
        } else {
            state.inventory.item(editingItemId).copy(
                name = details.name,
                description = details.description,
                tags = details.tags,
                webUrl = details.webUrl,
            )
        }
        val submission = descriptionGenerationWork.submit(
            DescriptionGenerationRequest(
                householdId = household.id,
                item = capturedItem,
                requestingMember = RequestingMemberAttribution(
                    id = identity.id,
                    displayName = identity.attributionDisplayName(),
                ),
                deviceLanguage = deviceLanguage(),
                saveMode = if (editingItemId == null) {
                    DescriptionGenerationSaveMode.Create
                } else {
                    DescriptionGenerationSaveMode.Update
                },
            ),
            replacementPhoto = draft.photo,
        )
        val submitted = submission.request.item
        updateState(
            state.copy(
                inventory = state.inventory.withItem(submitted),
                selectedItemId = submitted.id,
                itemDraft = null,
                operationInProgress = false,
                errorMessage = null,
                successMessage = null,
            ),
        )
    }

    override fun consumeDeferredError(id: String) {
        if (state.deferredError?.id != id) return
        updateState(state.copy(deferredError = null))
        descriptionGenerationWork.consumeOutcome(id)
    }

    override fun retryFailedAttachment(id: String) {
        if (state.operationInProgress) return
        attachmentUploadFailureRegistry.retry(id)
    }

    override fun removeFailedAttachment(id: String) {
        if (state.operationInProgress) return
        attachmentUploadFailureRegistry.remove(id)
    }

    override fun openItemAttachmentCarousel() {
        val item = state.selectedItem
        if (item.photoUrl == null || item.id == household.rootItem.id || state.itemDraft != null) return
        val collection = state.itemAttachments
            ?.takeIf { it.itemId == item.id && it.errorMessage == null }
        updateState(
            state.copy(
                itemAttachmentCarousel = collection
                    ?: ItemAttachmentState(itemId = item.id),
            ),
        )
        if (collection == null) observeItemAttachments(item)
    }

    override fun closeItemAttachmentCarousel() {
        if (state.itemAttachmentCarousel != null) {
            updateState(state.copy(itemAttachmentCarousel = null))
        }
    }

    override fun designateItemPhoto(attachment: ItemAttachment) {
        val item = state.selectedItem
        val collection = state.itemAttachments
            ?.takeIf { it.itemId == item.id && !it.loading && it.errorMessage == null }
        if (
            state.operationInProgress ||
            state.itemAttachmentCarousel?.itemId != item.id ||
            attachment.itemId != item.id ||
            item.photoAttachmentId == attachment.id ||
            collection == null ||
            collection.attachments.none { it.id == attachment.id }
        ) {
            return
        }
        updateState(state.copy(operationInProgress = true, errorMessage = null))
        gateway.designateItemPhoto(
            householdId = household.id,
            item = item,
            updater = identity,
            attachment = attachment,
        ) { result ->
            result.onSuccess { updated ->
                observedInventory = observedInventory.withItem(updated)
                updateState(
                    state.copy(
                        inventory = state.inventory.withItem(updated),
                        operationInProgress = false,
                        errorMessage = null,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operationInProgress = false,
                        errorMessage = failure.message ?: "Couldn't designate the Item Photo.",
                    ),
                )
            }
        }
    }

    override fun deleteItemAttachment(attachment: ItemAttachment) {
        val item = state.selectedItem
        val collection = state.itemAttachments
            ?.takeIf { it.itemId == item.id && !it.loading && it.errorMessage == null }
            ?: return
        if (
            state.operationInProgress ||
            state.itemAttachmentCarousel?.itemId != item.id ||
            attachment.itemId != item.id ||
            collection.attachments.none { it.id == attachment.id }
        ) {
            return
        }
        val remaining = collection.attachments.filterNot { it.id == attachment.id }
        updateState(state.copy(operationInProgress = true, errorMessage = null))
        gateway.deleteItemAttachment(
            householdId = household.id,
            item = item,
            updater = identity,
            attachment = attachment,
            remainingAttachments = remaining,
        ) { result ->
            result.onSuccess { updated ->
                observedInventory = observedInventory.withItem(updated)
                val nextCollection = collection.copy(attachments = remaining)
                updateState(
                    state.copy(
                        inventory = state.inventory.withItem(updated),
                        operationInProgress = false,
                        errorMessage = null,
                        itemAttachments = nextCollection,
                        itemAttachmentCarousel = if (updated.photoUrl == null) {
                            null
                        } else {
                            nextCollection
                        },
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operationInProgress = false,
                        errorMessage = failure.message ?: "Couldn't delete the Item Attachment.",
                    ),
                )
            }
        }
    }

    private fun observeItemAttachments(item: Item) {
        itemAttachmentSubscription?.cancel()
        if (item.id == household.rootItem.id || item.parentItemId == null) {
            itemAttachmentSubscription = null
            updateState(state.copy(itemAttachments = null))
            return
        }
        updateState(
            state.copy(
                itemAttachments = ItemAttachmentState(itemId = item.id),
                itemAttachmentCarousel = state.itemAttachmentCarousel
                    ?.takeIf { it.itemId == item.id }
                    ?.copy(loading = true, errorMessage = null),
            ),
        )
        itemAttachmentSubscription = itemAttachmentGateway.observe(household, item) { result ->
            if (state.itemAttachments?.itemId != item.id) return@observe
            val collection = result.fold(
                onSuccess = { attachments ->
                    ItemAttachmentState(
                        itemId = item.id,
                        attachments = attachments,
                        loading = false,
                    )
                },
                onFailure = { failure ->
                    ItemAttachmentState(
                        itemId = item.id,
                        loading = false,
                        errorMessage = failure.message ?: "Couldn't load Item Attachments.",
                    )
                },
            )
            updateState(
                state.copy(
                    itemAttachments = collection,
                    itemAttachmentCarousel = state.itemAttachmentCarousel
                        ?.takeIf { it.itemId == item.id }
                        ?.let { collection },
                ),
            )
        }
    }

    private fun validateAndNormalize(draft: ItemFormState): ItemDetails? {
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
            return null
        }
        if (
            draft.description.codePointCount(0, draft.description.length) >
            ItemFormPolicy.MAX_DESCRIPTION_LENGTH
        ) {
            updateState(
                state.copy(
                    itemDraft = draft.copy(
                        descriptionError = "Descriptions can contain at most " +
                            "${java.lang.String.format(
                                java.util.Locale.ROOT,
                                "%,d",
                                ItemFormPolicy.MAX_DESCRIPTION_LENGTH,
                            )} characters.",
                    ),
                ),
            )
            return null
        }
        val webUrl = draft.webUrl.trimUnicodeWhitespace()
        val webUrlError = webUrl.takeIf(String::isNotEmpty)?.webUrlValidationFailure()
        if (webUrlError != null) {
            updateState(state.copy(itemDraft = draft.copy(webUrlError = webUrlError)))
            return null
        }
        if (!state.inventory.contains(draft.parentItemId)) {
            updateState(
                state.copy(errorMessage = "The Parent Item is no longer in this Household."),
            )
            return null
        }

        return ItemDetails(
            name = name,
            description = draft.description.takeIf(String::isNotEmpty),
            tags = draft.tags,
            webUrl = webUrl.takeIf(String::isNotEmpty),
        )
    }

    override fun close() {
        cancelConceptualSearch()
        searchDebouncer.close()
        subscription.cancel()
        itemAttachmentSubscription?.cancel()
        attachmentUploadFailureSubscription.cancel()
        descriptionGenerationSubscription.cancel()
        onStateChanged = {}
    }

    private fun Inventory.withDescriptionGenerationOverlays(): Inventory {
        return pendingDescriptionGenerations.values.fold(this) { inventory, request ->
            val item = request.item
            val canAddPendingCreate = request.saveMode == DescriptionGenerationSaveMode.Create &&
                item.parentItemId?.let(inventory::contains) == true
            if (inventory.contains(item.id) || canAddPendingCreate) {
                inventory.withItem(item)
            } else {
                inventory
            }
        }
    }

    private fun updateState(newState: InventoryUiState) {
        state = newState
        onStateChanged(newState)
    }

    private fun cancelConceptualSearch() {
        searchDebounceSubscription?.cancel()
        searchDebounceSubscription = null
        searchRequestSubscription?.cancel()
        searchRequestSubscription = null
    }

    private fun transitionItemFormState(
        expectedStage: ItemFormStage,
        transition: (ItemFormState) -> ItemFormState,
    ) {
        val draft = state.itemDraft ?: return
        if (draft.stage != expectedStage) return
        updateState(state.copy(itemDraft = transition(draft)))
    }

    private fun ItemFormState.acceptPhoto(photo: ItemPhoto): ItemFormState =
        advancePhotoSelection(accepted = photo)

    private fun ItemFormState.advancePhotoSelection(accepted: ItemPhoto?): ItemFormState {
        val acceptedPhotos = accepted?.let { photos + it } ?: photos
        val nextUri = pendingPhotoUris.firstOrNull()
        return if (nextUri != null) {
            copy(
                stage = ItemFormStage.Crop,
                photo = ItemPhoto(nextUri),
                photos = acceptedPhotos,
                pendingPhotoUris = pendingPhotoUris.drop(1),
                photoRemoved = false,
            )
        } else {
            copy(
                stage = ItemFormStage.Details,
                photo = acceptedPhotos.firstOrNull(),
                photos = acceptedPhotos,
                pendingPhotoUris = emptyList(),
                photoRemoved = if (acceptedPhotos.isEmpty()) {
                    editingItemId != null
                } else {
                    false
                },
            )
        }
    }

}

private fun CompletedDescriptionGeneration.deferredError(): DeferredInventoryError? {
    val message = outcome.deferredErrorMessage ?: return null
    return DeferredInventoryError(id, message)
}

internal fun String.trimUnicodeWhitespace(): String = trim { character ->
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

private enum class SearchFieldPriority {
    Name,
    Tag,
    Description,
}

private enum class SearchMatchPriority {
    Exact,
    Prefix,
    Substring,
}

private enum class PreciseSearchRank {
    ExactName,
    NamePrefix,
    ExactTag,
}

private data class SearchRank(
    val field: SearchFieldPriority,
    val match: SearchMatchPriority,
)

@JvmInline
private value class SearchQuery(val value: String)

private fun Item.searchRank(query: SearchQuery): SearchRank? {
    name.matchPriority(query)?.let { return SearchRank(SearchFieldPriority.Name, it) }
    tags.mapNotNull { it.matchPriority(query) }.minOrNull()?.let {
        return SearchRank(SearchFieldPriority.Tag, it)
    }
    description?.matchPriority(query)?.let {
        return SearchRank(SearchFieldPriority.Description, it)
    }
    return null
}

private fun Item.preciseSearchRank(query: SearchQuery): PreciseSearchRank? {
    val normalizedName = name.tagKey().value
    if (normalizedName == query.value) return PreciseSearchRank.ExactName
    if (normalizedName.startsWith(query.value)) return PreciseSearchRank.NamePrefix
    if (tags.any { it.tagKey().value == query.value }) return PreciseSearchRank.ExactTag
    return null
}

private fun Item.toSearchResult(inventory: Inventory): InventorySearchResult =
    InventorySearchResult(
        item = this,
        itemPath = inventory.pathTo(id).drop(1).dropLast(1),
    )

private fun String.matchPriority(query: SearchQuery): SearchMatchPriority? {
    val candidate = tagKey().value
    return when {
        candidate == query.value -> SearchMatchPriority.Exact
        candidate.startsWith(query.value) -> SearchMatchPriority.Prefix
        query.value in candidate -> SearchMatchPriority.Substring
        else -> null
    }
}

internal fun ItemDetails.validationFailure(): String? {
    if (name != name.trimUnicodeWhitespace() || name.isEmpty()) return "Invalid Item name."
    if (name.codePointCount(0, name.length) > ItemFormPolicy.MAX_ITEM_NAME_LENGTH) {
        return "Invalid Item name."
    }
    if (
        description != null &&
        description.codePointCount(0, description.length) > ItemFormPolicy.MAX_DESCRIPTION_LENGTH
    ) {
        return "Invalid Item description."
    }
    if (tags.size > ItemFormPolicy.MAX_TAG_COUNT) return "Invalid Item Tags."
    if (tags.any { tag ->
            tag != tag.trimUnicodeWhitespace() ||
                tag.isEmpty() ||
                tag.codePointCount(0, tag.length) > ItemFormPolicy.MAX_TAG_LENGTH
        }
    ) {
        return "Invalid Item Tags."
    }
    if (tags.distinctBy(String::tagKey).size != tags.size) return "Invalid Item Tags."
    webUrl?.webUrlValidationFailure()?.let { return it }
    return null
}

private fun String.webUrlValidationFailure(): String? {
    if (
        this != trimUnicodeWhitespace() ||
        codePointCount(0, length) > ItemFormPolicy.MAX_WEB_URL_LENGTH
    ) {
        return "Enter a valid web URL of at most ${ItemFormPolicy.MAX_WEB_URL_LENGTH} characters."
    }
    val valid = runCatching {
        val uri = java.net.URI(this)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
    return if (valid) {
        null
    } else {
        "Enter a valid web URL beginning with http:// or https://."
    }
}
