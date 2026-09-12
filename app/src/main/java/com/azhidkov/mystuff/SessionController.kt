package com.azhidkov.mystuff

data class AuthenticatedIdentity(
    val id: String,
    val displayName: String?,
    val email: String?,
)

interface AuthenticationGateway {
    val currentIdentity: AuthenticatedIdentity?

    fun observeIdentity(onChanged: (AuthenticatedIdentity?) -> Unit): () -> Unit {
        onChanged(currentIdentity)
        return {}
    }

    fun signIn(onResult: (Result<AuthenticatedIdentity>) -> Unit)
    fun reauthenticate(onResult: (Result<Unit>) -> Unit)
    fun signOut(onResult: (Result<Unit>) -> Unit)
}

enum class AppDestination { SignIn, OpeningHousehold, HouseholdEntry, HouseholdRoot }

enum class SessionOperation {
    SigningIn,
    OpeningHousehold,
    CreatingHousehold,
    SigningOut,
    PreparingDeletion,
    Reauthenticating,
    RequestingDeletion,
}

data class SessionUiState(
    val destination: AppDestination,
    val identity: AuthenticatedIdentity? = null,
    val household: Household? = null,
    val householdNameError: String? = null,
    val operation: SessionOperation? = null,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
    val deletionPreview: DeletionPreview? = null,
    val deletionErrorMessage: String? = null,
)

