package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LiteralSearchParityTest(
    private val caseName: String,
    private val query: String,
    private val expectedIds: List<String>,
) {
    @Test
    fun `literal Search agrees with the shared MCP fixtures`() {
        val items = fixtureRows("items").map { columns ->
            Item(
                id = columns[0],
                parentItemId = columns[1].takeUnless { it == "-" },
                name = columns[2],
                tags = columns[3].takeUnless { it == "-" }?.split('|') ?: emptyList(),
                description = columns[4].takeUnless { it == "-" },
                webUrl = columns[5].takeUnless { it == "-" },
                photoUrl = columns[6].takeUnless { it == "-" },
            )
        }
        val household = Household(
            id = "home",
            ownerMemberId = "owner",
            rootItem = items.single { it.id == "home" },
        )
        // Match the ID-ordered synced snapshot used by Android and MCP.
        val inventory = Inventory.from(household, items.sortedBy(Item::id))
        val state = InventoryUiState(
            inventory = inventory,
            selectedItemId = household.id,
            search = InventorySearchState(query = query),
        )

        assertEquals(caseName, expectedIds, state.searchResults.map { it.item.id })
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = fixtureRows("queries").map { (name, query, ids) ->
            arrayOf(name, query, if (ids == "-") emptyList<String>() else ids.split(','))
        }
    }
}

private fun fixtureRows(name: String): List<List<String>> =
    checkNotNull(LiteralSearchParityTest::class.java.getResourceAsStream("/literal-search-$name.tsv"))
        .bufferedReader(Charsets.UTF_8)
        .use { reader -> reader.readLines().drop(1).map { it.split('\t') } }
