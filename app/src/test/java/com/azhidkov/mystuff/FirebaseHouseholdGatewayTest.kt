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
                "householdId" to "household-1",
                "role" to "owner",
                "householdName" to "Our Home",
                "ownerMemberId" to "member-1",
                "ownerEmail" to "alex@example.com",
                "useTags" to false,
            ),
            store.createdDocuments?.membership,
        )
        assertEquals(
            mapOf(
                "name" to "Our Home",
                "ownerMemberId" to "member-1",
                "ownerEmail" to "alex@example.com",
                "rootItemId" to "household-1",
                "useTags" to false,
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
                "webUrl" to null,
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
        assertEquals(false, result?.getOrThrow()?.useTags)
    }

    @Test
    fun `returning Member is reopened from one bootstrap document`() {
        val bootstrap = householdBootstrap()
        val store = FakeHouseholdDocumentStore(
            cachedBootstrap = Result.success(bootstrap),
            serverBootstrap = Result.success(bootstrap),
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
        assertEquals(false, result?.getOrThrow()?.useTags)
        assertEquals(
            listOf(HouseholdDocumentSource.Cache, HouseholdDocumentSource.Server),
            store.loadedSources,
        )
    }

    @Test
    fun `persisted useTags enables Tags for the Household`() {
        val bootstrap = householdBootstrap().let { existing ->
            existing.copy(data = existing.data + ("useTags" to true))
        }
        val gateway = FirebaseHouseholdGateway(
            FakeHouseholdDocumentStore(
                serverBootstrap = Result.success(bootstrap),
            ),
        )
        var result: Result<Household?>? = null

        gateway.findForMember("member-1") { result = it }

        assertEquals(true, result?.getOrThrow()?.useTags)
    }

    @Test
    fun `person without a membership has no Household to reopen`() {
        val gateway = FirebaseHouseholdGateway(FakeHouseholdDocumentStore())
        var result: Result<Household?>? = null

        gateway.findForMember("member-1") { result = it }

        assertNull(result?.getOrThrow())
    }

    @Test
    fun `cached membership is not opened until the server confirms it is current`() {
        val store = FakeHouseholdDocumentStore(
            cachedBootstrap = Result.success(householdBootstrap()),
            serverBootstrap = Result.success(null),
        )
        val gateway = FirebaseHouseholdGateway(store)
        var result: Result<Household?>? = null

        gateway.findForMember("member-1") { result = it }

        assertNull(result?.getOrThrow())
        assertEquals(
            listOf(HouseholdDocumentSource.Cache, HouseholdDocumentSource.Server),
            store.loadedSources,
        )
    }

    @Test
    fun `cache miss remains unresolved until the server confirms no membership`() {
        val store = FakeHouseholdDocumentStore(completeServerImmediately = false)
        val gateway = FirebaseHouseholdGateway(store)
        var callbackCount = 0
        var result: Result<Household?>? = null

        gateway.findForMember("member-1") {
            callbackCount += 1
            result = it
        }

        assertEquals(0, callbackCount)

        store.completeServer(Result.success(null))

        assertEquals(1, callbackCount)
        assertNull(result?.getOrThrow())
    }

    @Test
    fun `legacy membership reopens once and is upgraded to a bootstrap document`() {
        val store = FakeHouseholdDocumentStore(
            serverBootstrap = Result.success(
                HouseholdBootstrapDocument(
                    memberId = "member-1",
                    data = mapOf(
                        "householdId" to "household-1",
                        "role" to "owner",
                    ),
                ),
            ),
            legacyHousehold = Result.success(
                mapOf(
                    "name" to "Our Home",
                    "ownerMemberId" to "member-1",
                    "useTags" to false,
                ),
            ),
        )
        val gateway = FirebaseHouseholdGateway(store)
        var result: Result<Household?>? = null

        gateway.findForMember("member-1") { result = it }

        assertEquals("Our Home", result?.getOrThrow()?.rootItem?.name)
        assertEquals(
            householdBootstrap().data,
            store.savedBootstrap?.data,
        )
    }
}

private class FakeHouseholdDocumentStore(
    private val householdId: String = "unused-household",
    override val serverTimestamp: Any = Any(),
    private val cachedBootstrap: Result<HouseholdBootstrapDocument?> = Result.success(null),
    private val serverBootstrap: Result<HouseholdBootstrapDocument?> = Result.success(null),
    private val completeServerImmediately: Boolean = true,
    private val legacyHousehold: Result<Map<String, Any?>> =
        Result.failure(IllegalStateException("No legacy Household configured")),
) : HouseholdDocumentStore {
    private var pendingServer: ((Result<HouseholdBootstrapDocument?>) -> Unit)? = null
    val loadedSources = mutableListOf<HouseholdDocumentSource>()
    var createdByMemberId: String? = null
        private set
    var createdDocuments: HouseholdDocuments? = null
        private set
    var savedBootstrap: HouseholdBootstrapDocument? = null
        private set

    override fun newHouseholdId(): String = householdId

    override fun loadBootstrap(
        memberId: String,
        source: HouseholdDocumentSource,
        onResult: (Result<HouseholdBootstrapDocument?>) -> Unit,
    ) {
        loadedSources += source
        if (source == HouseholdDocumentSource.Cache) {
            onResult(cachedBootstrap)
        } else if (completeServerImmediately) {
            onResult(serverBootstrap)
        } else {
            pendingServer = onResult
        }
    }

    fun completeServer(result: Result<HouseholdBootstrapDocument?>) {
        requireNotNull(pendingServer).invoke(result)
        pendingServer = null
    }

    override fun loadHouseholdSummary(
        householdId: String,
        onResult: (Result<Map<String, Any?>>) -> Unit,
    ) {
        onResult(legacyHousehold)
    }

    override fun saveBootstrap(
        bootstrap: HouseholdBootstrapDocument,
        onResult: (Result<Unit>) -> Unit,
    ) {
        savedBootstrap = bootstrap
        onResult(Result.success(Unit))
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

private fun householdBootstrap(): HouseholdBootstrapDocument = HouseholdBootstrapDocument(
    memberId = "member-1",
    data = mapOf(
        "householdId" to "household-1",
        "role" to "owner",
        "householdName" to "Our Home",
        "ownerMemberId" to "member-1",
        "useTags" to false,
    ),
)
