package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionControllerTest {
    @Test
    fun `creation is unavailable while signed-in membership is being confirmed`() {
        val identity = AuthenticatedIdentity(
            id = "member-1",
            displayName = "Alex",
            email = "alex@example.com",
        )
        val householdGateway = FakeHouseholdGateway(completeLookupImmediately = false)
        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity),
            householdGateway = householdGateway,
        )

        assertEquals(AppDestination.OpeningHousehold, controller.state.destination)
        assertEquals(SessionOperation.OpeningHousehold, controller.state.operation)

        controller.createHousehold("Another Home")

        assertEquals(0, householdGateway.createCalls)

        householdGateway.completeLookup(Result.success(null))

        assertEquals(AppDestination.HouseholdEntry, controller.state.destination)
        assertEquals(null, controller.state.operation)
    }

    @Test
    fun `failed Household opening stays separate from creation and can be retried`() {
        val identity = AuthenticatedIdentity("member-1", "Alex", "alex@example.com")
        val household = testHousehold(ownerMemberId = identity.id)
        val householdGateway = FakeHouseholdGateway(completeLookupImmediately = false)
        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity),
            householdGateway = householdGateway,
        )

        householdGateway.completeLookup(Result.failure(IllegalStateException("Network unavailable")))

        assertEquals(AppDestination.OpeningHousehold, controller.state.destination)
        assertEquals(null, controller.state.operation)
        assertEquals("Network unavailable", controller.state.errorMessage)

        controller.createHousehold("Another Home")

        assertEquals(0, householdGateway.createCalls)

        controller.retryOpeningHousehold()

        assertEquals(SessionOperation.OpeningHousehold, controller.state.operation)

        householdGateway.completeLookup(Result.success(household))

        assertEquals(AppDestination.HouseholdRoot, controller.state.destination)
        assertEquals(household, controller.state.household)
    }

    @Test
    fun `session actions expose only their operation-specific progress state`() {
        val identity = AuthenticatedIdentity("member-1", "Alex", "alex@example.com")

        val signingIn = SessionController(FakeAuthenticationGateway())
        signingIn.signIn()
        assertEquals(SessionOperation.SigningIn, signingIn.state.operation)

        val opening = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity),
            householdGateway = FakeHouseholdGateway(completeLookupImmediately = false),
        )
        assertEquals(SessionOperation.OpeningHousehold, opening.state.operation)

        val joining = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity),
            invitationAcceptanceGateway = FakeInvitationAcceptanceGateway(
                completeImmediately = false,
            ),
            invitationId = "invitation-1",
        )
        assertEquals(SessionOperation.JoiningHousehold, joining.state.operation)

        val creatingGateway = FakeHouseholdGateway(completeCreateImmediately = false)
        val creating = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity),
            householdGateway = creatingGateway,
        )
        creating.createHousehold("Our Home")
        assertEquals(SessionOperation.CreatingHousehold, creating.state.operation)

        val signOutGateway = FakeAuthenticationGateway(
            currentIdentity = identity,
            completeSignOutImmediately = false,
        )
        val signingOut = SessionController(
            authenticationGateway = signOutGateway,
            householdGateway = FakeHouseholdGateway(),
        )
        signingOut.signOut()
        assertEquals(SessionOperation.SigningOut, signingOut.state.operation)
    }

    @Test
    fun `opening an invitation signs in then accepts it and opens the shared Household`() {
        val identity = AuthenticatedIdentity(
            id = "member-2",
            displayName = "Sam",
            email = "sam@example.com",
        )
        val household = testHousehold(ownerMemberId = "member-1")
        val acceptanceGateway = FakeInvitationAcceptanceGateway()
        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(
                signInResult = Result.success(identity),
            ),
            householdGateway = FakeHouseholdGateway(existingHousehold = household),
            invitationAcceptanceGateway = acceptanceGateway,
            invitationId = "invitation-1",
        )

        assertEquals(AppDestination.SignIn, controller.state.destination)
        assertEquals("invitation-1", controller.state.pendingInvitationId)

        controller.signIn()

        assertEquals("invitation-1", acceptanceGateway.acceptedInvitationId)
        assertEquals(AppDestination.HouseholdRoot, controller.state.destination)
        assertEquals(household, controller.state.household)
        assertEquals(null, controller.state.pendingInvitationId)
    }

    @Test
    fun `a rejected invitation shows its clear outcome after Google sign in`() {
        val identity = AuthenticatedIdentity(
            id = "member-2",
            displayName = "Sam",
            email = "wrong@example.com",
        )
        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(
                signInResult = Result.success(identity),
            ),
            householdGateway = FakeHouseholdGateway(),
            invitationAcceptanceGateway = FakeInvitationAcceptanceGateway(
                result = Result.failure(
                    IllegalStateException(
                        "This invitation was sent to a different Google Account.",
                    ),
                ),
            ),
            invitationId = "invitation-1",
        )

        controller.signIn()

        assertEquals(AppDestination.HouseholdEntry, controller.state.destination)
        assertEquals(
            "This invitation was sent to a different Google Account.",
            controller.state.invitationErrorMessage,
        )
        assertEquals("invitation-1", controller.state.pendingInvitationId)
    }

    @Test
    fun `a Member of another Household remains in their current Household after rejection`() {
        val identity = AuthenticatedIdentity(
            id = "member-2",
            displayName = "Sam",
            email = "sam@example.com",
        )
        val currentHousehold = testHousehold(
            id = "household-2",
            ownerMemberId = "member-3",
        )

        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity),
            householdGateway = FakeHouseholdGateway(existingHousehold = currentHousehold),
            invitationAcceptanceGateway = FakeInvitationAcceptanceGateway(
                result = Result.failure(
                    IllegalStateException("You already belong to a Household."),
                ),
            ),
            invitationId = "invitation-1",
        )

        assertEquals(AppDestination.HouseholdRoot, controller.state.destination)
        assertEquals(currentHousehold, controller.state.household)
        assertEquals(
            "You already belong to a Household.",
            controller.state.invitationErrorMessage,
        )
    }

    @Test
    fun `only a MyStuff invitation link yields an invitation ID`() {
        assertEquals(
            "invitation-1",
            invitationIdFromLink("mystuff://invitation/invitation-1"),
        )
        assertEquals(null, invitationIdFromLink("https://example.com/invitation-1"))
        assertEquals(null, invitationIdFromLink("mystuff://invitation/"))
        assertEquals(null, invitationIdFromLink("mystuff://other/invitation-1"))
    }

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
        assertEquals(null, controller.state.operation)
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
        val observedIdentityIds = mutableListOf<String?>()
        val controller = SessionController(
            authenticationGateway = gateway,
            onIdentityChanged = observedIdentityIds::add,
        )

        controller.signOut()

        assertEquals(AppDestination.SignIn, controller.state.destination)
        assertEquals(null, controller.state.identity)
        assertEquals(1, gateway.signOutCalls)
        assertEquals(listOf("person-1", null), observedIdentityIds)
    }

    @Test
    fun `authentication loss leaves the Household and reaches session cleanup`() {
        val identity = AuthenticatedIdentity("person-1", "Alex", "alex@example.com")
        val gateway = FakeAuthenticationGateway(currentIdentity = identity)
        val observedIdentityIds = mutableListOf<String?>()
        val controller = SessionController(
            authenticationGateway = gateway,
            onIdentityChanged = observedIdentityIds::add,
        )

        gateway.emitIdentity(null)

        assertEquals(AppDestination.SignIn, controller.state.destination)
        assertEquals(null, controller.state.identity)
        assertEquals(listOf("person-1", null), observedIdentityIds)
    }

    @Test
    fun `authentication identity replacement reaches session cleanup before reopening`() {
        val first = AuthenticatedIdentity("person-1", "Alex", "alex@example.com")
        val second = AuthenticatedIdentity("person-2", "Sam", "sam@example.com")
        val gateway = FakeAuthenticationGateway(currentIdentity = first)
        val observedIdentityIds = mutableListOf<String?>()
        val controller = SessionController(
            authenticationGateway = gateway,
            onIdentityChanged = observedIdentityIds::add,
        )

        gateway.emitIdentity(second)

        assertEquals(second, controller.state.identity)
        assertEquals(listOf("person-1", "person-2"), observedIdentityIds)
    }
}

