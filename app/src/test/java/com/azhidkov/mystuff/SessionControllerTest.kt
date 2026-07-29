package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionControllerTest {
    @Test
    fun `person without an authenticated Google account sees sign in`() {
        val controller = SessionController(FakeAuthenticationGateway())

        assertEquals(AppDestination.SignIn, controller.state.destination)
    }

    @Test
    fun `successful Google sign in opens Household entry`() {
        val identity = AuthenticatedIdentity(
            id = "person-1",
            displayName = "Alex",
            email = "alex@example.com",
        )
        val controller = SessionController(
            FakeAuthenticationGateway(signInResult = Result.success(identity)),
        )

        controller.signIn()

        assertEquals(AppDestination.HouseholdEntry, controller.state.destination)
        assertEquals(identity, controller.state.identity)
    }

    @Test
    fun `failed authentication returns to sign in with a retryable error`() {
        val gateway = FakeAuthenticationGateway(
            signInResult = Result.failure(IllegalStateException("Network unavailable")),
        )
        val controller = SessionController(gateway)

        controller.signIn()

        assertEquals(AppDestination.SignIn, controller.state.destination)
        assertEquals(false, controller.state.operationInProgress)
        assertEquals(
            "Couldn't sign in. Network unavailable",
            controller.state.errorMessage,
        )
        assertEquals(1, gateway.signOutCalls)
    }

    @Test
    fun `authentication can be retried after a failure`() {
        val identity = AuthenticatedIdentity(
            id = "person-1",
            displayName = "Alex",
            email = "alex@example.com",
        )
        val gateway = FakeAuthenticationGateway(
            signInResult = Result.failure(IllegalStateException("Network unavailable")),
        )
        val controller = SessionController(gateway)
        controller.signIn()

        gateway.signInResult = Result.success(identity)
        controller.signIn()

        assertEquals(AppDestination.HouseholdEntry, controller.state.destination)
        assertEquals(identity, controller.state.identity)
    }

    @Test
    fun `sign out removes Household access and returns to sign in`() {
        val identity = AuthenticatedIdentity(
            id = "person-1",
            displayName = "Alex",
            email = "alex@example.com",
        )
        val gateway = FakeAuthenticationGateway(currentIdentity = identity)
        val controller = SessionController(gateway)

        controller.signOut()

        assertEquals(AppDestination.SignIn, controller.state.destination)
        assertEquals(null, controller.state.identity)
        assertEquals(1, gateway.signOutCalls)
    }
}

private class FakeAuthenticationGateway(
    var signInResult: Result<AuthenticatedIdentity>? = null,
    override val currentIdentity: AuthenticatedIdentity? = null,
) : AuthenticationGateway {
    var signOutCalls = 0
        private set

    override fun signIn(onResult: (Result<AuthenticatedIdentity>) -> Unit) {
        signInResult?.let(onResult)
    }

    override fun signOut(onResult: (Result<Unit>) -> Unit) {
        signOutCalls += 1
        onResult(Result.success(Unit))
    }
}
