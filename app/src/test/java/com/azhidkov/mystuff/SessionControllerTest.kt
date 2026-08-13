package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionControllerTest {
    @Test
    fun `Member creates a Household from a trimmed name and opens its root Item`() {
        val identity = AuthenticatedIdentity(
            id = "member-1",
            displayName = "Alex",
            email = "alex@example.com",
        )
        val householdGateway = FakeHouseholdGateway()
        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity),
            householdGateway = householdGateway,
        )

        controller.createHousehold("  Our Home\n")

        assertEquals("Our Home", householdGateway.createdName)
        assertEquals(AppDestination.HouseholdRoot, controller.state.destination)
        assertEquals("Our Home", controller.state.household?.rootItem?.name)
        assertEquals(null, controller.state.household?.rootItem?.parentItemId)
        assertEquals(null, controller.state.household?.rootItem?.photoUrl)
        assertEquals(null, controller.state.household?.rootItem?.description)
        assertEquals(emptyList<String>(), controller.state.household?.rootItem?.tags)
    }

    @Test
    fun `Household name must contain 1 to 100 Unicode characters after trimming`() {
        val identity = AuthenticatedIdentity(
            id = "member-1",
            displayName = "Alex",
            email = "alex@example.com",
        )
        val householdGateway = FakeHouseholdGateway()
        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity),
            householdGateway = householdGateway,
        )

        controller.createHousehold(" \n\t ")

        assertEquals("Enter a Household name.", controller.state.householdNameError)
        assertEquals(0, householdGateway.createCalls)

        controller.createHousehold("a".repeat(101))

        assertEquals(
            "Household names can contain at most 100 characters.",
            controller.state.householdNameError,
        )
        assertEquals(0, householdGateway.createCalls)

        controller.createHousehold("🏠".repeat(100))

        assertEquals(1, householdGateway.createCalls)
        assertEquals("🏠".repeat(100), householdGateway.createdName)
    }

    @Test
    fun `returning Member reopens their Household root Item`() {
        val identity = AuthenticatedIdentity(
            id = "member-1",
            displayName = "Alex",
            email = "alex@example.com",
        )
        val household = Household(
            id = "household-1",
            ownerMemberId = identity.id,
            rootItem = Item(
                id = "household-1",
                name = "Our Home",
                parentItemId = null,
                photoUrl = null,
                description = null,
                tags = emptyList(),
            ),
        )

        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity),
            householdGateway = FakeHouseholdGateway(existingHousehold = household),
        )

        assertEquals(AppDestination.HouseholdRoot, controller.state.destination)
        assertEquals(household, controller.state.household)
    }

    @Test
    fun `Member who already belongs to a Household cannot create another`() {
        val identity = AuthenticatedIdentity(
            id = "member-1",
            displayName = "Alex",
            email = "alex@example.com",
        )
        val household = Household(
            id = "household-1",
            ownerMemberId = identity.id,
            rootItem = Item(
                id = "household-1",
                name = "Our Home",
                parentItemId = null,
                photoUrl = null,
                description = null,
                tags = emptyList(),
            ),
        )
        val householdGateway = FakeHouseholdGateway(existingHousehold = household)
        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity),
            householdGateway = householdGateway,
        )

        controller.createHousehold("Another Home")

        assertEquals(0, householdGateway.createCalls)
        assertEquals(household, controller.state.household)
    }

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

private class FakeHouseholdGateway(
    private val existingHousehold: Household? = null,
) : HouseholdGateway {
    var createCalls = 0
        private set
    var createdName: String? = null
        private set

    override fun findForMember(
        memberId: String,
        onResult: (Result<Household?>) -> Unit,
    ) {
        onResult(Result.success(existingHousehold))
    }

    override fun create(
        owner: AuthenticatedIdentity,
        name: String,
        onResult: (Result<Household>) -> Unit,
    ) {
        createCalls += 1
        createdName = name
        onResult(
            Result.success(
                Household(
                    id = "new-household",
                    ownerMemberId = owner.id,
                    rootItem = Item(
                        id = "new-household",
                        name = name,
                        parentItemId = null,
                        photoUrl = null,
                        description = null,
                        tags = emptyList(),
                    ),
                ),
            ),
        )
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
