package com.azhidkov.mystuff.ui

import com.azhidkov.mystuff.Household
import com.azhidkov.mystuff.Inventory
import com.azhidkov.mystuff.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemMoveTreeTest {
    @Test
    fun `tree starts expanded through the current Parent Item`() {
        val fixture = fixture()

        assertEquals(
            listOf("household", "garage"),
            initialMoveExpandedBranch(fixture.inventory, fixture.cabinet),
        )
    }

    @Test
    fun `tree keeps structural ancestors and hides the moved subtree`() {
        val fixture = fixture()
        val rows = itemMoveTreeRows(
            inventory = fixture.inventory,
            movedItem = fixture.cabinet,
            candidateIds = setOf("household", "bench", "shed", "box"),
            selectedParentItemId = "bench",
            expandedBranch = listOf("household", "garage"),
        )

        assertEquals(
            listOf("household", "garage", "bench", "shed"),
            rows.map { it.item.id },
        )
        assertFalse(rows.single { it.item.id == "garage" }.isSelectable)
        assertTrue(rows.single { it.item.id == "garage" }.isCurrentParent)
        assertTrue(rows.single { it.item.id == "bench" }.isSelected)
        assertFalse(rows.any { it.item.id == "cabinet" || it.item.id == "drill" })
    }

    @Test
    fun `expanding a sibling collapses the previously expanded branch`() {
        val fixture = fixture()

        val expanded = toggleMoveExpandedBranch(
            inventory = fixture.inventory,
            expandedBranch = listOf("household", "garage"),
            itemId = "shed",
        )
        val rows = itemMoveTreeRows(
            inventory = fixture.inventory,
            movedItem = fixture.cabinet,
            candidateIds = setOf("household", "bench", "shed", "box"),
            selectedParentItemId = null,
            expandedBranch = expanded,
        )

        assertEquals(listOf("household", "shed"), expanded)
        assertEquals(
            listOf("household", "garage", "shed", "box"),
            rows.map { it.item.id },
        )
        assertFalse(rows.single { it.item.id == "garage" }.isExpanded)
        assertTrue(rows.single { it.item.id == "shed" }.isExpanded)
    }

    @Test
    fun `collapsing an Item removes it and its descendants from expanded branch`() {
        val fixture = fixture()

        assertEquals(
            listOf("household"),
            toggleMoveExpandedBranch(
                inventory = fixture.inventory,
                expandedBranch = listOf("household", "shed", "box"),
                itemId = "shed",
            ),
        )
    }
}

private data class ItemMoveTreeFixture(
    val inventory: Inventory,
    val cabinet: Item,
)

private fun fixture(): ItemMoveTreeFixture {
    val household = Household(
        id = "household",
        ownerMemberId = "member",
        rootItem = item("household", "Our Home", null),
    )
    val garage = item("garage", "Garage", household.id)
    val cabinet = item("cabinet", "Cabinet", garage.id)
    val drill = item("drill", "Drill", cabinet.id)
    val bench = item("bench", "Bench", garage.id)
    val shed = item("shed", "Shed", household.id)
    val box = item("box", "Box", shed.id)
    return ItemMoveTreeFixture(
        inventory = Inventory.from(
            household,
            listOf(household.rootItem, garage, cabinet, drill, bench, shed, box),
        ),
        cabinet = cabinet,
    )
}

private fun item(id: String, name: String, parentItemId: String?) = Item(
    id = id,
    name = name,
    parentItemId = parentItemId,
    photoUrl = null,
    description = null,
    tags = emptyList(),
)