private class FakeInvitationAcceptanceGateway(
    private val result: Result<String> = Result.success("household-1"),
    private val completeImmediately: Boolean = true,
) : InvitationAcceptanceGateway {
    var acceptedInvitationId: String? = null
        private set

    override fun accept(
        invitationId: String,
        onResult: (Result<String>) -> Unit,
    ) {
        acceptedInvitationId = invitationId
        if (completeImmediately) onResult(result)
    }
}

private class FakeHouseholdGateway(
    private val existingHousehold: Household? = null,
    private val completeLookupImmediately: Boolean = true,
    private val completeCreateImmediately: Boolean = true,
) : HouseholdGateway {
    private var pendingLookup: ((Result<Household?>) -> Unit)? = null
    var createCalls = 0
        private set
    var createdName: String? = null
        private set

    override fun findForMember(
        memberId: String,
        onResult: (Result<Household?>) -> Unit,
    ) {
        if (completeLookupImmediately) {
            onResult(Result.success(existingHousehold))
        } else {
            pendingLookup = onResult
        }
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
        if (completeCreateImmediately) {
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
}

private class FakeAuthenticationGateway(
    var signInResult: Result<AuthenticatedIdentity>? = null,
    override var currentIdentity: AuthenticatedIdentity? = null,
    private val completeSignOutImmediately: Boolean = true,
) : AuthenticationGateway {
    private var identityObserver: ((AuthenticatedIdentity?) -> Unit)? = null
    var signOutCalls = 0
        private set

    override fun signIn(onResult: (Result<AuthenticatedIdentity>) -> Unit) {
        signInResult?.let(onResult)
    }

    override fun signOut(onResult: (Result<Unit>) -> Unit) {
        signOutCalls += 1
        if (completeSignOutImmediately) onResult(Result.success(Unit))
    }

    override fun observeIdentity(onChanged: (AuthenticatedIdentity?) -> Unit): () -> Unit {
        identityObserver = onChanged
        onChanged(currentIdentity)
        return { identityObserver = null }
    }

    fun emitIdentity(identity: AuthenticatedIdentity?) {
        currentIdentity = identity
        identityObserver?.invoke(identity)
    }
}

private fun testHousehold(
    id: String = "household-1",
    ownerMemberId: String,
) = Household(
    id = id,
    ownerMemberId = ownerMemberId,
    rootItem = Item(
        id = id,
        name = "Our Home",
        parentItemId = null,
        photoUrl = null,
        description = null,
        tags = emptyList(),
    ),
)
