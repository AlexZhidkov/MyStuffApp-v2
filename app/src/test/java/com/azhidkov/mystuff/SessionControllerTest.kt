package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionControllerTest {
    @Test
    fun `automatic access claim is part of Household opening`() {
        val identity = identity()
        val household = testHousehold(ownerMemberId = "owner-1")
        val gateway = FakeHouseholdGateway(existingHousehold = household)
        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity),
            householdGateway = gateway,
        )

        assertEquals(listOf(identity), gateway.lookedUpIdentities)
        assertEquals(AppDestination.HouseholdRoot, controller.state.destination)
        assertEquals(household, controller.state.household)
    }

    @Test
    fun `creation is unavailable while membership and automatic access are being confirmed`() {
        val gateway = FakeHouseholdGateway(completeLookupImmediately = false)
        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity()),
            householdGateway = gateway,
        )

        controller.createHousehold("Another Home")

        assertEquals(0, gateway.createCalls)
        gateway.completeLookup(Result.success(null))
        assertEquals(AppDestination.HouseholdEntry, controller.state.destination)
    }

    @Test
    fun `failed Household opening can be retried`() {
        val gateway = FakeHouseholdGateway(completeLookupImmediately = false)
        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity()),
            householdGateway = gateway,
        )

        gateway.completeLookup(Result.failure(IllegalStateException("Network unavailable")))
        assertEquals(AppDestination.OpeningHousehold, controller.state.destination)
        assertEquals("Network unavailable", controller.state.errorMessage)

        controller.retryOpeningHousehold()
        assertEquals(SessionOperation.OpeningHousehold, controller.state.operation)
        gateway.completeLookup(Result.success(null))
        assertEquals(AppDestination.HouseholdEntry, controller.state.destination)
    }

    @Test
    fun `Household name is trimmed and validated as Unicode characters`() {
        val gateway = FakeHouseholdGateway()
        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity()),
            householdGateway = gateway,
        )

        controller.createHousehold("  Our Home\n")
        assertEquals("Our Home", gateway.createdName)
        assertEquals(AppDestination.HouseholdRoot, controller.state.destination)

        val invalid = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity()),
            householdGateway = FakeHouseholdGateway(),
        )
        invalid.createHousehold(" ")
        assertEquals("Enter a Household name.", invalid.state.householdNameError)
    }

    @Test
    fun `sign out clears the active identity`() {
        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity()),
            householdGateway = FakeHouseholdGateway(),
        )

        controller.signOut()
        assertEquals(AppDestination.SignIn, controller.state.destination)
        assertEquals(null, controller.state.identity)
    }
}

private class FakeHouseholdGateway(
    private val existingHousehold: Household? = null,
    private val completeLookupImmediately: Boolean = true,
    private val completeCreateImmediately: Boolean = true,
) : HouseholdGateway {
    private var pendingLookup: ((Result<Household?>) -> Unit)? = null
    val lookedUpIdentities = mutableListOf<AuthenticatedIdentity>()
    var createCalls = 0
        private set
    var createdName: String? = null
        private set

    override fun findForMember(
        identity: AuthenticatedIdentity,
        onResult: (Result<Household?>) -> Unit,
    ) {
        lookedUpIdentities += identity
        if (completeLookupImmediately) onResult(Result.success(existingHousehold))
        else pendingLookup = onResult
    }

    fun completeLookup(result: Result<Household?>) {
        requireNotNull(pendingLookup).invoke(result)
        pendingLookup = null
    }

    override fun create(
        owner: AuthenticatedIdentity,
        name: String,
        onResult: (Result<Household>) -> Unit,
    ) {
        createCalls += 1
        createdName = name
        if (completeCreateImmediately) onResult(Result.success(testHousehold(ownerMemberId = owner.id)))
    }
}

private class FakeAuthenticationGateway(
    override var currentIdentity: AuthenticatedIdentity? = null,
) : AuthenticationGateway {
    override fun signIn(onResult: (Result<AuthenticatedIdentity>) -> Unit) = Unit
    override fun signOut(onResult: (Result<Unit>) -> Unit) = onResult(Result.success(Unit))
}

private fun identity() = AuthenticatedIdentity("member-1", "Alex", "alex@example.com")

private fun testHousehold(ownerMemberId: String) = Household(
    id = "household-1",
    ownerMemberId = ownerMemberId,
    rootItem = Item(
        id = "household-1",
        name = "Our Home",
        parentItemId = null,
        photoUrl = null,
        description = null,
        tags = emptyList(),
    ),
)
