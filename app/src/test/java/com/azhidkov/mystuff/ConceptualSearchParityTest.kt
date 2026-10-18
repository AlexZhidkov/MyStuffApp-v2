package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ConceptualSearchParityTest(
    private val caseName: String,
    private val query: String,
    private val eligible: Boolean,
    private val outcome: String,
    private val candidates: List<String>,
    private val expectedIds: List<String>,
) {
    @Test
    fun `conceptual Search agrees with the shared MCP fixtures`() {
        val items = rows("literal-search-items").map { columns ->
            Item(
                id = columns[0],
                parentItemId = columns[1].takeUnless { it == "-" },
                name = columns[2],
                tags = columns[3].takeUnless { it == "-" }?.split('|') ?: emptyList(),
                description = columns[4].takeUnless { it == "-" },
                photoUrl = columns[6].takeUnless { it == "-" },
            )
        }
        val household = Household(
            id = "home",
            ownerMemberId = "owner",
            rootItem = items.single { it.id == "home" },
        )
        val state = InventoryUiState(
            inventory = Inventory.from(household, items.sortedBy(Item::id)),
            selectedItemId = household.id,
            search = InventorySearchState(
                query = query,
                conceptualResultIds = candidates.takeIf { eligible && outcome == "success" },
            ),
        )

        assertEquals(caseName, eligible, query.isConceptualSearchEligible())
        assertEquals(caseName, expectedIds, state.searchResults.map { it.item.id })
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = rows("conceptual-search-queries").map { columns ->
            arrayOf(
                columns[0], columns[1], columns[2].toBoolean(), columns[3],
                ids(columns[4]), ids(columns[5]),
            )
        }

        private fun ids(value: String): List<String> =
            if (value == "-") emptyList() else value.split(',')
    }
}

private fun rows(name: String): List<List<String>> =
    checkNotNull(ConceptualSearchParityTest::class.java.getResourceAsStream("/$name.tsv"))
        .bufferedReader(Charsets.UTF_8)
        .use { reader -> reader.readLines().drop(1).map { it.split('\t') } }
