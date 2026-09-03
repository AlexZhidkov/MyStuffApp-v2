package com.azhidkov.mystuff

class InvalidInventoryException : IllegalStateException(
    "Your Inventory data is incomplete. Please try again.",
)

class InvalidItemMoveException(message: String) : IllegalArgumentException(message)

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
        allItems
            .filter { it.parentItemId == parentItemId }
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<Item>>(
                    { it.value.displayOrder ?: it.index.toLong() },
                    IndexedValue<Item>::index,
                ),
            )
            .map(IndexedValue<Item>::value)

    fun reorderItem(itemId: String, offset: Int): Inventory {
        val item = item(itemId)
        val parentItemId = item.parentItemId
            ?: throw IllegalArgumentException("The Household root Item cannot be reordered.")
        val children = childrenOf(parentItemId)
        val currentIndex = children.indexOfFirst { it.id == itemId }
        val targetIndex = (currentIndex + offset).coerceIn(0, children.lastIndex)
        if (targetIndex == currentIndex) return this
        val reordered = children.toMutableList().apply {
            add(targetIndex, removeAt(currentIndex))
        }
        return withChildrenInOrder(parentItemId, reordered.map(Item::id))
    }

    fun withChildrenInOrder(parentItemId: String, orderedItemIds: List<String>): Inventory {
        val children = childrenOf(parentItemId)
        val childrenById = children.associateBy(Item::id)
        require(orderedItemIds.size == orderedItemIds.distinct().size) {
            "Child Item order contains duplicates."
        }
        require(orderedItemIds.all(childrenById::containsKey)) {
            "Child Item order contains an unrelated Item."
        }
        val completeOrder = orderedItemIds + children.map(Item::id).filterNot(orderedItemIds::contains)
        return completeOrder.foldIndexed(this) { index, inventory, childId ->
            inventory.withItem(
                childrenById.getValue(childId).copy(displayOrder = index.toLong()),
            )
        }
    }

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

    fun moveItem(itemId: String, newParentItemId: String): Inventory {
        val item = validateMove(itemId, newParentItemId)
        return withItem(item.copy(parentItemId = newParentItemId))
    }

    fun isValidMoveTarget(itemId: String, newParentItemId: String): Boolean {
        return try {
            validateMove(itemId, newParentItemId)
            true
        } catch (_: InvalidItemMoveException) {
            false
        }
    }

    private fun validateMove(itemId: String, newParentItemId: String): Item {
        val item = itemsById[itemId]
            ?: throw InvalidItemMoveException("The Item no longer exists.")
        itemsById[newParentItemId]
            ?: throw InvalidItemMoveException("The selected Parent Item no longer exists.")
        if (item.id == rootItemId) {
            throw InvalidItemMoveException("The Household root Item cannot be moved.")
        }
        if (item.id == newParentItemId) {
            throw InvalidItemMoveException("An Item cannot be its own Parent Item.")
        }
        if (pathTo(newParentItemId).any { it.id == item.id }) {
            throw InvalidItemMoveException(
                "An Item cannot be moved beneath one of its Child Items.",
            )
        }
        return item
    }

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
