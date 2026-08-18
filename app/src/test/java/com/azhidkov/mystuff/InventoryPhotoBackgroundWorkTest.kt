package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

class InventoryPhotoBackgroundWorkTest {
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
    fun `versioned full upload retries independently without preventing thumbnail success`() {
        val revision = "11111111-1111-1111-1111-111111111111"
        val fullPath = "households/household-1/items/item-1-$revision.webp"
        val thumbnailPath = "households/household-1/items/item-1-$revision-thumb.webp"
        val remote = FakePhotoRemoteStore(
            outcomes = mutableMapOf(
                fullPath to mutableListOf(
                    Result.failure(IllegalStateException("offline")),
                    Result.success(Unit),
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
        val retriedFullResult = runner.run(
            PhotoTransferTask.Upload(
                storagePath = fullPath,
                sourceUri = "content://mystuff/full.webp",
            ),
        )

        assertEquals(PhotoTransferResult.Retry, fullResult)
        assertEquals(PhotoTransferResult.Success, thumbnailResult)
        assertEquals(PhotoTransferResult.Success, retriedFullResult)
        assertEquals(2, remote.attempts[fullPath])
        assertEquals(1, remote.attempts[thumbnailPath])
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
                    "households/household-1/items/item-1-11111111-1111-1111-1111-111111111111.webp",
                    "content://old-full.webp",
                ),
                PhotoTransferTask.Upload(
                    "households/household-1/items/item-1-11111111-1111-1111-1111-111111111111-thumb.webp",
                    "content://old-thumb.webp",
                ),
                PhotoTransferTask.Upload(
                    "households/household-1/items/item-1-22222222-2222-2222-2222-222222222222.webp",
                    "content://new-full.webp",
                ),
                PhotoTransferTask.Upload(
                    "households/household-1/items/item-1-22222222-2222-2222-2222-222222222222-thumb.webp",
                    "content://new-thumb.webp",
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
