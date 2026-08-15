package com.azhidkov.mystuff.ui

import com.azhidkov.mystuff.ItemPhotoVariant
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemPhotoSizingTest {
    @Test
    fun `full and thumbnail policies bound dimensions without upscaling`() {
        assertEquals(PhotoDimensions(1_024, 768), photoDimensions(1_600, 1_200, 1_024))
        assertEquals(PhotoDimensions(256, 192), photoDimensions(1_600, 1_200, 256))
        assertEquals(PhotoDimensions(180, 120), photoDimensions(180, 120, 1_024))
        assertEquals(PhotoDimensions(180, 120), photoDimensions(180, 120, 256))
        assertEquals(75, ItemPhotoVariant.Full.webPQuality)
        assertEquals(68, ItemPhotoVariant.Thumbnail.webPQuality)
    }
}
