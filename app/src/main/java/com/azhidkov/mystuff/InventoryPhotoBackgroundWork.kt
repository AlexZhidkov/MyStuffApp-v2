package com.azhidkov.mystuff

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

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

    data class GenerateThumbnail(
        override val storagePath: String,
        val sourceLocation: String,
    ) : PhotoTransferTask {
        override val operationName = GENERATE_THUMBNAIL_OPERATION

        override fun execute(remoteStore: PhotoRemoteStore): Result<Unit> =
            remoteStore.generateThumbnail(storagePath, sourceLocation)

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
        if (this is GenerateThumbnail) builder.putString(SOURCE_LOCATION_KEY, sourceLocation)
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
                GENERATE_THUMBNAIL_OPERATION -> GenerateThumbnail(
                    storagePath,
                    requireNotNull(data.getString(SOURCE_LOCATION_KEY)),
                )
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

    override fun generateAttachmentThumbnailInBackground(
        revision: ItemPhotoRevision,
        sourceLocation: String,
    ) {
        queue.replace(
            PhotoTransferTask.GenerateThumbnail(
                storagePath = revision.thumbnailStoragePath,
                sourceLocation = sourceLocation,
            ),
        )
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
    fun generateThumbnail(storagePath: String, sourceLocation: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Thumbnail generation is unavailable."))
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

    override fun generateThumbnail(storagePath: String, sourceLocation: String): Result<Unit> =
        runCatching {
            val sourceBytes = Tasks.await(
                storage.getReferenceFromUrl(sourceLocation).getBytes(MAX_ATTACHMENT_DISPLAY_BYTES),
            )
            val source = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
                ?: error("Attachment display image could not be decoded")
            val (width, height) = attachmentThumbnailDimensions(source.width, source.height)
            var thumbnail = if (width == source.width && height == source.height) {
                source
            } else {
                Bitmap.createScaledBitmap(source, width, height, true)
            }
            val file = File.createTempFile("item-attachment-thumb", ".webp")
            try {
                var quality = ATTACHMENT_THUMBNAIL_QUALITY
                while (true) {
                    file.outputStream().use { output ->
                        check(thumbnail.compress(webPFormat(), quality, output))
                    }
                    if (file.length() <= MAX_ATTACHMENT_THUMBNAIL_BYTES) break
                    if (quality > MIN_ATTACHMENT_THUMBNAIL_QUALITY) {
                        quality -= ATTACHMENT_THUMBNAIL_QUALITY_STEP
                    } else if (maxOf(thumbnail.width, thumbnail.height) > 1) {
                        val longestSide = maxOf(thumbnail.width, thumbnail.height)
                        val nextSide = (longestSide * THUMBNAIL_SIZE_REDUCTION).roundToInt()
                            .coerceAtLeast(1)
                        val smaller = Bitmap.createScaledBitmap(
                            thumbnail,
                            (thumbnail.width * nextSide.toFloat() / longestSide)
                                .roundToInt()
                                .coerceAtLeast(1),
                            (thumbnail.height * nextSide.toFloat() / longestSide)
                                .roundToInt()
                                .coerceAtLeast(1),
                            true,
                        )
                        if (thumbnail !== source) thumbnail.recycle()
                        thumbnail = smaller
                        quality = ATTACHMENT_THUMBNAIL_QUALITY
                    } else {
                        break
                    }
                }
                Tasks.await(
                    storage.reference.child(storagePath).putFile(file.toUri(), webPMetadata),
                )
            } finally {
                file.delete()
                if (thumbnail !== source) thumbnail.recycle()
                source.recycle()
            }
        }.map { }
}

private const val OPERATION_KEY = "operation"
private const val STORAGE_PATH_KEY = "storage-path"
private const val SOURCE_URI_KEY = "source-uri"
private const val SOURCE_LOCATION_KEY = "source-location"
private const val UPLOAD_OPERATION = "upload"
private const val DELETE_OPERATION = "delete"
private const val GENERATE_THUMBNAIL_OPERATION = "generate-thumbnail"
private const val WEBP_CONTENT_TYPE = "image/webp"
private const val MAX_ATTACHMENT_DISPLAY_BYTES = 2L * 1024 * 1024
private const val MAX_ATTACHMENT_THUMBNAIL_BYTES = 256L * 1024
private const val ATTACHMENT_THUMBNAIL_QUALITY = 68
private const val MIN_ATTACHMENT_THUMBNAIL_QUALITY = 20
private const val ATTACHMENT_THUMBNAIL_QUALITY_STEP = 5
private const val THUMBNAIL_SIZE_REDUCTION = 0.85f

private fun attachmentThumbnailDimensions(width: Int, height: Int): Pair<Int, Int> {
    val longestSide = maxOf(width, height)
    if (longestSide <= 256) return width to height
    val scale = 256f / longestSide
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

@Suppress("DEPRECATION")
private fun webPFormat(): Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP
