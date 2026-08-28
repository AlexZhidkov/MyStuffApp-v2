package com.azhidkov.mystuff

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
    val webUrl: String? = null,
)

internal object ItemFormPolicy {
    const val MAX_ITEM_NAME_LENGTH = 100
    const val MAX_DESCRIPTION_LENGTH = 2_000
    const val MAX_TAG_COUNT = 20
    const val MAX_TAG_LENGTH = 40
    const val MAX_WEB_URL_LENGTH = 2_048
}

sealed interface ItemPhotoUpdate {
    data object Unchanged : ItemPhotoUpdate
    data object Removed : ItemPhotoUpdate
    data class Replaced(val photo: ItemPhoto) : ItemPhotoUpdate
}

interface InventoryActions {
    fun changeSearchQuery(query: String)
    fun openSearchResult(itemId: String)
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
    fun changeItemWebUrl(webUrl: String)
    fun changeTagInput(tag: String)
    fun addTag()
    fun addSuggestedTag(tag: String)
    fun removeTag(tag: String)
    fun saveItem()
    fun saveAndGenerateDescription()
    fun consumeDeferredError(id: String)
}

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

data class ItemFormState(
    val name: String = "",
    val parentItemId: String,
    val stage: ItemFormStage = ItemFormStage.CameraPermission,
    val photo: ItemPhoto? = null,
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
    val loading: Boolean = false,
    val operationInProgress: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val deferredError: DeferredInventoryError? = null,
) {
    val canGenerateDescription: Boolean
        get() {
            val draft = itemDraft ?: return false
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
