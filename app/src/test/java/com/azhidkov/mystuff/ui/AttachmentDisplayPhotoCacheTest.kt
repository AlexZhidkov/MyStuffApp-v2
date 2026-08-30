package com.azhidkov.mystuff.ui

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class AttachmentDisplayPhotoCacheTest {
    @Test
    fun `display image bytes are cached without an application size limit`() = runBlocking {
        val directory = Files.createTempDirectory("attachment-display-cache").toFile()
        try {
            var downloads = 0
            val bytes = ByteArray(3 * 1024 * 1024) { 7 }
            val cache = AttachmentDisplayPhotoCache(
                directory = directory,
                download = {
                    downloads += 1
                    bytes
                },
                decode = { it.size },
            )

            assertEquals(bytes.size, cache.load(DISPLAY_LOCATION))
            assertEquals(bytes.size, cache.load(DISPLAY_LOCATION))

            assertEquals(1, downloads)
            assertTrue(directory.resolve(attachmentDisplayCacheFileName(DISPLAY_LOCATION)).isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `deleting a display image removes its cached bytes`() = runBlocking {
        val directory = Files.createTempDirectory("attachment-display-cache").toFile()
        try {
            val cache = AttachmentDisplayPhotoCache(
                directory = directory,
                download = { byteArrayOf(1, 2, 3) },
                decode = { it.size },
            )

            cache.load(DISPLAY_LOCATION)
            cache.remove(DISPLAY_LOCATION)

            assertTrue(!directory.resolve(attachmentDisplayCacheFileName(DISPLAY_LOCATION)).exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}

private const val DISPLAY_LOCATION = "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp"
