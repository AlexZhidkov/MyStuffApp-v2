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

enum class AppDestination { SignIn, OpeningHousehold, HouseholdEntry, HouseholdRoot }

enum class SessionOperation { SigningIn, OpeningHousehold, CreatingHousehold, SigningOut }

data class SessionUiState(
    val destination: AppDestination,
    val identity: AuthenticatedIdentity? = null,
    val household: Household? = null,
    val householdNameError: String? = null,
    val operation: SessionOperation? = null,
    val errorMessage: String? = null,
)

class SessionController(
    private val authenticationGateway: AuthenticationGateway,
    private val householdGateway: HouseholdGateway = NoHouseholdGateway,
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
