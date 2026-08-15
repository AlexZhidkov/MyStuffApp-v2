package com.azhidkov.mystuff

class InvalidInventoryException : IllegalStateException(
    "Your Inventory data is incomplete. Please try again.",
)

class Inventory private constructor(
    val householdId: String,
    val rootItemId: String,
    private val itemsById: Map<String, Item>,
) {
    val allItems: List<Item>
        get() = itemsById.values.toList()

    fun item(itemId: String): Item = itemsById[itemId] ?: throw InvalidInventoryException()

    fun contains(itemId: String): Boolean = itemsById.containsKey(itemId)

    fun childrenOf(parentItemId: String): List<Item> =
        allItems.filter { it.parentItemId == parentItemId }

    fun pathTo(itemId: String): List<Item> {
        val path = mutableListOf<Item>()
        var current = item(itemId)
        while (true) {
            path += current
            val parentItemId = current.parentItemId ?: break
            current = item(parentItemId)
        }
        return path.asReversed()
    }

    fun withItem(item: Item): Inventory = from(
        householdId = householdId,
        rootItemId = rootItemId,
        items = allItems.filterNot { it.id == item.id } + item,
    )

    companion object {
        fun from(household: Household, items: List<Item>): Inventory = from(
            householdId = household.id,
            rootItemId = household.rootItem.id,
            items = items,
        )

        private fun from(
            householdId: String,
            rootItemId: String,
            items: List<Item>,
        ): Inventory {
            val itemsById = items.associateBy(Item::id)
            if (itemsById.size != items.size || rootItemId != householdId) {
                throw InvalidInventoryException()
            }
            val rootItem = itemsById[rootItemId] ?: throw InvalidInventoryException()
            if (rootItem.parentItemId != null) throw InvalidInventoryException()

            itemsById.values.forEach { item ->
                if (item.id != rootItemId && item.parentItemId == null) {
                    throw InvalidInventoryException()
                }
                val visited = mutableSetOf<String>()
                var current = item
                while (current.id != rootItemId) {
                    if (!visited.add(current.id)) throw InvalidInventoryException()
                    val parentItemId = current.parentItemId ?: throw InvalidInventoryException()
                    current = itemsById[parentItemId] ?: throw InvalidInventoryException()
                }
            }
            return Inventory(householdId, rootItemId, itemsById)
        }
    }
}
