package com.azhidkov.mystuff.ui

internal data class ChildItemBounds(
    val itemId: String,
    val topPx: Float,
    val heightPx: Float,
)

internal fun childItemSwapOffsets(
    draggedItemId: String,
    draggedTopPx: Float,
    draggedHeightPx: Float,
    dragAmountY: Float,
    orderedItemIds: List<String>,
    visibleItemBounds: List<ChildItemBounds>,
): List<Int> {
    if (dragAmountY == 0f) return emptyList()
    val draggedIndex = orderedItemIds.indexOf(draggedItemId)
    if (draggedIndex == -1) return emptyList()

    val boundsByItemId = visibleItemBounds.associateBy(ChildItemBounds::itemId)
    val draggedCenterPx = draggedTopPx + draggedHeightPx / 2f
    val swapOffsets = mutableListOf<Int>()
    if (dragAmountY > 0f) {
        for (index in draggedIndex + 1..orderedItemIds.lastIndex) {
            val adjacentBounds = boundsByItemId[orderedItemIds[index]] ?: break
            val adjacentCenterPx = adjacentBounds.topPx + adjacentBounds.heightPx / 2f
            if (draggedCenterPx <= adjacentCenterPx) break
            swapOffsets += 1
        }
    } else {
        for (index in draggedIndex - 1 downTo 0) {
            val adjacentBounds = boundsByItemId[orderedItemIds[index]] ?: break
            val adjacentCenterPx = adjacentBounds.topPx + adjacentBounds.heightPx / 2f
            if (draggedCenterPx >= adjacentCenterPx) break
            swapOffsets += -1
        }
    }
    return swapOffsets
}

internal fun childItemBounds(
    itemId: String,
    topPx: Int,
    heightPx: Int,
) = ChildItemBounds(
    itemId = itemId,
    topPx = topPx.toFloat(),
    heightPx = heightPx.toFloat(),
)
