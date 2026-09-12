package com.azhidkov.mystuff.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.azhidkov.mystuff.DeferredInventoryError
import com.azhidkov.mystuff.FailedItemAttachmentDraft
import com.azhidkov.mystuff.CarouselImage
import com.azhidkov.mystuff.AuthenticatedIdentity
import com.azhidkov.mystuff.HouseholdAccess
import com.azhidkov.mystuff.HouseholdAccessUiState
import com.azhidkov.mystuff.InventoryActions
import com.azhidkov.mystuff.InventoryUiState
import com.azhidkov.mystuff.Item
import com.azhidkov.mystuff.ItemAttachment
import com.azhidkov.mystuff.ItemAttachmentState
import com.azhidkov.mystuff.ItemFormStage
import com.azhidkov.mystuff.ItemFormPolicy
import com.azhidkov.mystuff.ItemPhoto
import com.azhidkov.mystuff.ItemPhotoSelectionPurpose
import com.azhidkov.mystuff.R
import com.azhidkov.mystuff.carouselImages
import com.azhidkov.mystuff.otherAttachmentCount
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdRootScreen(
    inventoryState: InventoryUiState,
    householdAccessState: HouseholdAccessUiState,
    currentIdentity: AuthenticatedIdentity,
    sessionOperationInProgress: Boolean,
    sessionMessage: String?,
    onAddHouseholdAccess: (String) -> Unit,
    onRemoveHouseholdAccess: (String) -> Unit,
    inventoryActions: InventoryActions,
    onSignOut: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onDeleteAccount: () -> Unit,
    onDeleteHousehold: (() -> Unit)?,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val deferredError = inventoryState.deferredError
    LaunchedEffect(sessionMessage) {
        sessionMessage?.let { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(deferredError?.id) {
        deferredError?.let { error ->
            presentDeferredInventoryError(
                error = error,
                showSnackbar = snackbarHostState::showSnackbar,
                consume = inventoryActions::consumeDeferredError,
            )
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        HouseholdRootContent(
            inventoryState = inventoryState,
            householdAccessState = householdAccessState,
            currentIdentity = currentIdentity,
            sessionOperationInProgress = sessionOperationInProgress,
            onAddHouseholdAccess = onAddHouseholdAccess,
            onRemoveHouseholdAccess = onRemoveHouseholdAccess,
            inventoryActions = inventoryActions,
            onSignOut = onSignOut,
            onPrivacyPolicy = onPrivacyPolicy,
            onDeleteAccount = onDeleteAccount,
            onDeleteHousehold = onDeleteHousehold,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
        )
    }
}

internal suspend fun presentDeferredInventoryError(
    error: DeferredInventoryError,
    showSnackbar: suspend (String) -> Unit,
    consume: (String) -> Unit,
) {
    try {
        showSnackbar(error.message)
    } finally {
        consume(error.id)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HouseholdRootContent(
    inventoryState: InventoryUiState,
    householdAccessState: HouseholdAccessUiState,
    currentIdentity: AuthenticatedIdentity,
    sessionOperationInProgress: Boolean,
    onAddHouseholdAccess: (String) -> Unit,
    onRemoveHouseholdAccess: (String) -> Unit,
    inventoryActions: InventoryActions,
    onSignOut: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onDeleteAccount: () -> Unit,
    onDeleteHousehold: (() -> Unit)?,
) {
    var showMembers by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<Item?>(null) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val itemPhotoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        inventoryActions.photoPickerSelected(uris.map { ItemPhoto(it.toString()) })
    }

    if (showMembers) {
        BackHandler { showMembers = false }
        MembersScreen(
            state = householdAccessState,
            owner = currentIdentity,
            onAddAccess = onAddHouseholdAccess,
            onRemoveAccess = onRemoveHouseholdAccess,
            onClose = { showMembers = false },
        )
        return
    }

    val itemDraft = inventoryState.itemDraft
    if (itemDraft != null) {
        val context = LocalContext.current
        val unsavedPhotos = itemDraft.photos + listOfNotNull(itemDraft.photo)
        BackHandler(
            enabled = !inventoryState.operationInProgress &&
                itemDraft.stage != ItemFormStage.Crop,
        ) {
            discardUnsavedPhotoSources(context, unsavedPhotos)
            inventoryActions.closeItemForm()
        }
        when (itemDraft.stage) {
            ItemFormStage.CameraPermission,
            ItemFormStage.Camera,
            -> CameraCaptureStep(itemDraft.stage, unsavedPhotos, inventoryActions)

            ItemFormStage.Crop -> CropPhotoScreen(
                photo = requireNotNull(itemDraft.photo),
                unsavedPhotos = listOfNotNull(itemDraft.photo),
                processingPurpose = if (
                    itemDraft.photoSelectionPurpose != ItemPhotoSelectionPurpose.ReplaceItemPhoto
                ) {
                    PhotoProcessingPurpose.ItemAttachment
                } else {
                    PhotoProcessingPurpose.ItemPhoto
                },
                selectionSource = itemDraft.photoSelectionSource,
                selectionIndex = itemDraft.photoSelectionTotal - itemDraft.pendingPhotoUris.size,
                selectionTotal = itemDraft.photoSelectionTotal,
                saving = inventoryState.operationInProgress,
                actions = inventoryActions,
            )

            ItemFormStage.Details -> ItemFormScreen(
                state = inventoryState,
                actions = inventoryActions,
            )
        }
        return
    }

    inventoryState.itemPhotoAddition?.let { addition ->
        val unsavedPhotos = listOfNotNull(addition.photo)
        when (addition.stage) {
            ItemFormStage.CameraPermission,
            ItemFormStage.Camera,
            -> CameraCaptureStep(
                stage = addition.stage,
                unsavedPhotos = unsavedPhotos,
                actions = inventoryActions,
                onCancel = inventoryActions::cancelPhotoSelection,
            )

            ItemFormStage.Crop -> CropPhotoScreen(
                photo = requireNotNull(addition.photo),
                unsavedPhotos = unsavedPhotos,
                processingPurpose = PhotoProcessingPurpose.ItemAttachment,
                selectionSource = addition.source,
                selectionIndex = addition.selectionTotal - addition.pendingPhotoUris.size,
                selectionTotal = addition.selectionTotal,
                addingToExistingItem = true,
                saving = addition.saving,
                actions = inventoryActions,
            )

            ItemFormStage.Details -> Unit
        }
        if (addition.stage != ItemFormStage.Details) return
    }

    inventoryState.itemMove?.let { moveState ->
        BackHandler(enabled = !inventoryState.operationInProgress) {
            inventoryActions.closeMoveItem()
        }
        ItemMoveScreen(
            inventory = inventoryState.inventory,
            item = inventoryState.inventory.item(moveState.itemId),
            candidates = inventoryState.moveParentItems,
            selectedParentItemId = moveState.selectedParentItemId,
            operationInProgress = inventoryState.operationInProgress,
            onSelect = inventoryActions::selectMoveParentItem,
            onConfirm = inventoryActions::confirmMoveItem,
            onClose = inventoryActions::closeMoveItem,
        )
        return
    }

    inventoryState.itemAttachmentCarousel?.let { carouselState ->
        BackHandler { inventoryActions.closeItemAttachmentCarousel() }
        ItemAttachmentCarouselScreen(
            item = inventoryState.selectedItem,
            state = carouselState,
            onClose = inventoryActions::closeItemAttachmentCarousel,
            onDesignate = inventoryActions::designateItemPhoto,
            onDeleteAttachment = inventoryActions::deleteItemAttachment,
            operationInProgress = inventoryState.operationInProgress,
        )
        return
    }

    val isHome = inventoryState.selectedItemId == inventoryState.inventory.rootItemId
    val reorderStepPx = with(LocalDensity.current) { 56.dp.toPx() }
    val showSearchResults = inventoryState.searchQuery.isNotBlank() &&
        inventoryState.openedSearchResultId == null
    BackHandler(
        enabled = inventoryState.openedSearchResultId != null ||
            (!isHome && !showSearchResults),
    ) {
        if (inventoryState.openedSearchResultId != null) {
            inventoryActions.changeSearchQuery(inventoryState.searchQuery)
        } else {
            inventoryActions.openParentItem()
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            AddItemFab(inventoryState, inventoryActions)
        },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppBarBrandIcon(
                            onClick = {
                                inventoryActions.openItem(inventoryState.inventory.rootItemId)
                            },
                        )
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            TextField(
                                value = inventoryState.searchQuery,
                                onValueChange = inventoryActions::changeSearchQuery,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.search_household)) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = { keyboardController?.hide() },
                                ),
                                trailingIcon = {
                                    if (inventoryState.searchQuery.isNotEmpty()) {
                                        Spacer(Modifier.size(48.dp))
                                    }
                                },
                            )
                            if (inventoryState.searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { inventoryActions.changeSearchQuery("") },
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_clear),
                                        contentDescription = stringResource(R.string.clear_search),
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    AppBarOverflowMenu(
                        enabled = !sessionOperationInProgress,
                        onMembers = if (householdAccessState.canManage) {
                            { showMembers = true }
                        } else {
                            null
                        },
                        onSignOut = onSignOut,
                        onPrivacyPolicy = onPrivacyPolicy,
                        onDeleteAccount = onDeleteAccount,
                        onDeleteHousehold = onDeleteHousehold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            inventoryState.failedAttachmentDrafts.forEach { draft ->
                item(key = "failed-attachment:${draft.id}") {
                    FailedAttachmentCard(
                        draft = draft,
                        onRetry = { inventoryActions.retryFailedAttachment(draft.id) },
                        onRemove = { inventoryActions.removeFailedAttachment(draft.id) },
                    )
                }
            }
            if (showSearchResults) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.search_results),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (inventoryState.search.isConceptualSearchLoading) {
                            LinearProgressIndicator()
                        }
                    }
                }
                if (
                    inventoryState.searchResults.isEmpty() &&
                    !inventoryState.search.isConceptualSearchLoading
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.no_search_results),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(
                        items = inventoryState.searchResults,
                        key = { it.item.id },
                    ) { result ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    inventoryActions.openSearchResult(result.item.id)
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                storedPhotoLocation(
                                    result.item,
                                    ItemPhotoPresentation.Compact,
                                )?.let {
                                    StoredItemPhoto(
                                        item = result.item,
                                        presentation = ItemPhotoPresentation.Compact,
                                        modifier = Modifier.size(64.dp),
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = result.item.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = result.itemPathText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                storedPhotoLocation(
                    inventoryState.selectedItem,
                    ItemPhotoPresentation.Detail,
                )?.let {
                    item {
                        ItemPhotoWithAttachmentBadge(
                            item = inventoryState.selectedItem,
                            attachments = inventoryState.itemAttachments,
                            onClick = inventoryActions::openItemAttachmentCarousel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                        )
                    }
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        inventoryState.itemPath.forEachIndexed { index, pathItem ->
                            if (index > 0) {
                                Text(
                                    text = "→",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = pathItem.name,
                                modifier = Modifier.clickable {
                                    inventoryActions.openItem(pathItem.id)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                item {
                    val webUrl = inventoryState.selectedItem.webUrl?.takeIf(String::isNotBlank)
                    val context = LocalContext.current
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = inventoryState.selectedItem.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        webUrl?.let { url ->
                            IconButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                    )
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_web),
                                    contentDescription = stringResource(R.string.open_item_web_url),
                                )
                            }
                        }
                        if (!isHome) {
                            ItemActionsOverflowMenu(
                                canDelete = inventoryState.childItems.isEmpty() &&
                                    !inventoryState.operationInProgress,
                                actionsEnabled = !inventoryState.operationInProgress,
                                onEdit = inventoryActions::beginEditItem,
                                onTakePhoto = inventoryActions::beginTakeItemPhoto,
                                onChoosePhotos = {
                                    inventoryActions.beginChooseItemPhotos()
                                    itemPhotoPickerLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                                        ),
                                    )
                                },
                                onMove = inventoryActions::beginMoveItem,
                                onDelete = {
                                    deleteCandidate = inventoryState.selectedItem
                                },
                            )
                        }
                    }
                }
                inventoryState.selectedItem.description?.let { description ->
                    item {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (inventoryState.useTags && inventoryState.selectedItem.tags.isNotEmpty()) {
                    item {
                        Text(
                            text = inventoryState.selectedItem.tags.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                if (inventoryState.childItems.isNotEmpty()) {
                    items(
                        items = inventoryState.childItems,
                        key = Item::id,
                    ) { item ->
                        var dragOffsetY by remember(item.id) { mutableStateOf(0f) }
                        val reorderDescription = stringResource(R.string.reorder_item, item.name)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (dragOffsetY == 0f) 0f else 1f)
                                .graphicsLayer { translationY = dragOffsetY }
                                .clickable { inventoryActions.openItem(item.id) },
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .semantics {
                                            contentDescription = reorderDescription
                                        }
                                        .pointerInput(item.id) {
                                            detectDragGestures(
                                                onDragEnd = { dragOffsetY = 0f },
                                                onDragCancel = { dragOffsetY = 0f },
                                            ) { change, dragAmount ->
                                                change.consume()
                                                dragOffsetY += dragAmount.y
                                                while (abs(dragOffsetY) >= reorderStepPx) {
                                                    val offset = if (dragOffsetY > 0f) 1 else -1
                                                    inventoryActions.reorderItem(item.id, offset)
                                                    dragOffsetY -= offset * reorderStepPx
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_drag_handle),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                storedPhotoLocation(
                                    item,
                                    ItemPhotoPresentation.Compact,
                                )?.let {
                                    StoredItemPhoto(
                                        item = item,
                                        presentation = ItemPhotoPresentation.Compact,
                                        modifier = Modifier.size(64.dp),
                                    )
                                }
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
                inventoryState.errorMessage?.let { error ->
                    item {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                inventoryState.successMessage?.let { success ->
                    item {
                        Text(
                            text = success,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    deleteCandidate?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.delete_item_title, item.name)) },
            text = { Text(stringResource(R.string.delete_item_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteCandidate = null
                        inventoryActions.deleteItem()
                    },
                ) {
                    Text(stringResource(R.string.delete_item))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ItemActionsOverflowMenu(
    canDelete: Boolean,
    actionsEnabled: Boolean,
    onEdit: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhotos: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.open_menu),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit_item)) },
                onClick = {
                    expanded = false
                    onEdit()
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = null,
                    )
                },
                enabled = actionsEnabled,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.take_photo)) },
                onClick = {
                    expanded = false
                    onTakePhoto()
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_photo_camera),
                        contentDescription = null,
                    )
                },
                enabled = actionsEnabled,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.choose_photos)) },
                onClick = {
                    expanded = false
                    onChoosePhotos()
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_photo),
                        contentDescription = null,
                    )
                },
                enabled = actionsEnabled,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_item)) },
                onClick = {
                    expanded = false
                    onMove()
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_move),
                        contentDescription = null,
                    )
                },
                enabled = actionsEnabled,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_item)) },
                onClick = {
                    expanded = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                    )
                },
                enabled = canDelete,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MembersScreen(
    state: HouseholdAccessUiState,
    owner: AuthenticatedIdentity,
    onAddAccess: (String) -> Unit,
    onRemoveAccess: (String) -> Unit,
    onClose: () -> Unit,
) {
    var removeCandidate by remember { mutableStateOf<HouseholdAccess?>(null) }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.members)) },
                actions = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_clear),
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "owner") {
                MemberCard(
                    name = owner.displayName ?: owner.email ?: stringResource(R.string.household_member),
                    email = owner.email,
                    detail = stringResource(R.string.household_owner),
                    operationInProgress = true,
                    removable = false,
                    onRemove = {},
                )
            }
            items(items = state.access, key = HouseholdAccess::email) { access ->
                MemberCard(
                    name = access.memberDisplayName ?: access.email,
                    email = access.memberEmail ?: if (access.isClaimed) access.email else null,
                    detail = if (access.isClaimed) {
                        stringResource(R.string.household_member)
                    } else {
                        stringResource(R.string.not_signed_in_yet)
                    },
                    operationInProgress = state.operationInProgress,
                    removable = true,
                    onRemove = { removeCandidate = access },
                )
            }
            item {
                AccessComposer(
                    state = state,
                    onAddAccess = onAddAccess,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    removeCandidate?.let { access ->
        AlertDialog(
            onDismissRequest = { removeCandidate = null },
            title = { Text(stringResource(R.string.remove_household_access_title)) },
            text = { Text(stringResource(R.string.remove_household_access_body, access.email)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveAccess(access.email)
                        removeCandidate = null
                    },
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removeCandidate = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun FailedAttachmentCard(
    draft: FailedItemAttachmentDraft,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = draft.message,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text(stringResource(R.string.retry_attachment)) }
                OutlinedButton(onClick = onRemove) {
                    Text(stringResource(R.string.remove_attachment_draft))
                }
            }
        }
    }
}

@Composable
private fun ItemPhotoWithAttachmentBadge(
    item: Item,
    attachments: ItemAttachmentState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.clickable(onClick = onClick)) {
        StoredItemPhoto(
            item = item,
            presentation = ItemPhotoPresentation.Detail,
            modifier = Modifier.fillMaxSize(),
        )
        val count = attachments
            ?.takeIf { it.itemId == item.id && !it.loading && it.errorMessage == null }
            ?.let { item.otherAttachmentCount(it.attachments) }
            ?: 0
        if (count > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = "+$count",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemAttachmentCarouselScreen(
    item: Item,
    state: ItemAttachmentState,
    onClose: () -> Unit,
    onDesignate: (ItemAttachment) -> Unit,
    onDeleteAttachment: (ItemAttachment) -> Unit,
    operationInProgress: Boolean,
) {
    val images = item.carouselImages(state.attachments)
    val pagerState = rememberPagerState(pageCount = { images.size.coerceAtLeast(1) })
    val loader = itemPhotoBitmapLoader(LocalContext.current.applicationContext)
    var deleteCandidate by remember { mutableStateOf<ItemAttachment?>(null) }
    var currentPageZoomed by remember { mutableStateOf(false) }
    val currentAttachment = when (val image = images.getOrNull(pagerState.currentPage)) {
        is CarouselImage.Attachment -> image.attachment
        is CarouselImage.ItemPhoto -> state.attachments.firstOrNull {
            it.id == item.photoAttachmentId
        }
        null -> null
    }
    LaunchedEffect(state.itemId, state.attachments) {
        loader.prepareAttachmentDisplays(
            buildList {
                item.photoUrl?.let(::add)
                state.attachments
                    .asSequence()
                    .filterNot { it.id == item.photoAttachmentId }
                    .mapTo(this) { it.displayUrl }
            },
        )
    }
    LaunchedEffect(pagerState.currentPage) { currentPageZoomed = false }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (images.isNotEmpty()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !currentPageZoomed,
                ) { page ->
                    when (val image = images[page]) {
                        is CarouselImage.ItemPhoto -> AttachmentDisplayPhoto(
                            location = requireNotNull(image.item.photoUrl),
                            previewLocation = image.item.photoThumbnailUrl,
                            active = page == pagerState.currentPage,
                            onZoomChanged = { zoomed ->
                                if (page == pagerState.currentPage) currentPageZoomed = zoomed
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 72.dp),
                        )
                        is CarouselImage.Attachment -> AttachmentDisplayPhoto(
                            location = image.attachment.displayUrl,
                            active = page == pagerState.currentPage,
                            onZoomChanged = { zoomed ->
                                if (page == pagerState.currentPage) currentPageZoomed = zoomed
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 72.dp),
                        )
                    }
                }
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_photo),
                    contentDescription = stringResource(R.string.item_photo_unavailable),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp),
                    tint = Color.White,
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 32.dp, start = 8.dp),
            ) {
                Text(
                    text = "×",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            if (state.loading) {
                LinearProgressIndicator(
                    color = Color.White,
                )
            }
            state.errorMessage?.let { error ->
                Text(
                    text = error,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (images.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${images.size}",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            currentAttachment?.let { attachment ->
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 64.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (attachment.id != item.photoAttachmentId) {
                        TextButton(
                            onClick = { onDesignate(attachment) },
                            enabled = !operationInProgress,
                        ) {
                            Text(
                                text = stringResource(R.string.designate_item_photo),
                                color = Color.White,
                            )
                        }
                    }
                    TextButton(
                        onClick = { deleteCandidate = attachment },
                        enabled = !operationInProgress,
                    ) {
                        Text(
                            text = stringResource(R.string.delete_attachment),
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
    deleteCandidate?.let { attachment ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.delete_attachment_title)) },
            text = { Text(stringResource(R.string.delete_attachment_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        loader.evictAttachmentDisplay(attachment.displayUrl)
                        onDeleteAttachment(attachment)
                        deleteCandidate = null
                    },
                ) {
                    Text(stringResource(R.string.delete_attachment))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AttachmentDisplayPhoto(
    location: String,
    previewLocation: String? = null,
    active: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by rememberAttachmentDisplayPhoto(location, previewLocation)
    val photoState = state
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (photoState) {
            PhotoLoadState.Loading -> LinearProgressIndicator(color = Color.White)
            PhotoLoadState.Unavailable -> Text(
                text = stringResource(R.string.attachment_unavailable),
                color = Color.White,
            )
            is PhotoLoadState.Available -> ZoomablePhoto(
                bitmap = photoState.value,
                contentDescription = stringResource(R.string.item_attachment),
                active = active,
                onZoomChanged = onZoomChanged,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AddItemFab(
    state: InventoryUiState,
    actions: InventoryActions,
) {
    if (!state.loading) {
        FloatingActionButton(onClick = actions::beginAddItem) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.add_item),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemFormScreen(
    state: InventoryUiState,
    actions: InventoryActions,
) {
    val draft = requireNotNull(state.itemDraft)
    val editing = draft.editingItemId != null
    val context = LocalContext.current
    val unsavedPhotos = draft.photos + listOfNotNull(draft.photo)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        actions.photoPickerSelected(uris.map { ItemPhoto(it.toString()) })
    }
    val formEnabled = !state.operationInProgress
    val saveDescription = stringResource(
        if (state.operationInProgress) R.string.saving_item else R.string.save_item,
    )
    val saveAndGenerateDescription = stringResource(R.string.save_and_generate_description)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (editing) R.string.edit_item else R.string.add_item),
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            discardUnsavedPhotoSources(context, unsavedPhotos)
                            actions.closeItemForm()
                        },
                        enabled = !state.operationInProgress,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_clear),
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(12.dp)) }
            item {
                Text(
                    text = state.inventory.pathTo(draft.parentItemId)
                        .joinToString(" → ", transform = Item::name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!editing && draft.photos.isNotEmpty()) {
                items(draft.photos) { photo ->
                    LocalItemPhoto(
                        photo = photo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                    )
                }
            }
            if (!editing) item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(
                        onClick = actions::addAnotherPhoto,
                        enabled = formEnabled,
                    ) {
                        Text(
                            stringResource(
                                if (draft.photos.isEmpty()) {
                                    R.string.add_photo
                                } else {
                                    R.string.add_another_photo
                                },
                            ),
                        )
                    }
                    TextButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        enabled = formEnabled,
                    ) {
                        Text(stringResource(R.string.choose_photos))
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = actions::changeItemName,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = formEnabled,
                    singleLine = true,
                    label = { Text(stringResource(R.string.item_name)) },
                    supportingText = {
                        Text(draft.nameError ?: stringResource(R.string.item_name_supporting_text))
                    },
                    isError = draft.nameError != null,
                )
            }
            item {
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = actions::changeItemDescription,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = formEnabled,
                    minLines = 4,
                    label = { Text(stringResource(R.string.item_description)) },
                    supportingText = {
                        Text(
                            draft.descriptionError
                                ?: stringResource(
                                    R.string.item_description_supporting_text,
                                    draft.description.codePointCount(0, draft.description.length),
                                    ItemFormPolicy.MAX_DESCRIPTION_LENGTH,
                                ),
                        )
                    },
                    isError = draft.descriptionError != null,
                )
            }
            item {
                OutlinedTextField(
                    value = draft.webUrl,
                    onValueChange = actions::changeItemWebUrl,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = formEnabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    label = { Text(stringResource(R.string.item_web_url)) },
                    supportingText = {
                        Text(
                            draft.webUrlError
                                ?: stringResource(R.string.item_web_url_supporting_text),
                        )
                    },
                    isError = draft.webUrlError != null,
                )
            }
            if (state.useTags) {
                item {
                    Text(
                        text = stringResource(R.string.item_tags),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(draft.tags, key = { "selected-tag:$it" }) { tag ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tag, style = MaterialTheme.typography.bodyLarge)
                        TextButton(
                            onClick = { actions.removeTag(tag) },
                            enabled = formEnabled,
                        ) {
                            Text(stringResource(R.string.remove_tag))
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = draft.tagInput,
                        onValueChange = actions::changeTagInput,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = formEnabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.item_tag)) },
                        supportingText = {
                            Text(
                                draft.tagError ?: stringResource(
                                    R.string.item_tag_supporting_text,
                                    ItemFormPolicy.MAX_TAG_LENGTH,
                                    ItemFormPolicy.MAX_TAG_COUNT,
                                ),
                            )
                        },
                        isError = draft.tagError != null,
                    )
                }
                item {
                    Button(
                        onClick = actions::addTag,
                        enabled = formEnabled && draft.tags.size < ItemFormPolicy.MAX_TAG_COUNT,
                    ) {
                        Text(stringResource(R.string.add_tag))
                    }
                }
                if (state.tagSuggestions.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.existing_household_tags),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    items(state.tagSuggestions, key = { "suggested-tag:$it" }) { suggestion ->
                        TextButton(
                            onClick = { actions.addSuggestedTag(suggestion) },
                            enabled = formEnabled,
                        ) {
                            Text(suggestion)
                        }
                    }
                }
            }
            state.errorMessage?.let { error ->
                item {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            state.successMessage?.let { success ->
                item {
                    Text(
                        text = success,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = actions::saveItem,
                        enabled = !state.operationInProgress,
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = saveDescription
                            },
                    ) {
                        if (state.operationInProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_save),
                            contentDescription = null,
                        )
                        }
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(saveDescription)
                    }
                    FilledTonalButton(
                        onClick = actions::saveAndGenerateDescription,
                        enabled = !state.operationInProgress && state.canGenerateDescription,
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = saveAndGenerateDescription
                            },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_auto_awesome),
                            contentDescription = null,
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(saveAndGenerateDescription)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AccessComposer(
    state: HouseholdAccessUiState,
    onAddAccess: (String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    Text(
        text = stringResource(R.string.household_access_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.operationInProgress,
        singleLine = true,
        label = { Text(stringResource(R.string.google_email_address)) },
        isError = state.emailError != null,
        supportingText = { state.emailError?.let { error -> Text(error) } },
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { onAddAccess(email) },
        enabled = !state.operationInProgress,
    ) {
        Text(stringResource(R.string.add_household_access))
    }
    state.errorMessage?.let { error ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MemberCard(
    name: String,
    email: String?,
    detail: String,
    operationInProgress: Boolean,
    removable: Boolean,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            email?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (removable) {
                TextButton(onClick = onRemove, enabled = !operationInProgress) {
                    Text(stringResource(R.string.remove))
                }
            }
        }
    }
}
