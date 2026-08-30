package com.azhidkov.mystuff

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Metadata needed to remove a failed attachment without making the failure durable. */
internal data class AttachmentUploadFailure(
    val id: String,
    val householdId: String,
    val itemId: String,
    val attachmentId: String,
    val originatingMemberId: String,
    val displayStoragePath: String,
    val thumbnailStoragePath: String,
)

data class FailedItemAttachmentDraft(
    val id: String,
    val householdId: String,
    val itemId: String,
    val attachmentId: String,
    val originatingMemberId: String,
    val message: String,
)

internal class AttachmentUploadFailureRegistry {
    private data class PendingUpload(
        val failure: AttachmentUploadFailure,
        val sourceUris: List<String>,
        val retry: () -> Unit,
    )

    private val lock = Any()
    private val pending = mutableMapOf<String, PendingUpload>()
    private val failed = linkedMapOf<String, FailedItemAttachmentDraft>()
    private val observers = mutableSetOf<(List<FailedItemAttachmentDraft>) -> Unit>()

    fun prepare(
        failure: AttachmentUploadFailure,
        sourceUris: List<String>,
        retry: () -> Unit,
    ) {
        synchronized(lock) {
            pending[failure.id] = PendingUpload(failure, sourceUris, retry)
        }
    }

    fun markFailed(failure: AttachmentUploadFailure, cause: Throwable?) {
        val shouldNotify = synchronized(lock) {
            if (!pending.containsKey(failure.id)) {
                false
            } else {
                failed[failure.id] = FailedItemAttachmentDraft(
                    id = failure.id,
                    householdId = failure.householdId,
                    itemId = failure.itemId,
                    attachmentId = failure.attachmentId,
                    originatingMemberId = failure.originatingMemberId,
                    message = "Couldn't upload the Item Attachment. Tap Retry to try again.",
                )
                true
            }
        }
        if (shouldNotify) notifyObservers()
    }

    fun complete(id: String) {
        val (upload, hadFailure) = synchronized(lock) {
            pending.remove(id) to (failed.remove(id) != null)
        }
        upload?.sourceUris?.forEach(::forgetSource)
        if (hadFailure) notifyObservers()
    }

    fun retry(id: String) {
        val upload = synchronized(lock) {
            if (!pending.containsKey(id)) return@synchronized null
            failed.remove(id)
            pending[id]
        }
        upload ?: return
        notifyObservers()
        runCatching { upload.retry() }
            .onFailure { markFailed(upload.failure, it) }
    }

    fun remove(id: String) {
        val (upload, hadFailure) = synchronized(lock) {
            pending.remove(id) to (failed.remove(id) != null)
        }
        upload ?: return
        upload.sourceUris.forEach(::forgetSource)
        if (hadFailure) notifyObservers()
    }

    fun observe(onChanged: (List<FailedItemAttachmentDraft>) -> Unit): InventorySubscription {
        val snapshot = synchronized(lock) {
            observers += onChanged
            failed.values.toList()
        }
        onChanged(snapshot)
        return InventorySubscription { synchronized(lock) { observers -= onChanged } }
    }

    private fun notifyObservers() {
        val (snapshot, listeners) = synchronized(lock) {
            failed.values.toList() to observers.toList()
        }
        listeners.forEach { it(snapshot) }
    }

    private fun forgetSource(uri: String) {
        sourceCleaner(uri)
    }

    @Volatile
    var sourceCleaner: (String) -> Unit = {}
}

internal val processAttachmentUploadFailures = AttachmentUploadFailureRegistry()

internal sealed interface PhotoTransferTask {
    val storagePath: String
    val operationName: String
    val uploadFailure: AttachmentUploadFailure?
        get() = null

    fun execute(remoteStore: PhotoRemoteStore): Result<Unit>
    fun cleanUpLocalSource(context: Context)

