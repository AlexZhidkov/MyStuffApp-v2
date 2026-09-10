package com.azhidkov.mystuff

import android.graphics.Bitmap
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
import androidx.lifecycle.ViewModelProvider
import com.azhidkov.mystuff.ui.HouseholdEntryScreen
import com.azhidkov.mystuff.ui.HouseholdRootScreen
import com.azhidkov.mystuff.ui.ItemPhotoLoader
import com.azhidkov.mystuff.ui.OpeningHouseholdScreen
import com.azhidkov.mystuff.ui.SignInScreen
import com.azhidkov.mystuff.ui.theme.MyStuffTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var sessionViewModel: SessionViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sessionViewModel = ViewModelProvider(
            this,
            SessionViewModel.Factory(
                applicationContext = applicationContext,
            ),
        )[SessionViewModel::class.java]
        sessionViewModel.attach(this)
        val sessionController = sessionViewModel.controller
        val itemPhotoLoader = sessionViewModel.itemPhotoLoader
        val householdAccessGateway = sessionViewModel.householdAccessGateway
        val inventoryGateway = FirebaseInventoryGateway()
        val itemAttachmentGateway = FirebaseItemAttachmentGateway()
        val searchGateway = FirebaseSearchGateway()
        val descriptionGenerationWork = WorkManagerInventoryDescriptionGenerationWork(
            applicationContext,
        )
        val rootChildItemCache = FileRootChildItemCache(
            File(cacheDir, ROOT_CHILD_ITEM_CACHE_DIRECTORY),
        )
        setContent {
            var sessionState by remember { mutableStateOf(sessionController.state) }
            DisposableEffect(sessionController) {
                sessionController.onStateChanged = {
                    sessionState = it
                }
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
                    onRetryOpeningHousehold = sessionController::retryOpeningHousehold,
                    householdAccessGateway = householdAccessGateway,
                    inventoryGateway = inventoryGateway,
                    itemAttachmentGateway = itemAttachmentGateway,
                    searchGateway = searchGateway,
                    rootChildItemCache = rootChildItemCache,
                    descriptionGenerationWork = descriptionGenerationWork,
                    itemPhotoLoader = itemPhotoLoader,
                )
            }
        }
    }

    override fun onDestroy() {
        sessionViewModel.detach(this)
        super.onDestroy()
    }
}
@Composable
private fun MyStuffApp(
    state: SessionUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onCreateHousehold: (String) -> Unit,
    onRetryOpeningHousehold: () -> Unit,
    householdAccessGateway: HouseholdAccessGateway,
    inventoryGateway: InventoryGateway,
    itemAttachmentGateway: ItemAttachmentGateway,
    searchGateway: SearchGateway,
    rootChildItemCache: RootChildItemCache,
    descriptionGenerationWork: InventoryDescriptionGenerationWork,
    itemPhotoLoader: ItemPhotoLoader<Bitmap>,
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

            AppDestination.OpeningHousehold -> OpeningHouseholdScreen(
                opening = state.operation == SessionOperation.OpeningHousehold,
                errorMessage = state.errorMessage,
                onRetry = onRetryOpeningHousehold,
                onSignOut = onSignOut,
            )

            AppDestination.HouseholdEntry -> HouseholdEntryScreen(
                identity = requireNotNull(state.identity),
                operation = state.operation,
                householdNameError = state.householdNameError,
                errorMessage = state.errorMessage,
                onCreateHousehold = onCreateHousehold,
                onSignOut = onSignOut,
            )

            AppDestination.HouseholdRoot -> {
                val household = requireNotNull(state.household)
                val identity = requireNotNull(state.identity)
                val householdAccessController = remember(household.id, identity.id) {
                    HouseholdAccessController(
                        household = household,
                        currentIdentity = identity,
                        gateway = householdAccessGateway,
                    )
                }
                val inventoryController = remember(household.id, identity.id) {
                    InventoryController(
                        household = household,
                        identity = identity,
                        gateway = inventoryGateway,
                        rootChildItemCache = rootChildItemCache,
                        descriptionGenerationWork = descriptionGenerationWork,
                        searchGateway = searchGateway,
                        searchDebouncer = MainThreadSearchDebouncer(),
                        itemAttachmentGateway = itemAttachmentGateway,
                        onInventoryChanged = itemPhotoLoader::onInventoryChanged,
                    )
                }
                var inventoryState by remember(inventoryController) {
                    mutableStateOf(inventoryController.state)
                }
                var householdAccessState by remember(householdAccessController) {
                    mutableStateOf(householdAccessController.state)
                }
                DisposableEffect(householdAccessController) {
                    householdAccessController.onStateChanged = { householdAccessState = it }
                    householdAccessState = householdAccessController.state
                    onDispose { householdAccessController.onStateChanged = {} }
                }
                DisposableEffect(inventoryController) {
                    inventoryController.onStateChanged = { inventoryState = it }
                    inventoryState = inventoryController.state
                    onDispose { inventoryController.close() }
                }

                HouseholdRootScreen(
                    inventoryState = inventoryState,
                    householdAccessState = householdAccessState,
                    currentIdentity = identity,
                    signOutInProgress = state.operation == SessionOperation.SigningOut,
                    sessionMessage = state.errorMessage,
                    onAddHouseholdAccess = householdAccessController::add,
                    onRemoveHouseholdAccess = householdAccessController::remove,
                    inventoryActions = inventoryController,
                    onSignOut = onSignOut,
                )
            }
        }
    }
}

private const val ROOT_CHILD_ITEM_CACHE_DIRECTORY = "root-child-items"
