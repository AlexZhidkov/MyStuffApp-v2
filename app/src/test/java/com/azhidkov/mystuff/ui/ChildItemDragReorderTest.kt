package com.azhidkov.mystuff.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChildItemDragReorderTest {
    @Test
    fun `drag does not swap before the dragged center crosses the adjacent Item center`() {
        val swapOffsets = childItemSwapOffsets(
            draggedItemId = "garage",
            draggedTopPx = 79f,
            draggedHeightPx = 48f,
            dragAmountY = 79f,
            orderedItemIds = listOf("garage", "kitchen"),
            visibleItemBounds = listOf(
                ChildItemBounds("garage", topPx = 0f, heightPx = 48f),
                ChildItemBounds("kitchen", topPx = 60f, heightPx = 88f),
            ),
        )

        assertEquals(emptyList<Int>(), swapOffsets)
    }

    @Test
    fun `drag swaps immediately after the adjacent Item center is crossed`() {
        val swapOffsets = childItemSwapOffsets(
            draggedItemId = "garage",
            draggedTopPx = 81f,
            draggedHeightPx = 48f,
            dragAmountY = 2f,
            orderedItemIds = listOf("garage", "kitchen"),
            visibleItemBounds = listOf(
                ChildItemBounds("garage", topPx = 0f, heightPx = 48f),
                ChildItemBounds("kitchen", topPx = 60f, heightPx = 88f),
            ),
        )

        assertEquals(listOf(1), swapOffsets)
    }

    @Test
    fun `dragging down swaps with each adjacent Item as its center is crossed`() {
        val swapOffsets = childItemSwapOffsets(
            draggedItemId = "garage",
            draggedTopPx = 170f,
            draggedHeightPx = 48f,
            dragAmountY = 170f,
            orderedItemIds = listOf("garage", "kitchen", "shed"),
            visibleItemBounds = listOf(
                ChildItemBounds("garage", topPx = 0f, heightPx = 48f),
                ChildItemBounds("kitchen", topPx = 60f, heightPx = 88f),
                ChildItemBounds("shed", topPx = 160f, heightPx = 48f),
            ),
        )

        assertEquals(listOf(1, 1), swapOffsets)
    }

    @Test
    fun `dragging up swaps with each adjacent Item as its center is crossed`() {
        val swapOffsets = childItemSwapOffsets(
            draggedItemId = "shed",
            draggedTopPx = -2f,
            draggedHeightPx = 48f,
            dragAmountY = -162f,
            orderedItemIds = listOf("garage", "kitchen", "shed"),
            visibleItemBounds = listOf(
                ChildItemBounds("garage", topPx = 0f, heightPx = 48f),
                ChildItemBounds("kitchen", topPx = 60f, heightPx = 88f),
                ChildItemBounds("shed", topPx = 160f, heightPx = 48f),
            ),
        )

        assertEquals(listOf(-1, -1), swapOffsets)
    }
}
