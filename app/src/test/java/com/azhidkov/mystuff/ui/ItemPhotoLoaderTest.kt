package com.azhidkov.mystuff.ui

import com.azhidkov.mystuff.Household
import com.azhidkov.mystuff.Inventory
import com.azhidkov.mystuff.Item
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemPhotoLoaderTest {
    @Test
    fun `a late request cannot repopulate a cleared session cache`() = runBlocking {
        val root = Files.createTempDirectory("item-photo-loader").toFile()
        try {
            val lateDownload = CompletableDeferred<kotlin.coroutines.Continuation<ByteArray>>()
            var downloads = 0
            val loader = testLoader(
                root = root,
                thumbnailDownload = {
                    downloads += 1
                    if (downloads == 1) {
                        suspendCoroutine { lateDownload.complete(it) }
                    } else {
                        "new-member-thumbnail".encodeToByteArray()
                    }
                },
            )
            val request = ItemPhotoRequest.Stored(
                location = TEST_THUMBNAIL_LOCATION,
                presentation = ItemPhotoPresentation.Compact,
            )
            loader.onSessionChanged("member-a")
            val oldRequest = launch { loader.states(request).collect() }
            val continuation = lateDownload.await()

            loader.onSessionChanged(null)
            continuation.resume("old-member-thumbnail".encodeToByteArray())
            oldRequest.join()
            loader.onSessionChanged("member-b")

            val available = loader.states(request)
                .filterIsInstance<PhotoLoadState.Available<String>>()
                .first()

            assertEquals("new-member-thumbnail", available.value)
            assertEquals(2, downloads)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a late preview cannot replace a full Item Photo`() = runBlocking {
        val root = Files.createTempDirectory("item-photo-loader").toFile()
        try {
            val previewLocation = "$TEST_THUMBNAIL_LOCATION-preview"
            val thumbnailDirectory = root.resolve("thumbnails").also { it.mkdirs() }
            thumbnailDirectory.resolve(thumbnailCacheFileName(previewLocation))
                .writeText("preview")
            val previewDecodeStarted = CompletableDeferred<Unit>()
            val releasePreview = CompletableDeferred<Unit>()
            val loader = testLoader(
                root = root,
                thumbnailDirectory = thumbnailDirectory,
                thumbnailDownload = { error("The cached preview should be used") },
                thumbnailDecode = { bytes ->
                    previewDecodeStarted.complete(Unit)
                    releasePreview.await()
                    bytes.decodeToString()
                },
                attachmentDownload = { "full-photo".encodeToByteArray() },
            )
            val fullAvailable = CompletableDeferred<Unit>()
            val states = async {
                loader.states(
                    ItemPhotoRequest.AttachmentDisplay(
                        location = TEST_DISPLAY_LOCATION,
                        previewLocation = previewLocation,
                    ),
                ).onEach {
                    if (
                        it is PhotoLoadState.Available &&
                        it.resolution == PhotoResolution.Full
                    ) {
                        fullAvailable.complete(Unit)
                    }
                }.toList()
            }
            previewDecodeStarted.await()
            fullAvailable.await()

            releasePreview.complete(Unit)

            assertEquals(
                listOf(
                    PhotoLoadState.Loading,
                    PhotoLoadState.Available("full-photo", PhotoResolution.Full),
                ),
                states.await(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a late display request cannot repopulate an evicted attachment`() = runBlocking {
        val root = Files.createTempDirectory("item-photo-loader").toFile()
        try {
            val lateDownload = CompletableDeferred<kotlin.coroutines.Continuation<ByteArray>>()
            var downloads = 0
            val loader = testLoader(
                root = root,
                attachmentDownload = {
                    downloads += 1
                    if (downloads == 1) {
                        suspendCoroutine { lateDownload.complete(it) }
                    } else {
                        "current-display".encodeToByteArray()
                    }
                },
            )
            val request = ItemPhotoRequest.AttachmentDisplay(TEST_DISPLAY_LOCATION)
            val oldRequest = launch { loader.states(request).collect() }
            val continuation = lateDownload.await()

            loader.evictAttachmentDisplay(TEST_DISPLAY_LOCATION)
            continuation.resume("obsolete-display".encodeToByteArray())
            oldRequest.join()

            assertEquals(
                "current-display",
                loader.states(request)
                    .filterIsInstance<PhotoLoadState.Available<String>>()
                    .first()
                    .value,
            )
            assertEquals(2, downloads)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `observing a replacement evicts the obsolete thumbnail revision`() = runBlocking {
        val root = Files.createTempDirectory("item-photo-loader").toFile()
        try {
            var downloads = 0
            val loader = testLoader(
                root = root,
                thumbnailDownload = {
                    downloads += 1
                    "download-$downloads".encodeToByteArray()
                },
            )
            val oldRequest = ItemPhotoRequest.Stored(
                location = TEST_THUMBNAIL_LOCATION,
                presentation = ItemPhotoPresentation.Compact,
            )
            loader.onInventoryChanged(inventoryWithThumbnail(TEST_THUMBNAIL_LOCATION))
            assertEquals(
                "download-1",
                loader.states(oldRequest)
                    .filterIsInstance<PhotoLoadState.Available<String>>()
                    .first()
                    .value,
            )

            loader.onInventoryChanged(inventoryWithThumbnail("$TEST_THUMBNAIL_LOCATION-new"))

            assertEquals(
                "download-2",
                loader.states(oldRequest)
                    .filterIsInstance<PhotoLoadState.Available<String>>()
                    .first()
                    .value,
            )
            assertEquals(2, downloads)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `observing Item Photo removal evicts a legacy thumbnail location`() = runBlocking {
        val root = Files.createTempDirectory("item-photo-loader").toFile()
        try {
            var downloads = 0
            val loader = testLoader(
                root = root,
                thumbnailDownload = {
                    downloads += 1
                    "legacy-download-$downloads".encodeToByteArray()
                },
            )
            val request = ItemPhotoRequest.Stored(
                location = LEGACY_THUMBNAIL_LOCATION,
                presentation = ItemPhotoPresentation.Compact,
            )
            loader.onInventoryChanged(inventoryWithThumbnail(LEGACY_THUMBNAIL_LOCATION))
            loader.states(request).filterIsInstance<PhotoLoadState.Available<String>>().first()

            loader.onInventoryChanged(inventoryWithThumbnail(null))

            assertEquals(
                "legacy-download-2",
                loader.states(request)
                    .filterIsInstance<PhotoLoadState.Available<String>>()
                    .first()
                    .value,
            )
            assertEquals(2, downloads)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `authentication changes clear cached attachment displays`() = runBlocking {
        val root = Files.createTempDirectory("item-photo-loader").toFile()
        try {
            var downloads = 0
            val loader = testLoader(
                root = root,
                attachmentDownload = {
                    downloads += 1
                    "display-$downloads".encodeToByteArray()
                },
            )
            val request = ItemPhotoRequest.AttachmentDisplay(TEST_DISPLAY_LOCATION)
            loader.onSessionChanged("member-a")
            val first = loader.states(request)
                .filterIsInstance<PhotoLoadState.Available<String>>()
                .first()

            loader.onSessionChanged(null)
            loader.onSessionChanged("member-b")

            val second = loader.states(request)
                .filterIsInstance<PhotoLoadState.Available<String>>()
                .first()
            assertEquals("display-1", first.value)
            assertEquals("display-2", second.value)
            assertEquals(2, downloads)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `persisted session identity clears thumbnails after an offline identity change`() =
        runBlocking {
            val root = Files.createTempDirectory("item-photo-loader").toFile()
            try {
                var downloads = 0
                fun loader() = testLoader(
                    root = root,
                    thumbnailDownload = {
                        downloads += 1
                        "member-thumbnail-$downloads".encodeToByteArray()
                    },
                    sessionIdentityFile = root.resolve("session-identity"),
                )
                val request = ItemPhotoRequest.Stored(
                    TEST_THUMBNAIL_LOCATION,
                    ItemPhotoPresentation.Compact,
                )
                val firstSession = loader()
                firstSession.onSessionChanged("member-a")
                firstSession.states(request)
                    .filterIsInstance<PhotoLoadState.Available<String>>()
                    .first()

                val changedSession = loader()
                changedSession.onSessionChanged("member-b")

                assertEquals(
                    "member-thumbnail-2",
                    changedSession.states(request)
                        .filterIsInstance<PhotoLoadState.Available<String>>()
                        .first()
                        .value,
                )
                assertEquals(2, downloads)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `stored photo retries use bounded exponential delays`() = runBlocking {
        val root = Files.createTempDirectory("item-photo-loader").toFile()
        try {
            var attempts = 0
            val delays = mutableListOf<Long>()
            val loader = testLoader(
                root = root,
                thumbnailDownload = {
                    attempts += 1
                    if (attempts <= 6) error("Firebase unavailable")
                    "decoded-photo".encodeToByteArray()
                },
                waitForRetry = delays::add,
            )

            val states = loader.states(
                ItemPhotoRequest.Stored(
                    TEST_THUMBNAIL_LOCATION,
                    ItemPhotoPresentation.Compact,
                ),
            ).toList()

            assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)
            assertEquals(
                listOf(
                    PhotoLoadState.Loading,
                    PhotoLoadState.Unavailable,
                    PhotoLoadState.Available("decoded-photo"),
                ),
                states,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `detail requests bypass thumbnail caches`() = runBlocking {
        val root = Files.createTempDirectory("item-photo-loader").toFile()
        try {
            var thumbnailDownloads = 0
            var detailDownloads = 0
            val loader = testLoader(
                root = root,
                thumbnailDownload = {
                    thumbnailDownloads += 1
                    "thumbnail".encodeToByteArray()
                },
                storedPhotoDownload = { _, maxBytes ->
                    assertEquals(MAX_FULL_PHOTO_DOWNLOAD_BYTES, maxBytes)
                    detailDownloads += 1
                    "full-photo-$detailDownloads".encodeToByteArray()
                },
            )
            val request = ItemPhotoRequest.Stored(
                TEST_DISPLAY_LOCATION,
                ItemPhotoPresentation.Detail,
            )

            val first = loader.states(request)
                .filterIsInstance<PhotoLoadState.Available<String>>()
                .first()
            val second = loader.states(request)
                .filterIsInstance<PhotoLoadState.Available<String>>()
                .first()

            assertEquals("full-photo-1", first.value)
            assertEquals("full-photo-2", second.value)
            assertEquals(0, thumbnailDownloads)
            assertEquals(2, detailDownloads)
            assertEquals(emptyList<String>(), root.resolve("thumbnails").list().orEmpty().toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `carousel preparation eagerly downloads every display image`() = runBlocking {
        val root = Files.createTempDirectory("item-photo-loader").toFile()
        try {
            val downloaded = mutableSetOf<String>()
            val loader = testLoader(
                root = root,
                attachmentDownload = { location ->
                    synchronized(downloaded) { downloaded += location }
                    location.encodeToByteArray()
                },
            )
            val locations = listOf("display-1", "display-2", "display-3")

            loader.prepareAttachmentDisplays(locations)

            assertEquals(locations.toSet(), downloaded)
        } finally {
            root.deleteRecursively()
        }
    }
}

private fun testLoader(
    root: File,
    thumbnailDirectory: File = root.resolve("thumbnails"),
    thumbnailDownload: suspend (String) -> ByteArray = {
        error("Thumbnail should not be loaded")
    },
    thumbnailDecode: suspend (ByteArray) -> String = ByteArray::decodeToString,
    attachmentDownload: suspend (String) -> ByteArray = {
        error("Attachment display should not be loaded")
    },
    storedPhotoDownload: suspend (String, Long) -> ByteArray = { _, _ ->
        error("Stored photo should not be loaded")
    },
    waitForRetry: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    sessionIdentityFile: File? = null,
): ItemPhotoLoader<String> = ItemPhotoLoader(
    thumbnails = ThumbnailCache(
        directory = thumbnailDirectory,
        memory = SizedLruMemoryCache(1_024, String::length),
        download = thumbnailDownload,
        decode = thumbnailDecode,
    ),
    attachmentDisplays = AttachmentDisplayPhotoCache(
        directory = root.resolve("displays"),
        download = attachmentDownload,
        decode = ByteArray::decodeToString,
    ),
    downloadStoredPhoto = storedPhotoDownload,
    decode = ByteArray::decodeToString,
    waitForRetry = waitForRetry,
    sessionIdentityFile = sessionIdentityFile,
)

private fun inventoryWithThumbnail(location: String?): Inventory {
    val household = Household(
        id = "household-1",
        ownerMemberId = "member-1",
        rootItem = Item(
            id = "household-1",
            name = "Our Home",
            parentItemId = null,
            photoUrl = null,
            description = null,
            tags = emptyList(),
        ),
    )
    val item = Item(
        id = "item-1",
        name = "Drill",
        parentItemId = household.id,
        photoUrl = location?.removeSuffix("-thumb.webp")?.plus(".webp"),
        description = null,
        tags = emptyList(),
        photoThumbnailUrl = location,
    )
    return Inventory.from(household, listOf(household.rootItem, item))
}

private const val TEST_THUMBNAIL_LOCATION =
    "gs://mystuff/households/household-1/items/item-1/revisions/old-thumb.webp"
private const val TEST_DISPLAY_LOCATION =
    "gs://mystuff/households/household-1/items/item-1/attachments/photo.webp"
private const val LEGACY_THUMBNAIL_LOCATION =
    "gs://mystuff/households/household-1/items/item-1-thumb.webp"
