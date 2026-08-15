package com.azhidkov.mystuff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azhidkov.mystuff.HouseholdInvitation
import com.azhidkov.mystuff.InvitationStatus
import com.azhidkov.mystuff.InvitationUiState
import com.azhidkov.mystuff.InventoryActions
import com.azhidkov.mystuff.InventoryUiState
import com.azhidkov.mystuff.Item
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
    val itemDraft = inventoryState.itemDraft
    if (itemDraft != null) {
        AddItemScreen(
            state = inventoryState,
            onCancel = inventoryActions::cancelAddItem,
            onChangeName = inventoryActions::changeItemName,
            onChangeParent = inventoryActions::changeParentItem,
            onSave = inventoryActions::saveItem,
        )
        return
    }

    val isHome = inventoryState.selectedItemId == inventoryState.inventory.rootItemId
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    if (!isHome) {
                        TextButton(onClick = inventoryActions::openParentItem) {
                            Text(stringResource(R.string.up_to_parent_item))
                        }
                    }
                    TextButton(onClick = onSignOut, enabled = !signOutInProgress) {
                        Text(stringResource(R.string.sign_out))
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
            item { Spacer(Modifier.height(24.dp)) }
            item {
                Text(
                    text = stringResource(
                        if (isHome) {
                            R.string.household_root_label
                        } else {
                            R.string.item_details
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                Text(
                    text = inventoryState.selectedItem.name,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                Text(
                    text = inventoryState.itemPath.joinToString(" → ", transform = Item::name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                Button(
                    onClick = inventoryActions::beginAddItem,
                    enabled = !inventoryState.loading,
                ) {
                    Text(stringResource(R.string.add_item))
                }
            }
            item {
                Text(
                    text = stringResource(R.string.child_items),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (inventoryState.childItems.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_child_items),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(
                    items = inventoryState.childItems,
                    key = Item::id,
                ) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { inventoryActions.openItem(item.id) },
                    ) {
                        Text(
                            text = item.name,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
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
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddItemScreen(
    state: InventoryUiState,
    onCancel: () -> Unit,
    onChangeName: (String) -> Unit,
    onChangeParent: (String) -> Unit,
    onSave: () -> Unit,
) {
    val draft = requireNotNull(state.itemDraft)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_item)) },
                navigationIcon = {
                    TextButton(onClick = onCancel, enabled = !state.operationInProgress) {
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
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = onChangeName,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.operationInProgress,
                    singleLine = true,
                    label = { Text(stringResource(R.string.item_name)) },
                    supportingText = {
                        Text(draft.nameError ?: stringResource(R.string.item_name_supporting_text))
                    },
                    isError = draft.nameError != null,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.parent_item),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(
                items = state.inventory.allItems,
                key = Item::id,
            ) { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = !state.operationInProgress,
                            onClick = { onChangeParent(candidate.id) },
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = candidate.id == draft.parentItemId,
                        onClick = { onChangeParent(candidate.id) },
                        enabled = !state.operationInProgress,
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(candidate.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = state.inventory.pathTo(candidate.id)
                                .joinToString(" → ", transform = Item::name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            item {
                Button(
                    onClick = onSave,
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
