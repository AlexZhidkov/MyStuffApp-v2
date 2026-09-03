package com.azhidkov.mystuff

data class AuthenticatedIdentity(
    val id: String,
    val displayName: String?,
    val email: String?,
)

interface AuthenticationGateway {
    val currentIdentity: AuthenticatedIdentity?

    fun signIn(onResult: (Result<AuthenticatedIdentity>) -> Unit)

    fun signOut(onResult: (Result<Unit>) -> Unit)
}

enum class AppDestination {
    SignIn,
    HouseholdEntry,
    HouseholdRoot,
}

data class SessionUiState(
    val destination: AppDestination,
    val identity: AuthenticatedIdentity? = null,
    val household: Household? = null,
    val householdNameError: String? = null,
    val operationInProgress: Boolean = false,
    val errorMessage: String? = null,
    val pendingInvitationId: String? = null,
)

class SessionController(
    private val authenticationGateway: AuthenticationGateway,
    private val householdGateway: HouseholdGateway = NoHouseholdGateway,
    private val invitationAcceptanceGateway: InvitationAcceptanceGateway =
        NoInvitationAcceptanceGateway,
    invitationId: String? = null,
) {
    var state: SessionUiState = stateFor(authenticationGateway.currentIdentity, invitationId)
        private set

    var onStateChanged: (SessionUiState) -> Unit = {}

    init {
        state.identity?.let(::resumeFor)
    }

    fun signIn() {
        if (state.operationInProgress) return

        updateState(
            state.copy(
                operationInProgress = true,
                errorMessage = null,
            ),
        )
        authenticationGateway.signIn { result ->
            result.onSuccess { identity ->
                resumeFor(identity)
            }.onFailure { failure ->
                val pendingInvitationId = state.pendingInvitationId
                authenticationGateway.signOut {
                    updateState(
                        SessionUiState(
                            destination = AppDestination.SignIn,
                            errorMessage = buildSignInError(failure),
                            pendingInvitationId = pendingInvitationId,
                        ),
                    )
                }
            }
        }
    }

    private fun resumeFor(identity: AuthenticatedIdentity) {
        val invitationId = state.pendingInvitationId
        if (invitationId == null) {
            openHouseholdFor(identity)
        } else {
            acceptInvitation(identity, invitationId)
        }
    }

    private fun acceptInvitation(
        identity: AuthenticatedIdentity,
        invitationId: String,
    ) {
        updateState(
            SessionUiState(
                destination = AppDestination.HouseholdEntry,
                identity = identity,
                operationInProgress = true,
                pendingInvitationId = invitationId,
            ),
        )
        invitationAcceptanceGateway.accept(invitationId) { result ->
            result.onSuccess {
                openHouseholdFor(identity)
            }.onFailure { failure ->
                openHouseholdFor(
                    identity = identity,
                    pendingInvitationId = invitationId,
                    invitationError = failure.message
                        ?: "The invitation could not be accepted.",
                )
            }
        }
    }

    fun retryInvitationAcceptance() {
        val identity = state.identity ?: return
        val invitationId = state.pendingInvitationId ?: return
        if (state.operationInProgress) return
        acceptInvitation(identity, invitationId)
    }

    private fun openHouseholdFor(
        identity: AuthenticatedIdentity,
        pendingInvitationId: String? = null,
        invitationError: String? = null,
    ) {
        updateState(
            SessionUiState(
                destination = AppDestination.HouseholdEntry,
                identity = identity,
                operationInProgress = true,
                pendingInvitationId = pendingInvitationId,
                errorMessage = invitationError,
            ),
        )
        householdGateway.findForMember(identity.id) { result ->
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
                        pendingInvitationId = pendingInvitationId,
                        errorMessage = invitationError,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    SessionUiState(
                        destination = AppDestination.HouseholdEntry,
                        identity = identity,
                        errorMessage = invitationError
                            ?: failure.message
                            ?: "Couldn't open your Household.",
                        pendingInvitationId = pendingInvitationId,
                    ),
                )
            }
        }
    }

    fun signOut() {
        if (state.operationInProgress) return

        updateState(
            SessionUiState(
                destination = AppDestination.SignIn,
                operationInProgress = true,
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

    fun createHousehold(rawName: String) {
        val identity = state.identity ?: return
        if (state.operationInProgress || state.household != null) return

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
                operationInProgress = true,
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
                        operationInProgress = false,
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

    private companion object {
        fun buildSignInError(failure: Throwable): String {
            val detail = failure.message?.takeIf(String::isNotBlank)
                ?: "Please try again."
            return "Couldn't sign in. $detail"
        }

        fun buildSignOutError(failure: Throwable): String {
            val detail = failure.message?.takeIf(String::isNotBlank)
                ?: "Please try again."
            return "Signed out of MyStuff. Couldn't clear the Google session. $detail"
        }

        fun stateFor(
            identity: AuthenticatedIdentity?,
            invitationId: String?,
        ): SessionUiState =
            if (identity == null) {
                SessionUiState(
                    destination = AppDestination.SignIn,
                    pendingInvitationId = invitationId,
                )
            } else {
                SessionUiState(
                    destination = AppDestination.HouseholdEntry,
                    identity = identity,
                    pendingInvitationId = invitationId,
                )
            }
    }
}
