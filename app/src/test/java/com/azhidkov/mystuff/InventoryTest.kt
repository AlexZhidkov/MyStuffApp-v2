package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class InventoryTest {
    @Test
    fun `moving an Item changes only its Parent Item and preserves its subtree`() {
        val household = testHousehold()
        val garage = testItem("garage", "Garage", household.id)
        val cabinet = testItem("cabinet", "Cabinet", garage.id)
        val drill = testItem(
            id = "drill",
            name = "Drill",
            parentItemId = cabinet.id,
            description = "18V",
            tags = listOf("Tools"),
        )
        val shed = testItem("shed", "Shed", household.id)
        val inventory = Inventory.from(household, listOf(household.rootItem, garage, cabinet, drill, shed))

        val moved = inventory.moveItem(cabinet.id, shed.id)

        assertSame(cabinet, inventory.item(cabinet.id))
        assertEquals(shed.id, moved.item(cabinet.id).parentItemId)
        assertEquals(drill, moved.item(drill.id))
        assertEquals(listOf("Our Home", "Shed", "Cabinet"), moved.pathTo(cabinet.id).map(Item::name))
        assertEquals(
            listOf("Our Home", "Shed", "Cabinet", "Drill"),
            moved.pathTo(drill.id).map(Item::name),
        )
    }

    @Test
    fun `moving rejects the root Item itself and descendants as Parent Items`() {
        val household = testHousehold()
        val source = testItem("source", "Source", household.id)
        val child = testItem("child", "Child", source.id)
        val target = testItem("target", "Target", household.id)
        val inventory = Inventory.from(household, listOf(household.rootItem, source, child, target))

        assertThrows(InvalidItemMoveException::class.java) {
            inventory.moveItem(household.id, target.id)
        }
        assertThrows(InvalidItemMoveException::class.java) {
            inventory.moveItem(source.id, source.id)
        }
        assertThrows(InvalidItemMoveException::class.java) {
            inventory.moveItem(source.id, child.id)
        }
    }

    @Test
    fun `moving rejects missing Items and Parent Items`() {
        val household = testHousehold()
        val source = testItem("source", "Source", household.id)
        val inventory = Inventory.from(household, listOf(household.rootItem, source))

        assertThrows(InvalidItemMoveException::class.java) {
            inventory.moveItem("missing", household.id)
        }
        assertThrows(InvalidItemMoveException::class.java) {
            inventory.moveItem(source.id, "missing")
        }
    }
}

private fun testHousehold() = Household(
    id = "household-1",
    ownerMemberId = "member-1",
    rootItem = testItem("household-1", "Our Home", null),
)

private fun testItem(
    id: String,
    name: String,
    parentItemId: String?,
    description: String? = null,
    tags: List<String> = emptyList(),
) = Item(
    id = id,
    name = name,
    parentItemId = parentItemId,
    photoUrl = null,
    description = description,
    tags = tags,
)
