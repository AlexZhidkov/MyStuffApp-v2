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
}

data class SessionUiState(
    val destination: AppDestination,
    val identity: AuthenticatedIdentity? = null,
    val operationInProgress: Boolean = false,
    val errorMessage: String? = null,
)

class SessionController(
    private val authenticationGateway: AuthenticationGateway,
) {
    var state: SessionUiState = stateFor(authenticationGateway.currentIdentity)
        private set

    var onStateChanged: (SessionUiState) -> Unit = {}

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
                updateState(stateFor(identity))
            }.onFailure { failure ->
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

        fun stateFor(identity: AuthenticatedIdentity?): SessionUiState =
            if (identity == null) {
                SessionUiState(destination = AppDestination.SignIn)
            } else {
                SessionUiState(
                    destination = AppDestination.HouseholdEntry,
                    identity = identity,
                )
            }
    }
}
