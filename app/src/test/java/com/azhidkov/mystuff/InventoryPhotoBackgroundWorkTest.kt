package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `stored photo locations identify their exact display upload work`() {
        val store = BackgroundInventoryPhotoStore(
            bucketUrl = "gs://mystuff",
            queue = RecordingPhotoTransferQueue(),
        )
        val revision = store.newAttachmentRevision("household-1", "item-1", "attachment-1")

        assertEquals(
            "inventory-photo:${revision.fullStoragePath}",
            store.displayUploadWorkName(revision.locations.full),
        )
        assertEquals(
            null,
            store.displayUploadWorkName("gs://another-bucket/${revision.fullStoragePath}"),
        )
        assertEquals(
            null,
            store.displayUploadWorkName("gs://mystuff/households/household-1/items/item-1.webp"),
        )
    }

    @Test
    fun `persisted flat upload work is discarded`() {
        val legacyTask = PhotoTransferTask.Upload(
            storagePath = "households/household-1/items/item-1.webp",
            sourceUri = "content://mystuff/legacy.webp",
        )

        assertNull(PhotoTransferTask.fromWorkData(legacyTask.toWorkData()))
    }

    @Test
    fun `failed upload is terminal and does not retry automatically`() {
        val fullPath = "households/household-1/items/item-1/attachments/attachment-1.webp"
        val thumbnailPath =
            "households/household-1/items/item-1/attachments/attachment-1-thumb.webp"
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
    fun `display upload completes before its thumbnail follow-up`() {
        val fullPath = "households/household-1/items/item-1/attachments/attachment-1.webp"
        val thumbnailPath =
            "households/household-1/items/item-1/attachments/attachment-1-thumb.webp"
        val remote = FakePhotoRemoteStore(
            outcomes = mutableMapOf(
                fullPath to mutableListOf(Result.success(Unit)),
                thumbnailPath to mutableListOf(Result.success(Unit)),
            ),
        )
        val task = PhotoTransferTask.Upload(
            storagePath = fullPath,
            sourceUri = "content://mystuff/full.webp",
            additionalUploads = listOf(
                PhotoTransferTask.UploadPart(thumbnailPath, "content://mystuff/thumb.webp"),
            ),
        )
        val runner = PhotoTransferRunner(remote)

        assertEquals(PhotoTransferResult.Success, runner.run(task))
        assertEquals(1, remote.attempts[fullPath])
        assertEquals(null, remote.attempts[thumbnailPath])

        assertEquals(
            PhotoTransferResult.Success,
            runner.run(task.followUpUploads().single()),
        )
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
    fun `each attachment upload uses its immutable nested location for both variants`() {
        val queue = RecordingPhotoTransferQueue()
        val store = BackgroundInventoryPhotoStore(
            bucketUrl = "gs://mystuff",
            queue = queue,
        )

        val oldRevision = store.newAttachmentRevision("household-1", "item-1", "attachment-1")
        val newRevision = store.newAttachmentRevision("household-1", "item-1", "attachment-2")

        store.uploadAttachmentInBackground(
            revision = oldRevision,
            photo = ItemPhoto("content://old-full.webp", "content://old-thumb.webp"),
        )
        store.uploadAttachmentInBackground(
            revision = newRevision,
            photo = ItemPhoto("content://new-full.webp", "content://new-thumb.webp"),
        )

        assertEquals(
            listOf(
                PhotoTransferTask.Upload(
                    storagePath = "households/household-1/items/item-1/attachments/attachment-1.webp",
                    sourceUri = "content://old-full.webp",
                    additionalUploads = listOf(
                        PhotoTransferTask.UploadPart(
                            "households/household-1/items/item-1/attachments/attachment-1-thumb.webp",
                            "content://old-thumb.webp",
                        ),
                    ),
                ),
                PhotoTransferTask.Upload(
                    storagePath = "households/household-1/items/item-1/attachments/attachment-2.webp",
                    sourceUri = "content://new-full.webp",
                    additionalUploads = listOf(
                        PhotoTransferTask.UploadPart(
                            "households/household-1/items/item-1/attachments/attachment-2-thumb.webp",
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
                full = "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp",
                thumbnail = "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1-thumb.webp",
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
                    "households/household-1/items/item-1/attachments/attachment-1.webp",
                ),
                PhotoTransferTask.Delete(
                    "households/household-1/items/item-1/attachments/attachment-1-thumb.webp",
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
