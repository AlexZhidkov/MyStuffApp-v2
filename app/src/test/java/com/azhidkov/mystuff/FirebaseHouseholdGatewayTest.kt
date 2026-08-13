package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirebaseHouseholdGatewayTest {
    @Test
    fun `creation writes the Owner membership Household and matching root Item`() {
        val timestamp = Any()
        val store = FakeHouseholdDocumentStore(
            householdId = "household-1",
            serverTimestamp = timestamp,
        )
        val gateway = FirebaseHouseholdGateway(store)
        val owner = AuthenticatedIdentity(
            id = "member-1",
            displayName = "Alex",
            email = "alex@example.com",
        )
        var result: Result<Household>? = null

        gateway.create(owner, "Our Home") { result = it }

        assertEquals("member-1", store.createdByMemberId)
        assertEquals(
            mapOf(
                "name" to "Our Home",
                "ownerMemberId" to "member-1",
                "rootItemId" to "household-1",
                "createdAt" to timestamp,
            ),
            store.createdDocuments?.household,
        )
        assertEquals(
            mapOf(
                "householdId" to "household-1",
                "name" to "Our Home",
                "parentItemId" to null,
                "photoUrl" to null,
                "description" to null,
                "tags" to emptyList<String>(),
                "createdAt" to timestamp,
                "updatedAt" to timestamp,
                "createdById" to "member-1",
                "createdByDisplayName" to "Alex",
                "updatedById" to "member-1",
                "updatedByDisplayName" to "Alex",
            ),
            store.createdDocuments?.rootItem,
        )
        assertEquals("Our Home", result?.getOrThrow()?.rootItem?.name)
    }

    @Test
    fun `returning Member is reopened from persisted Household documents`() {
        val documents = householdDocuments()
        val store = FakeHouseholdDocumentStore(
            householdIdForMember = "household-1",
            loadedDocuments = documents,
        )
        val gateway = FirebaseHouseholdGateway(store)
        var result: Result<Household?>? = null

        gateway.findForMember("member-1") { result = it }

        assertEquals(
            Household(
                id = "household-1",
                ownerMemberId = "member-1",
                rootItem = Item(
                    id = "household-1",
                    name = "Our Home",
                    parentItemId = null,
                    photoUrl = null,
                    description = null,
                    tags = emptyList(),
                ),
            ),
            result?.getOrThrow(),
        )
    }

    @Test
    fun `person without a membership has no Household to reopen`() {
        val gateway = FirebaseHouseholdGateway(FakeHouseholdDocumentStore())
        var result: Result<Household?>? = null

        gateway.findForMember("member-1") { result = it }

        assertNull(result?.getOrThrow())
    }
}

private class FakeHouseholdDocumentStore(
    private val householdId: String = "unused-household",
    override val serverTimestamp: Any = Any(),
    private val householdIdForMember: String? = null,
    private val loadedDocuments: HouseholdDocuments? = null,
) : HouseholdDocumentStore {
    var createdByMemberId: String? = null
        private set
    var createdDocuments: HouseholdDocuments? = null
        private set

    override fun newHouseholdId(): String = householdId

    override fun findHouseholdIdForMember(
        memberId: String,
        onResult: (Result<String?>) -> Unit,
    ) {
        onResult(Result.success(householdIdForMember))
    }

    override fun loadHousehold(
        householdId: String,
        onResult: (Result<HouseholdDocuments>) -> Unit,
    ) {
        onResult(Result.success(requireNotNull(loadedDocuments)))
    }

    override fun createHousehold(
        memberId: String,
        documents: HouseholdDocuments,
        onResult: (Result<Unit>) -> Unit,
    ) {
        createdByMemberId = memberId
        createdDocuments = documents
        onResult(Result.success(Unit))
    }
}

private fun householdDocuments(): HouseholdDocuments = HouseholdDocuments(
    householdId = "household-1",
    household = mapOf(
        "name" to "Our Home",
        "ownerMemberId" to "member-1",
        "rootItemId" to "household-1",
        "createdAt" to Any(),
    ),
    rootItem = mapOf(
        "householdId" to "household-1",
        "name" to "Our Home",
        "parentItemId" to null,
        "photoUrl" to null,
        "description" to null,
        "tags" to emptyList<String>(),
        "createdAt" to Any(),
        "updatedAt" to Any(),
        "createdById" to "member-1",
        "createdByDisplayName" to "Alex",
        "updatedById" to "member-1",
        "updatedByDisplayName" to "Alex",
    ),
)
