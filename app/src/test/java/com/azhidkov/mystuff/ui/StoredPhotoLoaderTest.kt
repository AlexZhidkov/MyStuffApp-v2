package com.azhidkov.mystuff.ui

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredPhotoLoaderTest {
    @Test
    fun `compact thumbnail memory hit avoids disk decode and Firebase`() = runBlocking {
        withTemporaryDirectory { cacheDirectory ->
            var firebaseRequests = 0
            var decodes = 0
            val location = VERSIONED_THUMBNAIL_LOCATION
            val cache = ThumbnailCache(
                directory = cacheDirectory,
                memory = SizedLruMemoryCache(maxSizeBytes = 1_024, sizeOf = String::length),
                download = {
                    firebaseRequests += 1
                    "downloaded-webp".encodeToByteArray()
                },
                decode = {
                    decodes += 1
                    it.decodeToString()
                },
            )
            val loader = StoredPhotoLoader(
                thumbnails = cache,
                download = { _, _ -> error("detail loader should not be used") },
                decode = { error("detail decoder should not be used") },
            )

            assertEquals("downloaded-webp", loader.load(location, ItemPhotoPresentation.Compact))
            assertEquals("downloaded-webp", loader.memoryValue(location, ItemPhotoPresentation.Compact))
            cacheDirectory.resolve(thumbnailCacheFileName(location)).delete()

            assertEquals("downloaded-webp", loader.load(location, ItemPhotoPresentation.Compact))
            assertEquals(1, firebaseRequests)
            assertEquals(1, decodes)
        }
    }

    @Test
    fun `cached compact thumbnail remains available offline without Firebase validation`() = runBlocking {
        withTemporaryDirectory { cacheDirectory ->
            var firebaseRequests = 0
            val firstLoader = compactLoader(
                cacheDirectory = cacheDirectory,
                download = {
                    firebaseRequests += 1
                    "original-compressed-webp".encodeToByteArray()
                },
            )
            assertEquals(
                "original-compressed-webp",
                firstLoader.load(VERSIONED_THUMBNAIL_LOCATION, ItemPhotoPresentation.Compact),
            )

            val restartedLoader = compactLoader(
                cacheDirectory = cacheDirectory,
                download = {
                    firebaseRequests += 1
                    error("Firebase should not be used for a disk hit")
                },
            )
            assertEquals(
                "original-compressed-webp",
                restartedLoader.load(VERSIONED_THUMBNAIL_LOCATION, ItemPhotoPresentation.Compact),
            )
            cacheDirectory.resolve(thumbnailCacheFileName(VERSIONED_THUMBNAIL_LOCATION)).delete()
            assertEquals(
                "original-compressed-webp",
                restartedLoader.load(VERSIONED_THUMBNAIL_LOCATION, ItemPhotoPresentation.Compact),
            )
            assertEquals(1, firebaseRequests)
        }
    }

    @Test
    fun `corrupt cached thumbnail is deleted and downloaded again`() = runBlocking {
        withTemporaryDirectory { cacheDirectory ->
            val cacheFile = cacheDirectory.resolve(
                thumbnailCacheFileName(VERSIONED_THUMBNAIL_LOCATION),
            )
            cacheFile.writeText("truncated")
            var firebaseRequests = 0
            val loader = StoredPhotoLoader(
                thumbnails = ThumbnailCache(
                    directory = cacheDirectory,
                    memory = SizedLruMemoryCache(maxSizeBytes = 1_024, sizeOf = String::length),
                    download = {
                        firebaseRequests += 1
                        "valid-webp".encodeToByteArray()
                    },
                    decode = { bytes ->
                        bytes.decodeToString().also { decoded ->
                            check(decoded == "valid-webp") { "Thumbnail cannot be decoded" }
                        }
                    },
                ),
                download = { _, _ -> error("detail loader should not be used") },
                decode = { error("detail decoder should not be used") },
            )

            assertEquals(
                "valid-webp",
                loader.load(VERSIONED_THUMBNAIL_LOCATION, ItemPhotoPresentation.Compact),
            )
            assertEquals(1, firebaseRequests)
            assertEquals("valid-webp", cacheFile.readText())
        }
    }

    @Test
    fun `downloaded thumbnail is not committed until it decodes successfully`() = runBlocking {
        withTemporaryDirectory { cacheDirectory ->
            val loader = StoredPhotoLoader(
                thumbnails = ThumbnailCache(
                    directory = cacheDirectory,
                    memory = SizedLruMemoryCache(maxSizeBytes = 1_024, sizeOf = String::length),
                    download = { "corrupt-download".encodeToByteArray() },
                    decode = { error("Thumbnail cannot be decoded") },
                ),
                download = { _, _ -> error("detail loader should not be used") },
                decode = { error("detail decoder should not be used") },
            )

            val result = runCatching {
                loader.load(VERSIONED_THUMBNAIL_LOCATION, ItemPhotoPresentation.Compact)
            }

            assertTrue(result.isFailure)
            assertFalse(
                cacheDirectory.resolve(
                    thumbnailCacheFileName(VERSIONED_THUMBNAIL_LOCATION),
                ).exists(),
            )
        }
    }

    @Test
    fun `failed thumbnail download leaves no disk cache entry`() = runBlocking {
        withTemporaryDirectory { cacheDirectory ->
            val loader = compactLoader(cacheDirectory) {
                error("No network connectivity")
            }

            val result = runCatching {
                loader.load(VERSIONED_THUMBNAIL_LOCATION, ItemPhotoPresentation.Compact)
            }

            assertTrue(result.isFailure)
            assertTrue(cacheDirectory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `partial disk write is discarded while decoded thumbnail remains available`() = runBlocking {
        withTemporaryDirectory { cacheDirectory ->
            val memory = SizedLruMemoryCache(maxSizeBytes = 1_024, sizeOf = String::length)
            val loader = StoredPhotoLoader(
                thumbnails = ThumbnailCache(
                    directory = cacheDirectory,
                    memory = memory,
                    download = { "valid-webp".encodeToByteArray() },
                    decode = ByteArray::decodeToString,
                    writeTemporaryFile = { temporaryFile, _ ->
                        temporaryFile.writeText("partial")
                        error("Disk is full")
                    },
                ),
                download = { _, _ -> error("detail loader should not be used") },
                decode = { error("detail decoder should not be used") },
            )

            assertEquals(
                "valid-webp",
                loader.load(VERSIONED_THUMBNAIL_LOCATION, ItemPhotoPresentation.Compact),
            )
            assertEquals(
                "valid-webp",
                loader.memoryValue(VERSIONED_THUMBNAIL_LOCATION, ItemPhotoPresentation.Compact),
            )
            assertTrue(cacheDirectory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `successful thumbnail response is stored as original bytes under location SHA-256`() =
        runBlocking {
            withTemporaryDirectory { cacheDirectory ->
                val originalBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x01, 0x02)
                val loader = compactLoader(cacheDirectory) { originalBytes }

                loader.load(VERSIONED_THUMBNAIL_LOCATION, ItemPhotoPresentation.Compact)

                assertEquals(
                    "2de52039c1c2e96ade96eeb4bbc20a2312b37cf54af41e0fba4e46270c5e0ff8.webp",
                    thumbnailCacheFileName(VERSIONED_THUMBNAIL_LOCATION),
                )
                assertTrue(
                    originalBytes.contentEquals(
                        cacheDirectory
                            .resolve(thumbnailCacheFileName(VERSIONED_THUMBNAIL_LOCATION))
                            .readBytes(),
                    ),
                )
            }
        }

    @Test
    fun `detail photo bypasses thumbnail memory and disk caches`() = runBlocking {
        withTemporaryDirectory { cacheDirectory ->
            var thumbnailRequests = 0
            var detailRequests = 0
            val thumbnails = ThumbnailCache(
                directory = cacheDirectory,
                memory = SizedLruMemoryCache(maxSizeBytes = 1_024, sizeOf = String::length),
                download = {
                    thumbnailRequests += 1
                    "thumbnail".encodeToByteArray()
                },
                decode = ByteArray::decodeToString,
            )
            val loader = StoredPhotoLoader(
                thumbnails = thumbnails,
                download = { _, _ ->
                    detailRequests += 1
                    "full-photo".encodeToByteArray()
                },
                decode = ByteArray::decodeToString,
            )

            assertNull(loader.memoryValue(FULL_PHOTO_LOCATION, ItemPhotoPresentation.Detail))
            assertEquals("full-photo", loader.load(FULL_PHOTO_LOCATION, ItemPhotoPresentation.Detail))
            assertEquals("full-photo", loader.load(FULL_PHOTO_LOCATION, ItemPhotoPresentation.Detail))

            assertEquals(0, thumbnailRequests)
            assertEquals(2, detailRequests)
            assertFalse(cacheDirectory.resolve(thumbnailCacheFileName(FULL_PHOTO_LOCATION)).exists())
        }
    }

    @Test
    fun `decoded thumbnail memory cache is LRU and capped at one eighth heap`() {
        assertEquals(128L, thumbnailMemoryCacheMaxBytes(1_024L))
        val memory = SizedLruMemoryCache(maxSizeBytes = 6, sizeOf = String::length)
        memory.put("oldest", "aa")
        memory.put("recent", "bb")
        memory.get("oldest")

        memory.put("new", "ccc")

        assertEquals("aa", memory.get("oldest"))
        assertNull(memory.get("recent"))
        assertEquals("ccc", memory.get("new"))
    }

    private fun compactLoader(
        cacheDirectory: java.io.File,
        download: suspend (String) -> ByteArray,
    ): StoredPhotoLoader<String> = StoredPhotoLoader(
        thumbnails = ThumbnailCache(
            directory = cacheDirectory,
            memory = SizedLruMemoryCache(maxSizeBytes = 1_024, sizeOf = String::length),
            download = download,
            decode = ByteArray::decodeToString,
        ),
        download = { _, _ -> error("detail loader should not be used") },
        decode = { error("detail decoder should not be used") },
    )

    private inline fun withTemporaryDirectory(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("thumbnail-cache-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}

private const val VERSIONED_THUMBNAIL_LOCATION =
    "gs://mystuff/households/household-1/items/item-1-123e4567-e89b-12d3-a456-426614174000-thumb.webp"
private const val FULL_PHOTO_LOCATION =
    "gs://mystuff/households/household-1/items/item-1-123e4567-e89b-12d3-a456-426614174000.webp"