    data class Upload(
        override val storagePath: String,
        val sourceUri: String,
        val additionalUploads: List<UploadPart> = emptyList(),
        override val uploadFailure: AttachmentUploadFailure? = null,
        val cleanUpSourceUris: List<String> = emptyList(),
    ) : PhotoTransferTask {
        override val operationName = UPLOAD_OPERATION

        override fun execute(remoteStore: PhotoRemoteStore): Result<Unit> = runCatching {
            remoteStore.upload(storagePath, sourceUri).getOrThrow()
        }

        override fun cleanUpLocalSource(context: Context) {
            sourceUrisToClean.forEach { uri ->
                runCatching { context.contentResolver.delete(uri.toUri(), null, null) }
            }
        }

        fun followUpUploads(): List<Upload> = additionalUploads.map { upload ->
            Upload(
                storagePath = upload.storagePath,
                sourceUri = upload.sourceUri,
                uploadFailure = uploadFailure,
                cleanUpSourceUris = sourceUrisToClean,
            )
        }

        private val sourceUrisToClean: List<String>
            get() = cleanUpSourceUris.takeIf { it.isNotEmpty() }
                ?: (listOf(sourceUri) + additionalUploads.map(UploadPart::sourceUri))
    }

    data class UploadPart(
        val storagePath: String,
        val sourceUri: String,
    )

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
        if (this is Upload && additionalUploads.isNotEmpty()) {
            builder.putStringArray(
                ADDITIONAL_STORAGE_PATHS_KEY,
                additionalUploads.map(UploadPart::storagePath).toTypedArray(),
            )
            builder.putStringArray(
                ADDITIONAL_SOURCE_URIS_KEY,
                additionalUploads.map(UploadPart::sourceUri).toTypedArray(),
            )
        }
        if (this is Upload && cleanUpSourceUris.isNotEmpty()) {
            builder.putStringArray(
                CLEAN_UP_SOURCE_URIS_KEY,
                cleanUpSourceUris.toTypedArray(),
            )
        }
        uploadFailure?.let { failure ->
            builder
                .putString(FAILURE_ID_KEY, failure.id)
                .putString(FAILURE_HOUSEHOLD_ID_KEY, failure.householdId)
                .putString(FAILURE_ITEM_ID_KEY, failure.itemId)
                .putString(FAILURE_ATTACHMENT_ID_KEY, failure.attachmentId)
                .putString(FAILURE_MEMBER_ID_KEY, failure.originatingMemberId)
                .putString(FAILURE_DISPLAY_PATH_KEY, failure.displayStoragePath)
                .putString(FAILURE_THUMBNAIL_PATH_KEY, failure.thumbnailStoragePath)
        }
        if (this is GenerateThumbnail) builder.putString(SOURCE_LOCATION_KEY, sourceLocation)
        return builder.build()
    }

    companion object {
        fun fromWorkData(data: Data): PhotoTransferTask? = runCatching {
            val storagePath = requireNotNull(data.getString(STORAGE_PATH_KEY))
            when (requireNotNull(data.getString(OPERATION_KEY))) {
                UPLOAD_OPERATION -> {
                    val additionalPaths = data.getStringArray(ADDITIONAL_STORAGE_PATHS_KEY)
                        ?: emptyArray()
                    val additionalSources = data.getStringArray(ADDITIONAL_SOURCE_URIS_KEY)
                        ?: emptyArray()
                    require(additionalPaths.size == additionalSources.size)
                    Upload(
                        storagePath = storagePath,
                        sourceUri = requireNotNull(data.getString(SOURCE_URI_KEY)),
                        additionalUploads = additionalPaths.zip(additionalSources)
                            .map { (path, source) -> UploadPart(path, source) },
                        uploadFailure = data.getString(FAILURE_ID_KEY)?.let {
                            AttachmentUploadFailure(
                                id = it,
                                householdId = requireNotNull(data.getString(FAILURE_HOUSEHOLD_ID_KEY)),
                                itemId = requireNotNull(data.getString(FAILURE_ITEM_ID_KEY)),
                                attachmentId = requireNotNull(data.getString(FAILURE_ATTACHMENT_ID_KEY)),
                                originatingMemberId = requireNotNull(data.getString(FAILURE_MEMBER_ID_KEY)),
                                displayStoragePath = requireNotNull(
                                    data.getString(FAILURE_DISPLAY_PATH_KEY),
                                ),
                                thumbnailStoragePath = requireNotNull(
                                    data.getString(FAILURE_THUMBNAIL_PATH_KEY),
                                ),
                            )
                        },
                        cleanUpSourceUris = data.getStringArray(CLEAN_UP_SOURCE_URIS_KEY)
                            ?.toList()
                            .orEmpty(),
                    )
                }
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

internal fun photoTransferWorkName(storagePath: String): String =
    "inventory-photo:$storagePath"

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

    override fun displayUploadWorkName(location: String): String? =
        location.removePrefix("$bucketUrl/")
            .takeIf { it != location }
            ?.let(::photoTransferWorkName)

    override fun uploadInBackground(
        revision: ItemPhotoRevision,
        photo: ItemPhoto,
    ) = uploadInBackground(revision, photo, null)

    override fun uploadInBackground(
        revision: ItemPhotoRevision,
        photo: ItemPhoto,
        failure: AttachmentUploadFailure?,
    ) {
        queue.replace(
            PhotoTransferTask.Upload(
                storagePath = revision.fullStoragePath,
                sourceUri = photo.uri,
                additionalUploads = listOf(
                    PhotoTransferTask.UploadPart(
                        storagePath = revision.thumbnailStoragePath,
                        sourceUri = photo.thumbnailUri,
                    ),
                ),
                uploadFailure = failure,
            ),
        )
    }

    override fun uploadDisplayInBackground(
        revision: ItemPhotoRevision,
        photo: ItemPhoto,
        failure: AttachmentUploadFailure?,
    ) {
        queueUpload(revision.fullStoragePath, photo.uri, failure)
    }

    override fun uploadThumbnailInBackground(revision: ItemPhotoRevision, photo: ItemPhoto) {
        queueUpload(revision.thumbnailStoragePath, photo.thumbnailUri)
    }

    private fun queueUpload(
        storagePath: String,
        sourceUri: String,
        failure: AttachmentUploadFailure? = null,
    ) {
        queue.replace(
            PhotoTransferTask.Upload(
                storagePath = storagePath,
                sourceUri = sourceUri,
                uploadFailure = failure,
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
            .build()
        workManager.enqueueUniqueWork(
            photoTransferWorkName(task.storagePath),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

internal enum class PhotoTransferResult {
    Success,
    Failure,
}

internal interface PhotoTransferFailureHandler {
    fun handle(failure: AttachmentUploadFailure, remoteStore: PhotoRemoteStore)
}

internal object NoPhotoTransferFailureHandler : PhotoTransferFailureHandler {
    override fun handle(failure: AttachmentUploadFailure, remoteStore: PhotoRemoteStore) = Unit
}

internal interface PhotoRemoteStore {
    fun upload(storagePath: String, sourceUri: String): Result<Unit>
    fun delete(storagePath: String): Result<Unit>
    fun generateThumbnail(storagePath: String, sourceLocation: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Thumbnail generation is unavailable."))
}

internal class PhotoTransferRunner(
    private val remoteStore: PhotoRemoteStore,
    private val failureHandler: PhotoTransferFailureHandler = NoPhotoTransferFailureHandler,
) {
    fun run(task: PhotoTransferTask): PhotoTransferResult {
        val result = task.execute(remoteStore)
        if (result.isSuccess) return PhotoTransferResult.Success
        task.uploadFailure?.let { failure ->
            runCatching { failureHandler.handle(failure, remoteStore) }
        }
        return PhotoTransferResult.Failure
    }
}

internal class InventoryPhotoTransferWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val task = PhotoTransferTask.fromWorkData(inputData) ?: return Result.failure()
        val remoteStore = FirebasePhotoRemoteStore()
        val result = PhotoTransferRunner(
            remoteStore = remoteStore,
            failureHandler = FirebaseAttachmentUploadFailureHandler(),
        ).run(task)
        if (result == PhotoTransferResult.Success) {
            if (task is PhotoTransferTask.Upload && task.additionalUploads.isNotEmpty()) {
                runCatching {
                    val queue = WorkManagerPhotoTransferQueue(applicationContext)
                    task.followUpUploads().forEach(queue::replace)
                }.onFailure { failure ->
                    task.uploadFailure?.let { uploadFailure ->
                        runCatching {
                            FirebaseAttachmentUploadFailureHandler().handle(uploadFailure, remoteStore)
                        }
                        processAttachmentUploadFailures.markFailed(uploadFailure, failure)
                    }
                }
            } else {
                task.uploadFailure?.let { processAttachmentUploadFailures.complete(it.id) }
                task.cleanUpLocalSource(applicationContext)
            }
        } else {
            task.uploadFailure?.let { failure ->
                processAttachmentUploadFailures.markFailed(failure, null)
            }
        }
        return when (result) {
            PhotoTransferResult.Success -> Result.success()
            PhotoTransferResult.Failure -> {
                // Keep dependent Description Generation work runnable so it can report its
                // stage-specific photo failure after the attachment cleanup has completed.
                Result.success()
            }
        }
    }
}

private class FirebaseAttachmentUploadFailureHandler(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : PhotoTransferFailureHandler {
    override fun handle(failure: AttachmentUploadFailure, remoteStore: PhotoRemoteStore) {
        listOf(failure.displayStoragePath, failure.thumbnailStoragePath)
            .forEach { path -> runCatching { remoteStore.delete(path) } }

        val item = firestore.collection(FAILURE_HOUSEHOLDS)
            .document(failure.householdId)
            .collection(FAILURE_ITEMS)
            .document(failure.itemId)
        val attachment = item.collection(FAILURE_ATTACHMENTS).document(failure.attachmentId)
        runCatching {
            Tasks.await(
                firestore.runTransaction { transaction ->
                    val itemSnapshot = transaction.get(item)
                    if (itemSnapshot.getString(FAILURE_PHOTO_ATTACHMENT_ID) == failure.attachmentId) {
                        transaction.update(
                            item,
                            mapOf(
                                FAILURE_PHOTO_ATTACHMENT_ID to null,
                                FAILURE_PHOTO_URL to null,
                                FAILURE_PHOTO_THUMBNAIL_URL to null,
                            ),
                        )
                    }
                    transaction.delete(attachment)
                },
            )
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
private const val ADDITIONAL_STORAGE_PATHS_KEY = "additional-storage-paths"
private const val ADDITIONAL_SOURCE_URIS_KEY = "additional-source-uris"
private const val CLEAN_UP_SOURCE_URIS_KEY = "clean-up-source-uris"
private const val FAILURE_ID_KEY = "failure-id"
private const val FAILURE_HOUSEHOLD_ID_KEY = "failure-household-id"
private const val FAILURE_ITEM_ID_KEY = "failure-item-id"
private const val FAILURE_ATTACHMENT_ID_KEY = "failure-attachment-id"
private const val FAILURE_MEMBER_ID_KEY = "failure-member-id"
private const val FAILURE_DISPLAY_PATH_KEY = "failure-display-path"
private const val FAILURE_THUMBNAIL_PATH_KEY = "failure-thumbnail-path"
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
private const val FAILURE_HOUSEHOLDS = "households"
private const val FAILURE_ITEMS = "items"
private const val FAILURE_ATTACHMENTS = "attachments"
private const val FAILURE_PHOTO_ATTACHMENT_ID = "photoAttachmentId"
private const val FAILURE_PHOTO_URL = "photoUrl"
private const val FAILURE_PHOTO_THUMBNAIL_URL = "photoThumbnailUrl"

private fun attachmentThumbnailDimensions(width: Int, height: Int): Pair<Int, Int> {
    val longestSide = maxOf(width, height)
    if (longestSide <= 256) return width to height
    val scale = 256f / longestSide
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

@Suppress("DEPRECATION")
private fun webPFormat(): Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP
