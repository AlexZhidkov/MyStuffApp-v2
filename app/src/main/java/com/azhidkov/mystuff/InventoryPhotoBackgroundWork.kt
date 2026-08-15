package com.azhidkov.mystuff

import android.content.Context
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import java.util.concurrent.TimeUnit

internal enum class PhotoTransferOperation {
    Upload,
    Delete,
}

internal data class PhotoTransferTask(
    val operation: PhotoTransferOperation,
    val storagePath: String,
    val sourceUri: String?,
) {
    fun toWorkData(): Data = Data.Builder()
        .putString(OPERATION_KEY, operation.name)
        .putString(STORAGE_PATH_KEY, storagePath)
        .apply { sourceUri?.let { putString(SOURCE_URI_KEY, it) } }
        .build()

    companion object {
        fun upload(storagePath: String, sourceUri: String) = PhotoTransferTask(
            operation = PhotoTransferOperation.Upload,
            storagePath = storagePath,
            sourceUri = sourceUri,
        )

        fun delete(storagePath: String) = PhotoTransferTask(
            operation = PhotoTransferOperation.Delete,
            storagePath = storagePath,
            sourceUri = null,
        )

        fun fromWorkData(data: Data): PhotoTransferTask? = runCatching {
            val operation = PhotoTransferOperation.valueOf(requireNotNull(data.getString(OPERATION_KEY)))
            val storagePath = requireNotNull(data.getString(STORAGE_PATH_KEY))
            val sourceUri = data.getString(SOURCE_URI_KEY)
            if (operation == PhotoTransferOperation.Upload) requireNotNull(sourceUri)
            PhotoTransferTask(operation, storagePath, sourceUri)
        }.getOrNull()
    }
}

internal interface PhotoTransferQueue {
    fun replace(task: PhotoTransferTask)
}

internal class BackgroundInventoryPhotoStore(
    bucketUrl: String,
    private val queue: PhotoTransferQueue,
) : InventoryPhotoStore {
    private val bucketUrl = bucketUrl.trimEnd('/')

    override fun locations(householdId: String, itemId: String): ItemPhotoLocations =
        ItemPhotoLocations(
            full = "$bucketUrl/${photoStoragePath(householdId, itemId, ItemPhotoVariant.Full)}",
            thumbnail = "$bucketUrl/${photoStoragePath(
                householdId,
                itemId,
                ItemPhotoVariant.Thumbnail,
            )}",
        )

    override fun uploadInBackground(householdId: String, itemId: String, photo: ItemPhoto) {
        queue.replace(
            PhotoTransferTask.upload(
                photoStoragePath(householdId, itemId, ItemPhotoVariant.Full),
                photo.uri,
            ),
        )
        queue.replace(
            PhotoTransferTask.upload(
                photoStoragePath(householdId, itemId, ItemPhotoVariant.Thumbnail),
                photo.thumbnailUri,
            ),
        )
    }

    override fun deleteInBackground(householdId: String, itemIds: Collection<String>) {
        itemIds.forEach { itemId ->
            ItemPhotoVariant.entries.forEach { variant ->
                queue.replace(PhotoTransferTask.delete(photoStoragePath(householdId, itemId, variant)))
            }
        }
    }
}

internal class WorkManagerPhotoTransferQueue(context: Context) : PhotoTransferQueue {
    private val workManager = WorkManager.getInstance(context)

    override fun replace(task: PhotoTransferTask) {
        val request = OneTimeWorkRequestBuilder<InventoryPhotoTransferWorker>()
            .setInputData(task.toWorkData())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
        workManager.enqueueUniqueWork(
            "inventory-photo:${task.storagePath}",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

internal enum class PhotoTransferResult {
    Success,
    Retry,
}

internal interface PhotoRemoteStore {
    fun upload(storagePath: String, sourceUri: String): Result<Unit>
    fun delete(storagePath: String): Result<Unit>
}

internal class PhotoTransferRunner(
    private val remoteStore: PhotoRemoteStore,
) {
    fun run(task: PhotoTransferTask): PhotoTransferResult {
        val result = when (task.operation) {
            PhotoTransferOperation.Upload -> remoteStore.upload(
                task.storagePath,
                requireNotNull(task.sourceUri),
            )
            PhotoTransferOperation.Delete -> remoteStore.delete(task.storagePath)
        }
        return if (result.isSuccess) PhotoTransferResult.Success else PhotoTransferResult.Retry
    }
}

internal class InventoryPhotoTransferWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val task = PhotoTransferTask.fromWorkData(inputData) ?: return Result.failure()
        val result = PhotoTransferRunner(FirebasePhotoRemoteStore()).run(task)
        if (result == PhotoTransferResult.Success && task.operation == PhotoTransferOperation.Upload) {
            runCatching {
                applicationContext.contentResolver.delete(
                    requireNotNull(task.sourceUri).toUri(),
                    null,
                    null,
                )
            }
        }
        return when (result) {
            PhotoTransferResult.Success -> Result.success()
            PhotoTransferResult.Retry -> Result.retry()
        }
    }
}

private class FirebasePhotoRemoteStore(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) : PhotoRemoteStore {
    private val webPMetadata = StorageMetadata.Builder()
        .setContentType(WEBP_CONTENT_TYPE)
        .build()

    override fun upload(storagePath: String, sourceUri: String): Result<Unit> = runCatching {
        val upload = storage.reference.child(storagePath).putFile(sourceUri.toUri(), webPMetadata)
        try {
            Tasks.await(upload)
        } catch (failure: InterruptedException) {
            upload.cancel()
            throw failure
        }
    }.map { }

    override fun delete(storagePath: String): Result<Unit> = runCatching {
        Tasks.await(storage.reference.child(storagePath).delete())
    }.map { }.recoverCatching { failure ->
        if (
            failure !is StorageException ||
            failure.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND
        ) {
            throw failure
        }
    }
}

private const val OPERATION_KEY = "operation"
private const val STORAGE_PATH_KEY = "storage-path"
private const val SOURCE_URI_KEY = "source-uri"
private const val WEBP_CONTENT_TYPE = "image/webp"
