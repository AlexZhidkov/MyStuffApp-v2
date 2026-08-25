package com.azhidkov.mystuff.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.azhidkov.mystuff.DeferredInventoryError
import com.azhidkov.mystuff.HouseholdInvitation
import com.azhidkov.mystuff.InvitationStatus
import com.azhidkov.mystuff.InvitationUiState
import com.azhidkov.mystuff.InventoryActions
import com.azhidkov.mystuff.InventoryUiState
import com.azhidkov.mystuff.Item
import com.azhidkov.mystuff.ItemFormStage
import com.azhidkov.mystuff.ItemFormPolicy
import com.azhidkov.mystuff.R
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdRootScreen(
    inventoryState: InventoryUiState,
    invitationState: InvitationUiState,
    signOutInProgress: Boolean,
    onCreateInvitation: (String) -> Unit,
    onRevokeInvitation: (String) -> Unit,
    onReplaceInvitation: (String, String) -> Unit,
    inventoryActions: InventoryActions,
    onSignOut: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val deferredError = inventoryState.deferredError
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
            invitationState = invitationState,
            signOutInProgress = signOutInProgress,
            onCreateInvitation = onCreateInvitation,
            onRevokeInvitation = onRevokeInvitation,
            onReplaceInvitation = onReplaceInvitation,
            inventoryActions = inventoryActions,
            onSignOut = onSignOut,
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
    invitationState: InvitationUiState,
    signOutInProgress: Boolean,
    onCreateInvitation: (String) -> Unit,
    onRevokeInvitation: (String) -> Unit,
    onReplaceInvitation: (String, String) -> Unit,
    inventoryActions: InventoryActions,
    onSignOut: () -> Unit,
) {
    val itemDraft = inventoryState.itemDraft
    if (itemDraft != null) {
        BackHandler(enabled = !inventoryState.operationInProgress) {
            inventoryActions.closeItemForm()
        }
        when (itemDraft.stage) {
            ItemFormStage.CameraPermission,
            ItemFormStage.Camera,
            -> CameraCaptureStep(itemDraft.stage, inventoryActions)

            ItemFormStage.Crop -> CropPhotoScreen(
                photo = requireNotNull(itemDraft.photo),
                actions = inventoryActions,
            )

            ItemFormStage.Details -> ItemFormScreen(
                state = inventoryState,
                actions = inventoryActions,
            )
        }
        return
    }

    val isHome = inventoryState.selectedItemId == inventoryState.inventory.rootItemId
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
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            modifier = Modifier.clickable {
                                inventoryActions.openItem(inventoryState.inventory.rootItemId)
                            },
                            fontWeight = FontWeight.Bold,
                        )
                        TextField(
                            value = inventoryState.searchQuery,
                            onValueChange = inventoryActions::changeSearchQuery,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(stringResource(R.string.search_household)) },
                        )
                    }
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
            if (showSearchResults) {
                item {
                    AddItemButton(inventoryState, inventoryActions)
                }
                item {
                    Text(
                        text = stringResource(R.string.search_results),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (inventoryState.searchResults.isEmpty()) {
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
                                        color = MaterialTheme.colorScheme.primary,
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
                        StoredItemPhoto(
                            item = inventoryState.selectedItem,
                            presentation = ItemPhotoPresentation.Detail,
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
                if (inventoryState.selectedItem.tags.isNotEmpty()) {
                    item {
                        Text(
                            text = inventoryState.selectedItem.tags.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!isHome) {
                            TextButton(onClick = inventoryActions::beginEditItem) {
                                Text(stringResource(R.string.edit_item))
                            }
                        }
                        AddItemButton(inventoryState, inventoryActions)
                    }
                }
                if (inventoryState.childItems.isNotEmpty()) {
                    items(
                        items = inventoryState.childItems,
                        key = Item::id,
                    ) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { inventoryActions.openItem(item.id) },
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
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
                if (isHome && invitationState.canManage) {
                    item { Spacer(Modifier.height(16.dp)) }
                    item {
                        InvitationComposer(
                            state = invitationState,
                            onCreateInvitation = onCreateInvitation,
                        )
                    }
                    items(
                        items = invitationState.invitations,
                        key = HouseholdInvitation::id,
                    ) { invitation ->
                        InvitationCard(
                            invitation = invitation,
                            operationInProgress = invitationState.operationInProgress,
                            onRevoke = { onRevokeInvitation(invitation.id) },
                            onReplace = {
                                onReplaceInvitation(invitation.id, invitation.intendedEmail)
                            },
                        )
                    }
                }
                if (isHome) {
                    item { Spacer(Modifier.height(16.dp)) }
                    item {
                        TextButton(onClick = onSignOut, enabled = !signOutInProgress) {
                            Text(stringResource(R.string.sign_out))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AddItemButton(
    state: InventoryUiState,
    actions: InventoryActions,
) {
    Button(
        onClick = actions::beginAddItem,
        enabled = !state.loading,
    ) {
        Text(stringResource(R.string.add_item))
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
    val formEnabled = !state.operationInProgress
    val storedPhotoItem = draft.editingItemId
        ?.takeIf(state.inventory::contains)
        ?.let(state.inventory::item)
        ?.takeUnless { draft.photoRemoved || draft.photo != null }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (editing) R.string.edit_item else R.string.add_item),
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = actions::closeItemForm,
                        enabled = !state.operationInProgress,
                    ) {
                        Text(stringResource(R.string.cancel))
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
            draft.photo?.let { photo ->
                item {
                    LocalItemPhoto(
                        photo = photo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                    )
                }
            }
            storedPhotoItem?.let { itemWithPhoto ->
                if (storedPhotoLocation(itemWithPhoto, ItemPhotoPresentation.Detail) != null) {
                    item {
                        StoredItemPhoto(
                            item = itemWithPhoto,
                            presentation = ItemPhotoPresentation.Detail,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = actions::beginReplaceItemPhoto,
                        enabled = formEnabled,
                    ) {
                        Text(
                            stringResource(
                                if (
                                    draft.photo != null ||
                                    storedPhotoItem?.photoUrl != null
                                ) {
                                    R.string.replace_photo
                                } else {
                                    R.string.add_photo
                                },
                            ),
                        )
                    }
                    if (draft.photo != null || storedPhotoItem?.photoUrl != null) {
                        TextButton(
                            onClick = actions::removeItemPhoto,
                            enabled = formEnabled,
                        ) {
                            Text(stringResource(R.string.remove_photo))
                        }
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
                Button(
                    onClick = actions::saveItem,
                    enabled = !state.operationInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.operationInProgress) {
                            stringResource(R.string.saving_item)
                        } else {
                            stringResource(R.string.save_item)
                        },
                    )
                }
            }
            if (editing) {
                item {
                    OutlinedButton(
                        onClick = actions::saveAndGenerateDescription,
                        enabled = !state.operationInProgress && state.canGenerateDescription,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.save_and_generate_description))
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun InvitationComposer(
    state: InvitationUiState,
    onCreateInvitation: (String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    Text(
        text = stringResource(R.string.household_invitations),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = stringResource(R.string.household_invitations_body),
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
        onClick = { onCreateInvitation(email) },
        enabled = !state.operationInProgress,
    ) {
        Text(stringResource(R.string.create_invitation))
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
private fun InvitationCard(
    invitation: HouseholdInvitation,
    operationInProgress: Boolean,
    onRevoke: () -> Unit,
    onReplace: () -> Unit,
) {
    val status by currentInvitationStatus(invitation)
    val presentation = invitationStatusPresentation(invitation, status)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = presentation.containerColor),
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
                    text = invitation.intendedEmail,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                InvitationStatusLabel(presentation.label)
            }
            Text(
                text = presentation.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status == InvitationStatus.Pending) {
                Text(
                    text = stringResource(R.string.invitation_link),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SelectionContainer {
                    Text(
                        text = invitation.link,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (status == InvitationStatus.Pending) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRevoke, enabled = !operationInProgress) {
                        Text(stringResource(R.string.revoke_invitation))
                    }
                    TextButton(onClick = onReplace, enabled = !operationInProgress) {
                        Text(stringResource(R.string.replace_invitation))
                    }
                }
            }
        }
    }
}

@Composable
private fun currentInvitationStatus(
    invitation: HouseholdInvitation,
) = produceState(
    initialValue = invitation.statusAt(Instant.now()),
    key1 = invitation.id,
    key2 = invitation.expiresAt,
    key3 = invitation.storedStatus,
) {
    if (invitation.storedStatus != InvitationStatus.Pending) return@produceState
    val remaining = Duration.between(Instant.now(), invitation.expiresAt).toMillis()
    if (remaining > 0) delay(remaining)
    value = invitation.statusAt(Instant.now())
}

@Composable
private fun InvitationStatusLabel(label: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun invitationStatusPresentation(
    invitation: HouseholdInvitation,
    status: InvitationStatus,
): InvitationStatusPresentation = when (status) {
    InvitationStatus.Pending -> InvitationStatusPresentation(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        label = stringResource(R.string.invitation_pending),
        detail = stringResource(
            R.string.invitation_expires_on,
            invitation.expiresAt.formattedDate(),
        ),
    )
    InvitationStatus.Accepted -> InvitationStatusPresentation(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        label = stringResource(R.string.invitation_accepted),
        detail = stringResource(R.string.invitation_link_accepted),
    )
    InvitationStatus.Revoked -> InvitationStatusPresentation(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        label = stringResource(R.string.invitation_revoked),
        detail = stringResource(R.string.invitation_link_revoked),
    )
    InvitationStatus.Replaced -> InvitationStatusPresentation(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        label = stringResource(R.string.invitation_replaced),
        detail = stringResource(R.string.invitation_link_replaced),
    )
    InvitationStatus.Expired -> InvitationStatusPresentation(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        label = stringResource(R.string.invitation_expired),
        detail = stringResource(R.string.invitation_link_expired),
    )
}

private data class InvitationStatusPresentation(
    val containerColor: Color,
    val label: String,
    val detail: String,
)

private fun Instant.formattedDate(): String = DateTimeFormatter
    .ofLocalizedDate(FormatStyle.MEDIUM)
    .withLocale(Locale.getDefault())
    .withZone(ZoneId.systemDefault())
    .format(this)
