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
import java.util.UUID
import java.util.concurrent.TimeUnit

internal sealed interface PhotoTransferTask {
    val storagePath: String
    val operationName: String

    fun execute(remoteStore: PhotoRemoteStore): Result<Unit>
    fun cleanUpLocalSource(context: Context)

    data class Upload(
        override val storagePath: String,
        val sourceUri: String,
    ) : PhotoTransferTask {
        override val operationName = UPLOAD_OPERATION

        override fun execute(remoteStore: PhotoRemoteStore): Result<Unit> =
            remoteStore.upload(storagePath, sourceUri)

        override fun cleanUpLocalSource(context: Context) {
            runCatching {
                context.contentResolver.delete(sourceUri.toUri(), null, null)
            }
        }
    }

    data class Delete(
        override val storagePath: String,
    ) : PhotoTransferTask {
        override val operationName = DELETE_OPERATION

        override fun execute(remoteStore: PhotoRemoteStore): Result<Unit> =
            remoteStore.delete(storagePath)

        override fun cleanUpLocalSource(context: Context) = Unit
    }

    fun toWorkData(): Data {
        val builder = Data.Builder()
            .putString(
            OPERATION_KEY,
            operationName,
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
    private val newRevisionId: () -> UUID = UUID::randomUUID,
) : InventoryPhotoStore {
    private val bucketUrl = bucketUrl.trimEnd('/')

    override fun newRevision(householdId: String, itemId: String): ItemPhotoRevision {
        val revisionId = newRevisionId()
        val fullStoragePath = photoStoragePath(
            householdId,
            itemId,
            revisionId,
            ItemPhotoVariant.Full,
        )
        val thumbnailStoragePath = photoStoragePath(
            householdId,
            itemId,
            revisionId,
            ItemPhotoVariant.Thumbnail,
        )
        return ItemPhotoRevision(
            locations = ItemPhotoLocations(
                full = "$bucketUrl/$fullStoragePath",
                thumbnail = "$bucketUrl/$thumbnailStoragePath",
            ),
            fullStoragePath = fullStoragePath,
            thumbnailStoragePath = thumbnailStoragePath,
        )
    }

    override fun newAttachmentRevision(
        householdId: String,
        itemId: String,
        attachmentId: String,
    ): ItemPhotoRevision {
        val fullStoragePath = itemAttachmentStoragePath(householdId, itemId, attachmentId)
        val thumbnailStoragePath = itemAttachmentThumbnailStoragePath(
            householdId,
            itemId,
            attachmentId,
        )
        return ItemPhotoRevision(
            locations = ItemPhotoLocations(
                full = "$bucketUrl/$fullStoragePath",
                thumbnail = "$bucketUrl/$thumbnailStoragePath",
            ),
            fullStoragePath = fullStoragePath,
            thumbnailStoragePath = thumbnailStoragePath,
        )
    }

    override fun uploadInBackground(revision: ItemPhotoRevision, photo: ItemPhoto) {
        queueUpload(revision.fullStoragePath, photo.uri)
        queueUpload(revision.thumbnailStoragePath, photo.thumbnailUri)
    }

    override fun uploadDisplayInBackground(revision: ItemPhotoRevision, photo: ItemPhoto) {
        queueUpload(revision.fullStoragePath, photo.uri)
    }

    override fun uploadThumbnailInBackground(revision: ItemPhotoRevision, photo: ItemPhoto) {
        queueUpload(revision.thumbnailStoragePath, photo.thumbnailUri)
    }

    private fun queueUpload(storagePath: String, sourceUri: String) {
        queue.replace(
            PhotoTransferTask.Upload(
                storagePath,
                sourceUri,
            ),
        )
    }

    override fun deleteInBackground(locations: StoredItemPhotoLocations) {
        locations.presentLocations().forEach { location ->
            require(location.startsWith("$bucketUrl/")) {
                "Item photo location does not belong to the configured Firebase Storage bucket"
            }
            queue.replace(
                PhotoTransferTask.Delete(location.removePrefix("$bucketUrl/")),
            )
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
        val result = task.execute(remoteStore)
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
        if (result == PhotoTransferResult.Success) {
            task.cleanUpLocalSource(applicationContext)
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
