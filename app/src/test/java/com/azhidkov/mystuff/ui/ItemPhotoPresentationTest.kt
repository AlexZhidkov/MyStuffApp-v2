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
    fun `loading is neutral while a confirmed failure reports unavailable`() {
        assertNull(
            photoUnavailableText(PhotoLoadState.Loading),
        )
        assertEquals(
            R.string.item_photo_unavailable,
            photoUnavailableText(PhotoLoadState.Unavailable),
        )
        assertNull(photoUnavailableText(PhotoLoadState.Available("decoded-photo")))
    }

    @Test
    fun `cached preview remains visible through full photo loading and failures`() {
        val preview = PhotoLoadState.Available("thumbnail", PhotoResolution.Preview)

        assertEquals(
            preview,
            detailStateWithFullLoad(preview, PhotoLoadState.Loading),
        )
        assertEquals(
            preview,
            detailStateWithFullLoad(preview, PhotoLoadState.Unavailable),
        )
    }

    @Test
    fun `full photo replaces preview and cannot be overwritten by a late cache result`() {
        val preview = PhotoLoadState.Available("thumbnail", PhotoResolution.Preview)
        val full = detailStateWithFullLoad(
            preview,
            PhotoLoadState.Available("full-photo"),
        )

        assertEquals(
            PhotoLoadState.Available("full-photo", PhotoResolution.Full),
            full,
        )
        assertEquals(full, detailStateWithPreview(full, "late-thumbnail"))
    }

    @Test
    fun `confirmed full photo failure is visible when no preview exists`() {
        assertEquals(
            PhotoLoadState.Unavailable,
            detailStateWithFullLoad(PhotoLoadState.Loading, PhotoLoadState.Unavailable),
        )
    }
}
