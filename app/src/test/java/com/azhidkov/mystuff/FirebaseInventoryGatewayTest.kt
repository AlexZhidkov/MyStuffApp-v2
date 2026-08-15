package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Test

class FirebaseInventoryGatewayTest {
    @Test
    fun `observed Item documents become one connected Inventory`() {
        val household = inventoryHousehold()
        val store = FakeInventoryDocumentStore(
            documents = listOf(
                itemDocument("household-1", "Our Home", null),
                itemDocument("garage", "Garage", "household-1"),
                itemDocument("cabinet", "Cabinet", "garage"),
            ),
        )
        val gateway = FirebaseInventoryGateway(store)
        var result: Result<Inventory>? = null

        gateway.observe(household) { result = it }

        assertEquals(
            listOf("Our Home", "Garage", "Cabinet"),
            result?.getOrThrow()?.pathTo("cabinet")?.map(Item::name),
        )
    }

    @Test
    fun `creating an Item writes its generated identity current Parent Item and attribution`() {
        val timestamp = Any()
        val store = FakeInventoryDocumentStore(serverTimestamp = timestamp)
        val gateway = FirebaseInventoryGateway(store)
        var result: Result<Item>? = null

        gateway.createItem(
            householdId = "household-1",
            parentItemId = "garage",
            creator = inventoryIdentity(),
            name = "Drill",
        ) { result = it }

        assertEquals("item-1", store.createdItemId)
        assertEquals(
            mapOf(
                "householdId" to "household-1",
                "name" to "Drill",
                "parentItemId" to "garage",
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
            store.createdData,
        )
        assertEquals(
            inventoryItem("item-1", "Drill", "garage"),
            result?.getOrThrow(),
        )
    }
}

private class FakeInventoryDocumentStore(
    private val documents: List<InventoryItemDocument> = emptyList(),
    override val serverTimestamp: Any = Any(),
) : InventoryDocumentStore {
    var createdItemId: String? = null
        private set
    var createdData: Map<String, Any?>? = null
        private set

    override fun observeItems(
        householdId: String,
        onResult: (Result<List<InventoryItemDocument>>) -> Unit,
    ): InventorySubscription {
        onResult(Result.success(documents))
        return InventorySubscription {}
    }

    override fun newItemId(householdId: String): String = "item-1"

    override fun createItem(
        householdId: String,
        itemId: String,
        data: Map<String, Any?>,
        onResult: (Result<Unit>) -> Unit,
    ) {
        createdItemId = itemId
        createdData = data
        onResult(Result.success(Unit))
    }
}

private fun itemDocument(
    id: String,
    name: String,
    parentItemId: String?,
) = InventoryItemDocument(
    id = id,
    data = mapOf(
        "householdId" to "household-1",
        "name" to name,
        "parentItemId" to parentItemId,
        "photoUrl" to null,
        "description" to null,
        "tags" to emptyList<String>(),
    ),
)

private fun inventoryHousehold() = Household(
    id = "household-1",
    ownerMemberId = "member-1",
    rootItem = inventoryItem("household-1", "Our Home", null),
)

private fun inventoryIdentity() = AuthenticatedIdentity(
    id = "member-1",
    displayName = "Alex",
    email = "alex@example.com",
)

private fun inventoryItem(id: String, name: String, parentItemId: String?) = Item(
    id = id,
    name = name,
    parentItemId = parentItemId,
    photoUrl = null,
    description = null,
    tags = emptyList(),
)
