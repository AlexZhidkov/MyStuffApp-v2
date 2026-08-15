package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InventoryControllerTest {
    @Test
    fun `Member browses immediate Child Items and a complete deep Item Path`() {
        val household = household()
        val inventory = Inventory.from(
            household = household,
            items = listOf(
                household.rootItem,
                item("garage", "Garage", household.id),
                item("cabinet", "Cabinet", "garage"),
                item("drill", "Drill", "cabinet"),
            ),
        )
        val gateway = FakeInventoryGateway(inventory)
        val controller = InventoryController(household, identity(), gateway)

        assertEquals(listOf("Garage"), controller.state.childItems.map(Item::name))

        controller.openItem("garage")
        assertEquals("Garage", controller.state.selectedItem.name)
        assertEquals(listOf("Cabinet"), controller.state.childItems.map(Item::name))

        controller.openItem("cabinet")
        controller.openItem("drill")
        assertEquals(
            listOf("Our Home", "Garage", "Cabinet", "Drill"),
            controller.state.itemPath.map(Item::name),
        )
    }

    @Test
    fun `Add item defaults to displayed Item and accepts a changed Parent Item`() {
        val household = household()
        val inventory = Inventory.from(
            household,
            listOf(
                household.rootItem,
                item("garage", "Garage", household.id),
                item("shed", "Shed", household.id),
            ),
        )
        val gateway = FakeInventoryGateway(inventory)
        val controller = InventoryController(household, identity(), gateway)

        controller.beginAddItem()
        assertEquals("household-1", controller.state.itemDraft?.parentItemId)
        controller.cancelAddItem()

        controller.openItem("garage")
        controller.beginAddItem()
        assertEquals("garage", controller.state.itemDraft?.parentItemId)

        controller.changeParentItem("shed")
        controller.changeItemName("  Drill  ")
        controller.saveItem()

        assertEquals("shed", gateway.createdParentItemId)
        assertEquals("Drill", gateway.createdName)
        assertNull(controller.state.itemDraft)
        assertEquals("shed", controller.state.selectedItem.id)
        assertEquals(listOf("Drill"), controller.state.childItems.map(Item::name))
    }

    @Test
    fun `Item names are trimmed and contain one to one hundred Unicode characters`() {
        val gateway = FakeInventoryGateway(inventory())
        val controller = InventoryController(household(), identity(), gateway)

        controller.beginAddItem()
        controller.changeItemName("   ")
        controller.saveItem()
        assertEquals("Enter an Item name.", controller.state.itemDraft?.nameError)

        controller.changeItemName("🏠".repeat(101))
        controller.saveItem()
        assertEquals(
            "Item names can contain at most 100 characters.",
            controller.state.itemDraft?.nameError,
        )

        controller.changeItemName("  ${"🏠".repeat(100)}  ")
        controller.saveItem()
        assertEquals("🏠".repeat(100), gateway.createdName)
    }

    @Test
    fun `duplicate Item names are allowed beneath the same Parent Item`() {
        val gateway = FakeInventoryGateway(inventory())
        val controller = InventoryController(household(), identity(), gateway)

        repeat(2) {
            controller.beginAddItem()
            controller.changeItemName("Box")
            controller.saveItem()
        }

        assertEquals(listOf("Box", "Box"), controller.state.childItems.map(Item::name))
    }

    @Test(expected = InvalidInventoryException::class)
    fun `Inventory rejects a disconnected Item`() {
        Inventory.from(
            household(),
            listOf(
                household().rootItem,
                item("lost", "Lost", "missing-parent"),
            ),
        )
    }

    @Test(expected = InvalidInventoryException::class)
    fun `Inventory rejects another parentless Item`() {
        Inventory.from(
            household(),
            listOf(
                household().rootItem,
                item("another-root", "Another root", null),
            ),
        )
    }
}

private class FakeInventoryGateway(
    initialInventory: Inventory,
) : InventoryGateway {
    private var inventory = initialInventory
    private var observer: ((Result<Inventory>) -> Unit)? = null
    private var nextId = 1

    var createdParentItemId: String? = null
        private set
    var createdName: String? = null
        private set

    override fun observe(
        household: Household,
        onResult: (Result<Inventory>) -> Unit,
    ): InventorySubscription {
        observer = onResult
        onResult(Result.success(inventory))
        return InventorySubscription { observer = null }
    }

    override fun createItem(
        householdId: String,
        parentItemId: String,
        creator: AuthenticatedIdentity,
        name: String,
        onResult: (Result<Item>) -> Unit,
    ) {
        createdParentItemId = parentItemId
        createdName = name
        val created = item("created-${nextId++}", name, parentItemId)
        inventory = inventory.withItem(created)
        onResult(Result.success(created))
        observer?.invoke(Result.success(inventory))
    }
}

private fun household() = Household(
    id = "household-1",
    ownerMemberId = "member-1",
    rootItem = item("household-1", "Our Home", null),
)

private fun identity() = AuthenticatedIdentity(
    id = "member-1",
    displayName = "Alex",
    email = "alex@example.com",
)

private fun inventory(): Inventory {
    val household = household()
    return Inventory.from(household, listOf(household.rootItem))
}

private fun item(id: String, name: String, parentItemId: String?) = Item(
    id = id,
    name = name,
    parentItemId = parentItemId,
    photoUrl = null,
    description = null,
    tags = emptyList(),
)
