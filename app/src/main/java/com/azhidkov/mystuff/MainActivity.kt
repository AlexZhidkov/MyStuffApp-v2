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

            AppDestination.HouseholdRoot -> HouseholdRootScreen(
                household = requireNotNull(state.household),
                signOutInProgress = state.operationInProgress,
                onSignOut = onSignOut,
            )
        }
    }
}
