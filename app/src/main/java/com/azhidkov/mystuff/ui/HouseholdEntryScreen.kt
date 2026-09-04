package com.azhidkov.mystuff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.azhidkov.mystuff.AuthenticatedIdentity
import com.azhidkov.mystuff.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdEntryScreen(
    identity: AuthenticatedIdentity,
    operationInProgress: Boolean,
    householdNameError: String?,
    errorMessage: String?,
    pendingInvitationId: String?,
    onCreateHousehold: (String) -> Unit,
    onRetryInvitationAcceptance: () -> Unit,
    onSignOut: () -> Unit,
) {
    var householdName by remember { mutableStateOf("") }

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
                    AppBarOverflowMenu(
                        enabled = !operationInProgress,
                        onSignOut = onSignOut,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .widthIn(max = 720.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(
                    R.string.welcome_person,
                    identity.displayName ?: identity.email ?: "",
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            if (pendingInvitationId != null) {
                Text(
                    text = stringResource(R.string.accept_invitation_title),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        if (operationInProgress) {
                            R.string.accepting_invitation
                        } else {
                            R.string.accept_invitation_body
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (errorMessage != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onRetryInvitationAcceptance,
                        enabled = !operationInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.retry_invitation))
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
            Text(
                text = stringResource(R.string.create_household_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.create_household_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = householdName,
                onValueChange = { householdName = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !operationInProgress,
                label = { Text(stringResource(R.string.household_name)) },
                supportingText = {
                    Text(
                        householdNameError
                            ?: stringResource(R.string.household_name_supporting_text),
                    )
                },
                isError = householdNameError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { onCreateHousehold(householdName) },
                ),
            )
            if (errorMessage != null && pendingInvitationId == null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onCreateHousehold(householdName) },
                enabled = !operationInProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (operationInProgress) {
                        LinearProgressIndicator()
                    }
                    Text(
                        stringResource(
                            if (operationInProgress) {
                                R.string.creating_household
                            } else {
                                R.string.create_household
                            },
                        ),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.accept_invitation_link_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = identity.email.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
