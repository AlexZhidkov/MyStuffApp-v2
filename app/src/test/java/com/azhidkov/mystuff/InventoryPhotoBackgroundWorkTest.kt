package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Test

class InventoryPhotoBackgroundWorkTest {
    @Test
    fun `full upload failure retries without preventing thumbnail success`() {
        val remote = FakePhotoRemoteStore(
            outcomes = mutableMapOf(
                "households/household-1/items/item-1.webp" to mutableListOf(
                    Result.failure(IllegalStateException("offline")),
                    Result.success(Unit),
                ),
                "households/household-1/items/item-1-thumb.webp" to mutableListOf(
                    Result.success(Unit),
                ),
            ),
        )
        val runner = PhotoTransferRunner(remote)

        val fullResult = runner.run(
            PhotoTransferTask.upload(
                storagePath = "households/household-1/items/item-1.webp",
                sourceUri = "content://mystuff/full.webp",
            ),
        )
        val thumbnailResult = runner.run(
            PhotoTransferTask.upload(
                storagePath = "households/household-1/items/item-1-thumb.webp",
                sourceUri = "content://mystuff/thumb.webp",
            ),
        )
        val retriedFullResult = runner.run(
            PhotoTransferTask.upload(
                storagePath = "households/household-1/items/item-1.webp",
                sourceUri = "content://mystuff/full.webp",
            ),
        )

        assertEquals(PhotoTransferResult.Retry, fullResult)
        assertEquals(PhotoTransferResult.Success, thumbnailResult)
        assertEquals(PhotoTransferResult.Success, retriedFullResult)
        assertEquals(2, remote.attempts["households/household-1/items/item-1.webp"])
        assertEquals(1, remote.attempts["households/household-1/items/item-1-thumb.webp"])
    }

    @Test
    fun `replacement removal subtree and Household cleanup address both variants`() {
        val queue = RecordingPhotoTransferQueue()
        val store = BackgroundInventoryPhotoStore(
            bucketUrl = "gs://mystuff",
            queue = queue,
        )

        store.uploadInBackground(
            householdId = "household-1",
            itemId = "item-1",
            photo = ItemPhoto("content://old-full.webp", "content://old-thumb.webp"),
        )
        store.uploadInBackground(
            householdId = "household-1",
            itemId = "item-1",
            photo = ItemPhoto("content://new-full.webp", "content://new-thumb.webp"),
        )

        assertEquals(
            listOf(
                PhotoTransferTask.upload(
                    "households/household-1/items/item-1.webp",
                    "content://new-full.webp",
                ),
                PhotoTransferTask.upload(
                    "households/household-1/items/item-1-thumb.webp",
                    "content://new-thumb.webp",
                ),
            ),
            queue.tasks,
        )

        store.deleteInBackground("household-1", listOf("item-1", "item-2"))

        assertEquals(
            listOf(
                PhotoTransferTask.delete("households/household-1/items/item-1.webp"),
                PhotoTransferTask.delete("households/household-1/items/item-1-thumb.webp"),
                PhotoTransferTask.delete("households/household-1/items/item-2.webp"),
                PhotoTransferTask.delete("households/household-1/items/item-2-thumb.webp"),
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