class SessionController(
    private val authenticationGateway: AuthenticationGateway,
    private val householdGateway: HouseholdGateway = NoHouseholdGateway,
    private val deletionGateway: DeletionGateway = NoDeletionGateway,
    private val sessionDataCleaner: SessionDataCleaner = NoSessionDataCleaner,
    private val onIdentityChanged: (String?) -> Unit = {},
) {
    var state: SessionUiState = stateFor(authenticationGateway.currentIdentity)
        private set

    var onStateChanged: (SessionUiState) -> Unit = {}

    private var stopObservingIdentity: () -> Unit = {}
    private var activeIdentityId = state.identity?.id

    init {
        onIdentityChanged(state.identity?.id)
        state.identity?.let(::openHouseholdFor)
        stopObservingIdentity = authenticationGateway.observeIdentity(::authenticationChanged)
    }

    fun signIn() {
        if (state.operation != null) return
        updateState(state.copy(operation = SessionOperation.SigningIn, errorMessage = null))
        authenticationGateway.signIn { result ->
            result.onSuccess(::authenticationChanged).onFailure { failure ->
                authenticationGateway.signOut {
                    updateState(
                        SessionUiState(
                            destination = AppDestination.SignIn,
                            errorMessage = buildSignInError(failure),
                        ),
                    )
                }
            }
        }
    }

    fun close() {
        stopObservingIdentity()
        stopObservingIdentity = {}
    }

    private fun openHouseholdFor(identity: AuthenticatedIdentity) {
        updateState(
            SessionUiState(
                destination = AppDestination.OpeningHousehold,
                identity = identity,
                operation = SessionOperation.OpeningHousehold,
            ),
        )
        householdGateway.findForMember(identity) { result ->
            result.onSuccess { household ->
                updateState(
                    SessionUiState(
                        destination = if (household == null) {
                            AppDestination.HouseholdEntry
                        } else {
                            AppDestination.HouseholdRoot
                        },
                        identity = identity,
                        household = household,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    SessionUiState(
                        destination = AppDestination.OpeningHousehold,
                        identity = identity,
                        errorMessage = failure.message?.takeIf(String::isNotBlank)
                            ?: "Please try again.",
                    ),
                )
            }
        }
    }

    fun retryOpeningHousehold() {
        val identity = state.identity ?: return
        if (state.destination != AppDestination.OpeningHousehold || state.operation != null) return
        openHouseholdFor(identity)
    }

    fun signOut() {
        if (state.operation != null) return
        sessionDataCleaner.clear()
        publishIdentity(null)
        updateState(
            SessionUiState(
                destination = AppDestination.SignIn,
                operation = SessionOperation.SigningOut,
            ),
        )
        authenticationGateway.signOut { result ->
            updateState(
                SessionUiState(
                    destination = AppDestination.SignIn,
                    errorMessage = result.exceptionOrNull()?.let(::buildSignOutError),
                ),
            )
        }
    }

    fun beginAccountDeletion() {
        if (state.identity == null || state.operation != null) return
        updateState(state.copy(operation = SessionOperation.PreparingDeletion, errorMessage = null))
        deletionGateway.previewAccount { result ->
            result.onSuccess { preview ->
                updateState(
                    state.copy(
                        operation = null,
                        deletionPreview = preview,
                        deletionErrorMessage = null,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operation = null,
                        errorMessage = deletionMessage("Account deletion could not be prepared.", failure),
                    ),
                )
            }
        }
    }

    fun beginHouseholdDeletion() {
        val identity = state.identity ?: return
        val household = state.household ?: return
        if (state.operation != null || household.ownerMemberId != identity.id) return
        updateState(state.copy(operation = SessionOperation.PreparingDeletion, errorMessage = null))
        deletionGateway.previewHousehold(household.id) { result ->
            result.onSuccess { preview ->
                updateState(
                    state.copy(
                        operation = null,
                        deletionPreview = preview,
                        deletionErrorMessage = null,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operation = null,
                        errorMessage = deletionMessage("Household deletion could not be prepared.", failure),
                    ),
                )
            }
        }
    }

    fun cancelDeletion() {
        if (state.operation != null) return
        updateState(state.copy(deletionPreview = null, deletionErrorMessage = null))
    }

    fun confirmDeletion(rawHouseholdName: String) {
        val preview = state.deletionPreview ?: return
        if (state.operation != null) return
        if (preview.requiresHouseholdName && rawHouseholdName != preview.householdName) {
            updateState(state.copy(deletionErrorMessage = "Type the Household name exactly."))
            return
        }
        updateState(
            state.copy(
                operation = SessionOperation.Reauthenticating,
                deletionErrorMessage = null,
            ),
        )
        authenticationGateway.reauthenticate { result ->
            result.onSuccess { requestDeletion(preview, rawHouseholdName) }
                .onFailure { failure ->
                    updateState(
                        state.copy(
                            operation = null,
                            deletionErrorMessage = deletionMessage(
                                "Google sign-in could not be confirmed.",
                                failure,
                            ),
                        ),
                    )
                }
        }
    }

    private fun requestDeletion(preview: DeletionPreview, confirmationHouseholdName: String) {
        updateState(state.copy(operation = SessionOperation.RequestingDeletion))
        val onResult: (Result<Unit>) -> Unit = { result ->
            result.onSuccess {
                when (preview.target) {
                    DeletionTarget.Account -> finishAccountDeletionRequest()
                    DeletionTarget.Household -> finishHouseholdDeletionRequest()
                }
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operation = null,
                        deletionErrorMessage = deletionMessage(
                            "Deletion could not be started.",
                            failure,
                        ),
                    ),
                )
            }
        }
        when (preview.target) {
            DeletionTarget.Account -> deletionGateway.requestAccount(
                confirmationHouseholdName.takeIf { preview.deletesHousehold },
                onResult,
            )
            DeletionTarget.Household -> {
                val householdId = state.household?.id
                if (householdId == null) {
                    onResult(Result.failure(IllegalStateException("The Household is no longer open.")))
                } else {
                    deletionGateway.requestHousehold(
                        householdId,
                        confirmationHouseholdName,
                        onResult,
                    )
                }
            }
        }
    }

    private fun finishAccountDeletionRequest() {
        sessionDataCleaner.clear()
        publishIdentity(null)
        updateState(
            SessionUiState(
                destination = AppDestination.SignIn,
                operation = SessionOperation.SigningOut,
            ),
        )
        authenticationGateway.signOut {
            updateState(
                SessionUiState(
                    destination = AppDestination.SignIn,
                    noticeMessage = "Account deletion started. Your access has been removed.",
                ),
            )
        }
    }

    private fun finishHouseholdDeletionRequest() {
        val identity = state.identity ?: return
        sessionDataCleaner.clear()
        onIdentityChanged(null)
        onIdentityChanged(identity.id)
        updateState(
            SessionUiState(
                destination = AppDestination.HouseholdEntry,
                identity = identity,
                noticeMessage = "Household deletion started.",
            ),
        )
    }

    fun createHousehold(rawName: String) {
        val identity = state.identity ?: return
        if (
            state.operation != null ||
            state.household != null ||
            state.destination != AppDestination.HouseholdEntry
        ) return

        val name = rawName.trim(Char::isWhitespace)
        val nameError = when {
            name.isEmpty() -> "Enter a Household name."
            name.codePointCount(0, name.length) > 100 ->
                "Household names can contain at most 100 characters."
            else -> null
        }
        if (nameError != null) {
            updateState(state.copy(householdNameError = nameError))
            return
        }

        updateState(
            state.copy(
                operation = SessionOperation.CreatingHousehold,
                errorMessage = null,
                householdNameError = null,
            ),
        )
        householdGateway.create(identity, name) { result ->
            result.onSuccess { household ->
                updateState(
                    SessionUiState(
                        destination = AppDestination.HouseholdRoot,
                        identity = identity,
                        household = household,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operation = null,
                        errorMessage = failure.message ?: "Couldn't create your Household.",
                    ),
                )
            }
        }
    }

    private fun updateState(newState: SessionUiState) {
        state = newState
        onStateChanged(newState)
    }

    private fun authenticationChanged(identity: AuthenticatedIdentity?) {
        if (!publishIdentity(identity?.id)) return
        if (identity == null) updateState(SessionUiState(destination = AppDestination.SignIn))
        else openHouseholdFor(identity)
    }

    private fun publishIdentity(identityId: String?): Boolean {
        if (activeIdentityId == identityId) return false
        activeIdentityId = identityId
        onIdentityChanged(identityId)
        return true
    }

    private companion object {
        fun deletionMessage(prefix: String, failure: Throwable): String =
            "$prefix ${failure.message?.takeIf(String::isNotBlank) ?: "Please try again."}"

        fun buildSignInError(failure: Throwable): String =
            "Couldn't sign in. ${failure.message?.takeIf(String::isNotBlank) ?: "Please try again."}"

        fun buildSignOutError(failure: Throwable): String =
            "Signed out of MyStuff. Couldn't clear the Google session. " +
                (failure.message?.takeIf(String::isNotBlank) ?: "Please try again.")

        fun stateFor(identity: AuthenticatedIdentity?): SessionUiState =
            if (identity == null) SessionUiState(destination = AppDestination.SignIn)
            else SessionUiState(
                destination = AppDestination.OpeningHousehold,
                identity = identity,
                operation = SessionOperation.OpeningHousehold,
            )
    }
}
