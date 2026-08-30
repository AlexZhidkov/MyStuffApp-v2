package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class InventoryPhotoBackgroundWorkTest {
    @Test
    fun `attachment-backed photo revisions use nested attachment locations`() {
        val store = BackgroundInventoryPhotoStore(
            bucketUrl = "gs://mystuff",
            queue = RecordingPhotoTransferQueue(),
        )

        val revision = store.newAttachmentRevision("household-1", "item-1", "attachment-1")

        assertEquals(
            "households/household-1/items/item-1/attachments/attachment-1.webp",
            revision.fullStoragePath,
        )
        assertEquals(
            "households/household-1/items/item-1/attachments/attachment-1-thumb.webp",
            revision.thumbnailStoragePath,
        )
        assertEquals("gs://mystuff/${revision.fullStoragePath}", revision.locations.full)
        assertEquals("gs://mystuff/${revision.thumbnailStoragePath}", revision.locations.thumbnail)
    }

    @Test
    fun `new photo revisions use distinct random UUIDs`() {
        val store = BackgroundInventoryPhotoStore(
            bucketUrl = "gs://mystuff",
            queue = RecordingPhotoTransferQueue(),
        )

        val first = store.newRevision("household-1", "item-1")
        val second = store.newRevision("household-1", "item-1")
        val firstRevisionId = first.fullStoragePath
            .substringAfter("item-1-")
            .substringBefore(".webp")
        val secondRevisionId = second.fullStoragePath
            .substringAfter("item-1-")
            .substringBefore(".webp")

        UUID.fromString(firstRevisionId)
        UUID.fromString(secondRevisionId)
        assertNotEquals(firstRevisionId, secondRevisionId)
    }

    @Test
    fun `failed upload is terminal and does not retry automatically`() {
        val revision = "11111111-1111-1111-1111-111111111111"
        val fullPath = "households/household-1/items/item-1-$revision.webp"
        val thumbnailPath = "households/household-1/items/item-1-$revision-thumb.webp"
        val remote = FakePhotoRemoteStore(
            outcomes = mutableMapOf(
                fullPath to mutableListOf(
                    Result.failure(IllegalStateException("offline")),
                ),
                thumbnailPath to mutableListOf(
                    Result.success(Unit),
                ),
            ),
        )
        val runner = PhotoTransferRunner(remote)

        val fullResult = runner.run(
            PhotoTransferTask.Upload(
                storagePath = fullPath,
                sourceUri = "content://mystuff/full.webp",
            ),
        )
        val thumbnailResult = runner.run(
            PhotoTransferTask.Upload(
                storagePath = thumbnailPath,
                sourceUri = "content://mystuff/thumb.webp",
            ),
        )
        assertEquals(PhotoTransferResult.Failure, fullResult)
        assertEquals(PhotoTransferResult.Success, thumbnailResult)
        assertEquals(1, remote.attempts[fullPath])
        assertEquals(1, remote.attempts[thumbnailPath])
    }

    @Test
    fun `attachment upload failure metadata survives WorkManager data round trips`() {
        val failure = AttachmentUploadFailure(
            id = "attachment-1",
            householdId = "household-1",
            itemId = "item-1",
            attachmentId = "attachment-1",
            originatingMemberId = "member-1",
            displayStoragePath = "households/household-1/items/item-1/attachments/attachment-1.webp",
            thumbnailStoragePath = "households/household-1/items/item-1/attachments/attachment-1-thumb.webp",
        )
        val task = PhotoTransferTask.Upload(
            storagePath = failure.displayStoragePath,
            sourceUri = "content://mystuff/full.webp",
            additionalUploads = listOf(
                PhotoTransferTask.UploadPart(
                    failure.thumbnailStoragePath,
                    "content://mystuff/thumb.webp",
                ),
            ),
            uploadFailure = failure,
        )

        assertEquals(task, PhotoTransferTask.fromWorkData(task.toWorkData()))
    }

    @Test
    fun `one failed attachment does not prevent sibling transfers from being queued`() {
        val queue = RecordingPhotoTransferQueue()
        val store = BackgroundInventoryPhotoStore("gs://mystuff", queue)
        val first = store.newAttachmentRevision("household-1", "item-1", "first")
        val second = store.newAttachmentRevision("household-1", "item-1", "second")

        store.uploadDisplayInBackground(first, ItemPhoto("content://first.webp"))
        store.uploadDisplayInBackground(second, ItemPhoto("content://second.webp"))

        assertEquals(2, queue.tasks.size)
    }

    @Test
    fun `failure registry exposes retry and remove only for the current process`() {
        val registry = AttachmentUploadFailureRegistry()
        val failure = AttachmentUploadFailure(
            id = "attachment-1",
            householdId = "household-1",
            itemId = "item-1",
            attachmentId = "attachment-1",
            originatingMemberId = "member-1",
            displayStoragePath = "display.webp",
            thumbnailStoragePath = "thumb.webp",
        )
        val events = mutableListOf<List<FailedItemAttachmentDraft>>()
        val cleaned = mutableListOf<String>()
        var retries = 0
        registry.sourceCleaner = { cleaned += it }
        val subscription = registry.observe { events += it }
        registry.prepare(
            failure = failure,
            sourceUris = listOf("content://full", "content://thumb"),
            retry = { retries += 1 },
        )
        registry.markFailed(failure, IllegalStateException("offline"))

        assertEquals("attachment-1", events.last().single().attachmentId)
        registry.retry("attachment-1")
        assertEquals(1, retries)
        assertTrue(events.last().isEmpty())

        registry.markFailed(failure, IllegalStateException("still offline"))
        registry.remove("attachment-1")
        assertEquals(listOf("content://full", "content://thumb"), cleaned)
        assertTrue(events.last().isEmpty())
        subscription.cancel()
    }

    @Test
    fun `each prepared photo gets one shared immutable revision for both variants`() {
        val revisions = ArrayDeque(
            listOf(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
            ),
        )
        val queue = RecordingPhotoTransferQueue()
        val store = BackgroundInventoryPhotoStore(
            bucketUrl = "gs://mystuff",
            queue = queue,
            newRevisionId = revisions::removeFirst,
        )

        val oldRevision = store.newRevision("household-1", "item-1")
        val newRevision = store.newRevision("household-1", "item-1")

        store.uploadInBackground(
            revision = oldRevision,
            photo = ItemPhoto("content://old-full.webp", "content://old-thumb.webp"),
        )
        store.uploadInBackground(
            revision = newRevision,
            photo = ItemPhoto("content://new-full.webp", "content://new-thumb.webp"),
        )

        assertEquals(
            listOf(
                PhotoTransferTask.Upload(
                    storagePath = "households/household-1/items/item-1-11111111-1111-1111-1111-111111111111.webp",
                    sourceUri = "content://old-full.webp",
                    additionalUploads = listOf(
                        PhotoTransferTask.UploadPart(
                            "households/household-1/items/item-1-11111111-1111-1111-1111-111111111111-thumb.webp",
                            "content://old-thumb.webp",
                        ),
                    ),
                ),
                PhotoTransferTask.Upload(
                    storagePath = "households/household-1/items/item-1-22222222-2222-2222-2222-222222222222.webp",
                    sourceUri = "content://new-full.webp",
                    additionalUploads = listOf(
                        PhotoTransferTask.UploadPart(
                            "households/household-1/items/item-1-22222222-2222-2222-2222-222222222222-thumb.webp",
                            "content://new-thumb.webp",
                        ),
                    ),
                ),
            ),
            queue.tasks,
        )

        assertEquals(
            "gs://mystuff/${newRevision.fullStoragePath}",
            newRevision.locations.full,
        )
        assertEquals(
            "gs://mystuff/${newRevision.thumbnailStoragePath}",
            newRevision.locations.thumbnail,
        )
    }

    @Test
    fun `explicit removal queues the exact current versioned or legacy locations`() {
        val queue = RecordingPhotoTransferQueue()
        val store = BackgroundInventoryPhotoStore(
            bucketUrl = "gs://mystuff",
            queue = queue,
        )

        store.deleteInBackground(
            StoredItemPhotoLocations(
                full = "gs://mystuff/households/household-1/items/item-1-$REVISION.webp",
                thumbnail = "gs://mystuff/households/household-1/items/item-1-$REVISION-thumb.webp",
            ),
        )
        store.deleteInBackground(
            StoredItemPhotoLocations(
                full = "gs://mystuff/households/household-1/items/item-2.webp",
                thumbnail = "gs://mystuff/households/household-1/items/item-2-thumb.webp",
            ),
        )

        assertEquals(
            listOf(
                PhotoTransferTask.Delete(
                    "households/household-1/items/item-1-$REVISION.webp",
                ),
                PhotoTransferTask.Delete(
                    "households/household-1/items/item-1-$REVISION-thumb.webp",
                ),
                PhotoTransferTask.Delete("households/household-1/items/item-2.webp"),
                PhotoTransferTask.Delete("households/household-1/items/item-2-thumb.webp"),
            ),
            queue.tasks,
        )
    }

    @Test
    fun `designating an attachment queues thumbnail generation from its display image`() {
        val queue = RecordingPhotoTransferQueue()
        val store = BackgroundInventoryPhotoStore(
            bucketUrl = "gs://mystuff",
            queue = queue,
        )
        val revision = store.newAttachmentRevision("household-1", "item-1", "attachment-1")

        store.generateAttachmentThumbnailInBackground(
            revision = revision,
            sourceLocation = "gs://mystuff/${revision.fullStoragePath}",
        )

        assertEquals(
            listOf(
                PhotoTransferTask.GenerateThumbnail(
                    storagePath = revision.thumbnailStoragePath,
                    sourceLocation = "gs://mystuff/${revision.fullStoragePath}",
                ),
            ),
            queue.tasks,
        )
    }

    @Test
    fun `thumbnail generation tasks survive WorkManager data round trips`() {
        val task = PhotoTransferTask.GenerateThumbnail(
            storagePath = "households/household-1/items/item-1/attachments/attachment-1-thumb.webp",
            sourceLocation = "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp",
        )

        assertEquals(task, PhotoTransferTask.fromWorkData(task.toWorkData()))
    }
}

private class FakePhotoRemoteStore(
    private val outcomes: MutableMap<String, MutableList<Result<Unit>>>,
) : PhotoRemoteStore {
    val attempts = mutableMapOf<String, Int>()

    override fun upload(storagePath: String, sourceUri: String): Result<Unit> {
        attempts[storagePath] = attempts.getOrDefault(storagePath, 0) + 1
        return outcomes.getValue(storagePath).removeFirst()
    }

    override fun delete(storagePath: String): Result<Unit> = Result.success(Unit)
}

private class RecordingPhotoTransferQueue : PhotoTransferQueue {
    private val tasksByStoragePath = linkedMapOf<String, PhotoTransferTask>()
    val tasks: List<PhotoTransferTask>
        get() = tasksByStoragePath.values.toList()

    override fun replace(task: PhotoTransferTask) {
        tasksByStoragePath[task.storagePath] = task
    }
}

private const val REVISION = "11111111-1111-1111-1111-111111111111"
