package com.azhidkov.mystuff

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.azhidkov.mystuff.ui.HouseholdEntryScreen
import com.azhidkov.mystuff.ui.HouseholdRootScreen
import com.azhidkov.mystuff.ui.SignInScreen
import com.azhidkov.mystuff.ui.theme.MyStuffTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sessionController = SessionController(
            authenticationGateway = FirebaseAuthenticationGateway(this),
            householdGateway = FirebaseHouseholdGateway(),
        )
        val invitationGateway = FirebaseInvitationGateway()
        val inventoryGateway = FirebaseInventoryGateway()
        setContent {
            var sessionState by remember { mutableStateOf(sessionController.state) }
            DisposableEffect(sessionController) {
                sessionController.onStateChanged = { sessionState = it }
                sessionState = sessionController.state
                onDispose {
                    sessionController.onStateChanged = {}
                }
            }

            MyStuffTheme {
                MyStuffApp(
                    state = sessionState,
                    onSignIn = sessionController::signIn,
                    onSignOut = sessionController::signOut,
                    onCreateHousehold = sessionController::createHousehold,
                    invitationGateway = invitationGateway,
                    inventoryGateway = inventoryGateway,
                )
            }
        }
    }
}
@Composable
private fun MyStuffApp(
    state: SessionUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onCreateHousehold: (String) -> Unit,
    invitationGateway: InvitationGateway,
    inventoryGateway: InventoryGateway,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (state.destination) {
            AppDestination.SignIn -> SignInScreen(
                state = state,
                onSignIn = onSignIn,
            )

            AppDestination.HouseholdEntry -> HouseholdEntryScreen(
                identity = requireNotNull(state.identity),
                operationInProgress = state.operationInProgress,
                householdNameError = state.householdNameError,
                errorMessage = state.errorMessage,
                onCreateHousehold = onCreateHousehold,
                onSignOut = onSignOut,
            )

            AppDestination.HouseholdRoot -> {
                val household = requireNotNull(state.household)
                val identity = requireNotNull(state.identity)
                val invitationController = remember(household.id, identity.id) {
                    InvitationController(
                        household = household,
                        currentMemberId = identity.id,
                        gateway = invitationGateway,
                    )
                }
                val inventoryController = remember(household.id, identity.id) {
                    InventoryController(
                        household = household,
                        identity = identity,
                        gateway = inventoryGateway,
                    )
                }
                var inventoryState by remember(inventoryController) {
                    mutableStateOf(inventoryController.state)
                }
                var invitationState by remember(invitationController) {
                    mutableStateOf(invitationController.state)
                }
                DisposableEffect(invitationController) {
                    invitationController.onStateChanged = { invitationState = it }
                    invitationState = invitationController.state
                    onDispose { invitationController.onStateChanged = {} }
                }
                DisposableEffect(inventoryController) {
                    inventoryController.onStateChanged = { inventoryState = it }
                    inventoryState = inventoryController.state
                    onDispose { inventoryController.close() }
                }

                HouseholdRootScreen(
                    inventoryState = inventoryState,
                    invitationState = invitationState,
                    signOutInProgress = state.operationInProgress,
                    onCreateInvitation = invitationController::create,
                    onRevokeInvitation = invitationController::revoke,
                    onReplaceInvitation = { invitationId, email ->
                        invitationController.replace(invitationId, email)
                    },
                    inventoryActions = inventoryController,
                    onSignOut = onSignOut,
                )
            }
        }
    }
}
