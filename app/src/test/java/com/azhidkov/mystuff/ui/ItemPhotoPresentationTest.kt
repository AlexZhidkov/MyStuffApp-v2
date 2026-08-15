package com.azhidkov.mystuff.ui

import com.azhidkov.mystuff.Item
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemPhotoPresentationTest {
    @Test
    fun `detail uses full photo while compact views use thumbnail`() {
        val item = Item(
            id = "drill",
            name = "Drill",
            parentItemId = "cabinet",
            photoUrl = "gs://mystuff/households/household-1/items/drill.webp",
            description = null,
            tags = emptyList(),
            photoThumbnailUrl = "gs://mystuff/households/household-1/items/drill-thumb.webp",
        )

        assertEquals(
            item.photoUrl,
            storedPhotoLocation(item, ItemPhotoPresentation.Detail),
        )
        assertEquals(
            item.photoThumbnailUrl,
            storedPhotoLocation(item, ItemPhotoPresentation.Compact),
        )
    }
}
