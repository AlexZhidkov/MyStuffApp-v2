package com.azhidkov.mystuff.ui

import com.azhidkov.mystuff.Item
import com.azhidkov.mystuff.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `compact loading is neutral while failures and detail loading report unavailable`() {
        assertNull(
            photoUnavailableText(
                photoLoadStateForPresentation(
                    PhotoLoadState.Loading,
                    ItemPhotoPresentation.Compact,
                ),
            ),
        )
        assertEquals(
            R.string.item_photo_unavailable,
            photoUnavailableText(PhotoLoadState.Unavailable),
        )
        assertEquals(
            R.string.item_photo_unavailable,
            photoUnavailableText(
                photoLoadStateForPresentation(
                    PhotoLoadState.Loading,
                    ItemPhotoPresentation.Detail,
                ),
            ),
        )
        assertNull(photoUnavailableText(PhotoLoadState.Available("decoded-photo")))
    }
}
