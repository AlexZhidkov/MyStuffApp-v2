package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Test

class HouseholdAccessControllerTest {
    @Test
    fun `Owner adds a trimmed case insensitive email and sees it first as unclaimed`() {
        val gateway = FakeAccessGateway()
        val controller = HouseholdAccessController(household(), owner(), gateway)

        controller.add("  Sam@Example.com ")

        assertEquals("sam@example.com", gateway.addedEmail)
        assertEquals("sam@example.com", controller.state.access.single().email)
        assertEquals(false, controller.state.access.single().isClaimed)
    }

    @Test
    fun `Owner cannot add their own or duplicate email`() {
        val existing = access("sam@example.com")
        val gateway = FakeAccessGateway(listOf(existing))
        val controller = HouseholdAccessController(household(), owner(), gateway)

        controller.add("OWNER@EXAMPLE.COM")
        assertEquals("The Household Owner is already a Member.", controller.state.emailError)
        controller.add(" SAM@example.com ")
        assertEquals("That Google email already has Household Access.", controller.state.emailError)
        assertEquals(null, gateway.addedEmail)
    }

    @Test
    fun `non Owner cannot load add or remove Household Access`() {
        val gateway = FakeAccessGateway()
        val controller = HouseholdAccessController(
            household(),
            AuthenticatedIdentity("member-2", "Sam", "sam@example.com"),
            gateway,
        )

        controller.add("other@example.com")
        controller.remove("other@example.com")

        assertEquals(false, controller.state.canManage)
        assertEquals(0, gateway.loadCalls)
        assertEquals(null, gateway.addedEmail)
    }

    @Test
    fun `Owner removes a claimed Member access row`() {
        val gateway = FakeAccessGateway(listOf(access("sam@example.com", "member-2", "Sam")))
        val controller = HouseholdAccessController(household(), owner(), gateway)

        controller.remove("sam@example.com")

        assertEquals("sam@example.com", gateway.removedEmail)
        assertEquals(emptyList<HouseholdAccess>(), controller.state.access)
    }
}

private class FakeAccessGateway(
    private val initial: List<HouseholdAccess> = emptyList(),
) : HouseholdAccessGateway {
    var loadCalls = 0
    var addedEmail: String? = null
    var removedEmail: String? = null

    override fun load(householdId: String, onResult: (Result<List<HouseholdAccess>>) -> Unit) {
        loadCalls += 1
        onResult(Result.success(initial))
    }

    override fun add(
        householdId: String,
        email: String,
        onResult: (Result<HouseholdAccess>) -> Unit,
    ) {
        addedEmail = email
        onResult(Result.success(access(email)))
    }

    override fun remove(householdId: String, email: String, onResult: (Result<Unit>) -> Unit) {
        removedEmail = email
        onResult(Result.success(Unit))
    }
}

private fun owner() = AuthenticatedIdentity("member-1", "Alex", "owner@example.com")

private fun household() = Household(
    id = "household-1",
    ownerMemberId = "member-1",
    ownerEmail = "owner@example.com",
    rootItem = Item("household-1", "Our Home", null, null, null, emptyList()),
)

private fun access(email: String, memberId: String? = null, name: String? = null) = HouseholdAccess(
    email = email,
    householdId = "household-1",
    memberId = memberId,
    memberDisplayName = name,
    memberEmail = memberId?.let { email },
)
