package com.azhidkov.mystuff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.azhidkov.mystuff.AppConnection
import com.azhidkov.mystuff.AppConnectionsController
import com.azhidkov.mystuff.AppConnectionsGateway
import com.azhidkov.mystuff.AppConnectionsUiState
import com.azhidkov.mystuff.R
import java.text.DateFormat
import java.util.Date

@Composable
fun ConnectedAppsRoute(
    memberId: String,
    gateway: AppConnectionsGateway,
    onClose: () -> Unit,
    onPrivacyPolicy: () -> Unit,
) {
    val controller = remember(memberId, gateway) { AppConnectionsController(gateway) }
    var state by remember(controller) { mutableStateOf(controller.state) }
    DisposableEffect(controller) {
        controller.onStateChanged = { state = it }
        controller.load()
        onDispose { controller.onStateChanged = {} }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ConnectedAppsScreen(state, controller::load, controller::revoke, onClose, onPrivacyPolicy)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectedAppsScreen(
    state: AppConnectionsUiState,
    onRefresh: () -> Unit,
    onRevoke: (String) -> Unit,
    onClose: () -> Unit,
    onPrivacyPolicy: () -> Unit,
) {
    var candidate by remember { mutableStateOf<AppConnection?>(null) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val busy = state.loading || state.revokingId != null
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connected_apps)) },
                navigationIcon = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !busy) { Text(stringResource(R.string.connected_apps_refresh)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.connected_apps_scope))
                Text(stringResource(R.string.connected_apps_revocation_limits), modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = onPrivacyPolicy) { Text(stringResource(R.string.privacy_policy)) }
            }
            if (state.loading) item { CircularProgressIndicator() }
            state.errorMessage?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRefresh, enabled = !busy) { Text(stringResource(R.string.connected_apps_refresh)) }
                }
            }
            state.noticeMessage?.let { message -> item { Text(message) } }
            if (state.loaded && state.connections.isEmpty() && !state.loading) {
                item { Text(stringResource(R.string.connected_apps_empty)) }
            }
            items(state.connections, key = AppConnection::id) { connection ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(connection.clientName, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.connected_apps_household, connection.householdName))
                        Text(stringResource(R.string.connected_apps_identifier, connection.id.takeLast(8)), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.connected_apps_approved, dateFormat.format(Date(connection.createdAt))), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.connected_apps_used, dateFormat.format(Date(connection.lastUsedAt))), style = MaterialTheme.typography.bodySmall)
                        if (connection.revokedAt != null) {
                            Text(stringResource(R.string.connected_apps_revoked))
                        } else {
                            Text(stringResource(R.string.connected_apps_read_only))
                            TextButton(onClick = { candidate = connection }, enabled = !busy) {
                                Text(stringResource(if (state.revokingId == connection.id) R.string.connected_apps_revoking else R.string.connected_apps_revoke))
                            }
                        }
                    }
                }
            }
        }
    }
    candidate?.let { connection ->
        AlertDialog(
            onDismissRequest = { candidate = null },
            title = { Text(stringResource(R.string.connected_apps_revoke_title, connection.clientName)) },
            text = { Text(stringResource(R.string.connected_apps_revoke_confirmation, connection.householdName)) },
            confirmButton = {
                TextButton(onClick = { candidate = null; onRevoke(connection.id) }) {
                    Text(stringResource(R.string.connected_apps_revoke))
                }
            },
            dismissButton = {
                TextButton(onClick = { candidate = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
