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
        var cleaned = 0
        val controller = SessionController(
            authenticationGateway = FakeAuthenticationGateway(currentIdentity = identity()),
            householdGateway = FakeHouseholdGateway(),
            sessionDataCleaner = SessionDataCleaner { cleaned += 1 },
        )

        controller.signOut()
        assertEquals(AppDestination.SignIn, controller.state.destination)
        assertEquals(null, controller.state.identity)
        assertEquals(1, cleaned)
    }

    @Test
    fun `non-Owner Account Deletion requires reauthentication then clears local data`() {
        val authentication = FakeAuthenticationGateway(currentIdentity = identity())
        val deletion = FakeDeletionGateway(
            accountPreview = DeletionPreview(
                target = DeletionTarget.Account,
                deletesHousehold = false,
            ),
        )
        var cleaned = 0
        val controller = SessionController(
            authenticationGateway = authentication,
            householdGateway = FakeHouseholdGateway(
                existingHousehold = testHousehold(ownerMemberId = "owner-1"),
            ),
            deletionGateway = deletion,
            sessionDataCleaner = SessionDataCleaner { cleaned += 1 },
        )

        controller.beginAccountDeletion()
        controller.confirmDeletion("")

        assertEquals(1, authentication.reauthenticationCalls)
        assertEquals(1, deletion.accountRequests)
        assertEquals(1, cleaned)
        assertEquals(AppDestination.SignIn, controller.state.destination)
        assertEquals("Account deletion started. Your access has been removed.", controller.state.noticeMessage)
    }

    @Test
    fun `Owner must type the Household name before deleting their Account`() {
        val authentication = FakeAuthenticationGateway(currentIdentity = identity())
        val preview = DeletionPreview(
            target = DeletionTarget.Account,
            deletesHousehold = true,
            householdName = "Our Home",
            memberCount = 2,
            itemCount = 8,
        )
        val deletion = FakeDeletionGateway(accountPreview = preview)
        val controller = SessionController(
            authenticationGateway = authentication,
            householdGateway = FakeHouseholdGateway(existingHousehold = testHousehold("member-1")),
            deletionGateway = deletion,
        )

        controller.beginAccountDeletion()
        controller.confirmDeletion("our home")

        assertEquals(0, authentication.reauthenticationCalls)
        assertEquals("Type the Household name exactly.", controller.state.deletionErrorMessage)

        controller.confirmDeletion("Our Home")
        assertEquals(1, authentication.reauthenticationCalls)
        assertEquals(1, deletion.accountRequests)
        assertEquals("Our Home", deletion.accountConfirmationName)
    }

    @Test
    fun `standalone Household deletion keeps the Account and returns to creation`() {
        val authentication = FakeAuthenticationGateway(currentIdentity = identity())
        val household = testHousehold("member-1")
        val deletion = FakeDeletionGateway(
            householdPreview = DeletionPreview(
                target = DeletionTarget.Household,
                deletesHousehold = true,
                householdName = "Our Home",
                memberCount = 1,
                itemCount = 3,
            ),
        )
        val publishedIdentities = mutableListOf<String?>()
        val controller = SessionController(
            authenticationGateway = authentication,
            householdGateway = FakeHouseholdGateway(existingHousehold = household),
            deletionGateway = deletion,
            onIdentityChanged = publishedIdentities::add,
        )

        controller.beginHouseholdDeletion()
        controller.confirmDeletion("Our Home")

        assertEquals(1, deletion.householdRequests)
        assertEquals("Our Home", deletion.householdConfirmationName)
        assertEquals(AppDestination.HouseholdEntry, controller.state.destination)
        assertEquals(identity(), controller.state.identity)
        assertEquals("Household deletion started.", controller.state.noticeMessage)
        assertEquals(listOf("member-1", null, "member-1"), publishedIdentities)
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
    var reauthenticationCalls = 0
    override fun signIn(onResult: (Result<AuthenticatedIdentity>) -> Unit) = Unit
    override fun reauthenticate(onResult: (Result<Unit>) -> Unit) {
        reauthenticationCalls += 1
        onResult(Result.success(Unit))
    }
    override fun signOut(onResult: (Result<Unit>) -> Unit) = onResult(Result.success(Unit))
}

private class FakeDeletionGateway(
    private val accountPreview: DeletionPreview = DeletionPreview(
        target = DeletionTarget.Account,
        deletesHousehold = false,
    ),
    private val householdPreview: DeletionPreview = DeletionPreview(
        target = DeletionTarget.Household,
        deletesHousehold = true,
        householdName = "Our Home",
    ),
) : DeletionGateway {
    var accountRequests = 0
    var householdRequests = 0
    var accountConfirmationName: String? = null
    var householdConfirmationName: String? = null

    override fun previewAccount(onResult: (Result<DeletionPreview>) -> Unit) {
        onResult(Result.success(accountPreview))
    }

    override fun requestAccount(
        confirmationHouseholdName: String?,
        onResult: (Result<Unit>) -> Unit,
    ) {
        accountRequests += 1
        accountConfirmationName = confirmationHouseholdName
        onResult(Result.success(Unit))
    }

    override fun previewHousehold(
        householdId: String,
        onResult: (Result<DeletionPreview>) -> Unit,
    ) {
        onResult(Result.success(householdPreview))
    }

    override fun requestHousehold(
        householdId: String,
        confirmationHouseholdName: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        householdRequests += 1
        householdConfirmationName = confirmationHouseholdName
        onResult(Result.success(Unit))
    }
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
