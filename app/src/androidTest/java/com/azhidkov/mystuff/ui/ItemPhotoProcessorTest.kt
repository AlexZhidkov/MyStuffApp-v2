package com.azhidkov.mystuff.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ItemPhotoProcessorTest {
    @Test
    fun cropProducesOnlyBoundedLossyWebPFullAndThumbnailVariants() {
        val directory = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "item-photo-processor-test",
        ).apply { mkdirs() }
        directory.listFiles().orEmpty().forEach(File::delete)
        val crop = Bitmap.createBitmap(1_600, 1_200, Bitmap.Config.ARGB_8888)

        val files = ItemPhotoProcessor.writeVariants(crop, directory)

        assertWebP(files.full, expectedWidth = 1_024, expectedHeight = 768)
        assertWebP(files.thumbnail, expectedWidth = 256, expectedHeight = 192)
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "jpg" })
    }

    private fun assertWebP(file: File, expectedWidth: Int, expectedHeight: Int) {
        val header = file.readBytes().take(12).toByteArray().decodeToString()
        assertTrue(header.startsWith("RIFF"))
        assertEquals("WEBP", header.substring(8, 12))
        val decoded = BitmapFactory.decodeFile(file.path)
        assertEquals(expectedWidth, decoded.width)
        assertEquals(expectedHeight, decoded.height)
    }
}
