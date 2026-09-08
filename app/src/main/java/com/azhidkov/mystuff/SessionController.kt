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

    fun signOut(onResult: (Result<Unit>) -> Unit)
}

enum class AppDestination {
    SignIn,
    OpeningHousehold,
    HouseholdEntry,
    HouseholdRoot,
}

enum class SessionOperation {
    SigningIn,
    OpeningHousehold,
    JoiningHousehold,
    CreatingHousehold,
    SigningOut,
}

data class SessionUiState(
    val destination: AppDestination,
    val identity: AuthenticatedIdentity? = null,
    val household: Household? = null,
    val householdNameError: String? = null,
    val operation: SessionOperation? = null,
    val errorMessage: String? = null,
    val invitationErrorMessage: String? = null,
    val pendingInvitationId: String? = null,
)

class SessionController(
    private val authenticationGateway: AuthenticationGateway,
    private val householdGateway: HouseholdGateway = NoHouseholdGateway,
    private val invitationAcceptanceGateway: InvitationAcceptanceGateway =
        NoInvitationAcceptanceGateway,
    invitationId: String? = null,
    private val onIdentityChanged: (String?) -> Unit = {},
) {
    var state: SessionUiState = stateFor(authenticationGateway.currentIdentity, invitationId)
        private set

    var onStateChanged: (SessionUiState) -> Unit = {}

    private var stopObservingIdentity: () -> Unit = {}
    private var activeIdentityId = state.identity?.id

    init {
        onIdentityChanged(state.identity?.id)
        state.identity?.let(::resumeFor)
        stopObservingIdentity = authenticationGateway.observeIdentity(::authenticationChanged)
    }

    fun signIn() {
        if (state.operation != null) return

        updateState(
            state.copy(
                operation = SessionOperation.SigningIn,
                errorMessage = null,
            ),
        )
        authenticationGateway.signIn { result ->
            result.onSuccess { identity ->
                authenticationChanged(identity)
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

    fun close() {
        stopObservingIdentity()
        stopObservingIdentity = {}
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
                operation = SessionOperation.JoiningHousehold,
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
        if (state.operation != null) return
        acceptInvitation(identity, invitationId)
    }

    private fun openHouseholdFor(
        identity: AuthenticatedIdentity,
        pendingInvitationId: String? = null,
        invitationError: String? = null,
    ) {
        updateState(
            SessionUiState(
                destination = AppDestination.OpeningHousehold,
                identity = identity,
                operation = SessionOperation.OpeningHousehold,
                pendingInvitationId = pendingInvitationId,
                invitationErrorMessage = invitationError,
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
                        invitationErrorMessage = invitationError,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    SessionUiState(
                        destination = AppDestination.OpeningHousehold,
                        identity = identity,
                        errorMessage = failure.message?.takeIf(String::isNotBlank)
                            ?: "Please try again.",
                        invitationErrorMessage = invitationError,
                        pendingInvitationId = pendingInvitationId,
                    ),
                )
            }
        }
    }

    fun retryOpeningHousehold() {
        val identity = state.identity ?: return
        if (state.destination != AppDestination.OpeningHousehold || state.operation != null) return
        openHouseholdFor(
            identity = identity,
            pendingInvitationId = state.pendingInvitationId,
            invitationError = state.invitationErrorMessage,
        )
    }

    fun signOut() {
        if (state.operation != null) return

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
        if (identity == null) {
            updateState(
                SessionUiState(
                    destination = AppDestination.SignIn,
                    pendingInvitationId = state.pendingInvitationId,
                ),
            )
        } else {
            resumeFor(identity)
        }
    }

    private fun publishIdentity(identityId: String?): Boolean {
        if (activeIdentityId == identityId) return false
        activeIdentityId = identityId
        onIdentityChanged(identityId)
        return true
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
                    destination = AppDestination.OpeningHousehold,
                    identity = identity,
                    operation = SessionOperation.OpeningHousehold,
                    pendingInvitationId = invitationId,
                )
            }
    }
}
