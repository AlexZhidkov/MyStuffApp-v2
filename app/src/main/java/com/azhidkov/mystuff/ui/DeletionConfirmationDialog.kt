package com.azhidkov.mystuff.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.azhidkov.mystuff.DeletionPreview
import com.azhidkov.mystuff.DeletionTarget
import com.azhidkov.mystuff.R
import com.azhidkov.mystuff.SessionOperation

@Composable
fun DeletionConfirmationDialog(
    preview: DeletionPreview,
    operation: SessionOperation?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var householdName by remember(preview) { mutableStateOf("") }
    val busy = operation == SessionOperation.Reauthenticating ||
        operation == SessionOperation.RequestingDeletion
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (preview.target == DeletionTarget.Account) {
                        R.string.delete_account_title
                    } else {
                        R.string.delete_household_title
                    },
                ),
            )
        },
        text = {
            Column {
                Text(deletionExplanation(preview))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.deletion_reauthentication_notice))
                if (preview.requiresHouseholdName) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = householdName,
                        onValueChange = { householdName = it },
                        enabled = !busy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.type_household_name)) },
                        supportingText = {
                            Text(stringResource(R.string.type_household_name_value, preview.householdName.orEmpty()))
                        },
                        isError = errorMessage != null,
                    )
                }
                errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(householdName) },
                enabled = !busy && (
                    !preview.requiresHouseholdName || householdName == preview.householdName
                ),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        stringResource(
                            if (preview.target == DeletionTarget.Account) {
                                R.string.delete_account
                            } else {
                                R.string.delete_household
                            },
                        ),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun deletionExplanation(preview: DeletionPreview): String {
    if (preview.target == DeletionTarget.Account && !preview.deletesHousehold) {
        return stringResource(R.string.delete_member_account_body)
    }
    val members = pluralStringResource(
        R.plurals.deletion_member_count,
        preview.memberCount,
        preview.memberCount,
    )
    val items = pluralStringResource(
        R.plurals.deletion_item_count,
        preview.itemCount,
        preview.itemCount,
    )
    return stringResource(
        if (preview.target == DeletionTarget.Account) {
            R.string.delete_owner_account_body
        } else {
            R.string.delete_household_body
        },
        preview.householdName.orEmpty(),
        members,
        items,
    )
}
