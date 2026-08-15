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

internal sealed interface PhotoTransferTask {
    val storagePath: String

    data class Upload(
        override val storagePath: String,
        val sourceUri: String,
    ) : PhotoTransferTask

    data class Delete(
        override val storagePath: String,
    ) : PhotoTransferTask

    fun toWorkData(): Data {
        val builder = Data.Builder()
            .putString(
            OPERATION_KEY,
            when (this) {
                is Upload -> UPLOAD_OPERATION
                is Delete -> DELETE_OPERATION
            },
        )
            .putString(STORAGE_PATH_KEY, storagePath)
        if (this is Upload) builder.putString(SOURCE_URI_KEY, sourceUri)
        return builder.build()
    }

    companion object {
        fun fromWorkData(data: Data): PhotoTransferTask? = runCatching {
            val storagePath = requireNotNull(data.getString(STORAGE_PATH_KEY))
            when (requireNotNull(data.getString(OPERATION_KEY))) {
                UPLOAD_OPERATION -> Upload(
                    storagePath,
                    requireNotNull(data.getString(SOURCE_URI_KEY)),
                )
                DELETE_OPERATION -> Delete(storagePath)
                else -> error("Unknown photo transfer operation")
            }
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
            PhotoTransferTask.Upload(
                photoStoragePath(householdId, itemId, ItemPhotoVariant.Full),
                photo.uri,
            ),
        )
        queue.replace(
            PhotoTransferTask.Upload(
                photoStoragePath(householdId, itemId, ItemPhotoVariant.Thumbnail),
                photo.thumbnailUri,
            ),
        )
    }

    override fun deleteInBackground(householdId: String, itemIds: Collection<String>) {
        itemIds.forEach { itemId ->
            ItemPhotoVariant.entries.forEach { variant ->
                queue.replace(PhotoTransferTask.Delete(photoStoragePath(householdId, itemId, variant)))
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
        val result = when (task) {
            is PhotoTransferTask.Upload -> remoteStore.upload(
                task.storagePath,
                task.sourceUri,
            )
            is PhotoTransferTask.Delete -> remoteStore.delete(task.storagePath)
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
        if (result == PhotoTransferResult.Success && task is PhotoTransferTask.Upload) {
            runCatching {
                applicationContext.contentResolver.delete(
                    task.sourceUri.toUri(),
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
private const val UPLOAD_OPERATION = "upload"
private const val DELETE_OPERATION = "delete"
private const val WEBP_CONTENT_TYPE = "image/webp"
