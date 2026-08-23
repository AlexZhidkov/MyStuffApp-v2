package com.azhidkov.mystuff

import android.content.Context
import android.graphics.BitmapFactory
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.lifecycle.Observer
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Firebase
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.RequestTimeoutException
import com.google.firebase.ai.type.ServerException
import com.google.firebase.ai.type.ServiceConnectionHandshakeFailedException
import com.google.firebase.ai.type.UnknownException
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.runBlocking

internal data class DescriptionGenerationRequest(
    val householdId: String,
    val item: Item,
    val requestingMember: RequestingMemberAttribution,
    val deviceLanguage: String,
    val replacementPhoto: DescriptionGenerationReplacementPhoto? = null,
)

internal data class DescriptionGenerationReplacementPhoto(
    val revision: ItemPhotoRevision,
    val source: ItemPhoto,
)

internal data class RequestingMemberAttribution(
    val id: String,
    val displayName: String,
)

internal data class PendingDescriptionGeneration(
    val id: String,
    val request: DescriptionGenerationRequest,
)

internal data class CompletedDescriptionGeneration(
    val id: String,
    val householdId: String,
    val outcome: DescriptionGenerationOutcome,
)

internal data class DescriptionGenerationWorkState(
    val pending: List<PendingDescriptionGeneration> = emptyList(),
    val completed: List<CompletedDescriptionGeneration> = emptyList(),
)

internal class DescriptionGenerationRequestCapture(
    private val photoStore: InventoryPhotoStore,
) {
    fun capture(
        request: DescriptionGenerationRequest,
        replacementPhoto: ItemPhoto?,
    ): DescriptionGenerationRequest {
        if (replacementPhoto == null) return request
        val revision = photoStore.newRevision(request.householdId, request.item.id)
        return request.copy(
            item = request.item.copy(
                photoUrl = revision.locations.full,
                photoThumbnailUrl = revision.locations.thumbnail,
            ),
            replacementPhoto = DescriptionGenerationReplacementPhoto(
                revision = revision,
                source = replacementPhoto,
            ),
        )
    }

    fun uploadThumbnailInBackground(request: DescriptionGenerationRequest) {
        val replacement = request.replacementPhoto ?: return
        try {
            photoStore.uploadThumbnailInBackground(
                revision = replacement.revision,
                photo = replacement.source,
            )
        } catch (_: RuntimeException) {
            // Thumbnail transfer remains independent from Description Generation submission.
        }
    }
}

internal interface InventoryDescriptionGenerationWork {
    fun submit(
        request: DescriptionGenerationRequest,
        replacementPhoto: ItemPhoto? = null,
    ): PendingDescriptionGeneration

    fun observe(onChanged: (DescriptionGenerationWorkState) -> Unit): InventorySubscription

    fun consumeOutcome(id: String)
}

internal object NoInventoryDescriptionGenerationWork : InventoryDescriptionGenerationWork {
    override fun submit(
        request: DescriptionGenerationRequest,
        replacementPhoto: ItemPhoto?,
    ) = PendingDescriptionGeneration("", request)

    override fun observe(
        onChanged: (DescriptionGenerationWorkState) -> Unit,
    ): InventorySubscription {
        onChanged(DescriptionGenerationWorkState())
        return InventorySubscription {}
    }

    override fun consumeOutcome(id: String) = Unit
}

internal sealed interface DescriptionGenerationStep<out T> {
    data class Success<T>(val value: T) : DescriptionGenerationStep<T>
    data object PermanentFailure : DescriptionGenerationStep<Nothing>
    data class PermanentFailureWithErrorType(val errorType: String) : DescriptionGenerationStep<Nothing>
}

internal sealed interface DescriptionGenerationOutcome {
    val deferredErrorMessage: String?
    val storageName: String

    data object Success : DescriptionGenerationOutcome {
        override val deferredErrorMessage = null
        override val storageName = "Success"
    }

    data object PermanentSaveFailure : DescriptionGenerationOutcome {
        override val deferredErrorMessage = "Couldn't save the Item."
        override val storageName = "PermanentSaveFailure"
    }

    data object PermanentPhotoFailure : DescriptionGenerationOutcome {
        override val deferredErrorMessage = "Item saved, but couldn't upload its photo."
        override val storageName = "PermanentPhotoFailure"
    }

    data object PermanentGenerationFailure : DescriptionGenerationOutcome {
        override val deferredErrorMessage = "Item saved, but couldn't generate its description."
        override val storageName = "PermanentGenerationFailure"
    }

    data class PermanentGenerationFailureWithErrorType(
        val errorType: String,
    ) : DescriptionGenerationOutcome {
        override val deferredErrorMessage =
            "Item saved, but couldn't generate its description. $errorType."
        override val storageName = "PermanentGenerationFailureWithErrorType"
    }
}

internal enum class DescriptionGenerationFailureCategory {
    Connectivity,
    Throttling,
    RemoteService,
    Permanent,
}

internal data class DescriptionGenerationPhoto(
    val bytes: ByteArray,
)

internal data class DescriptionGenerationModelInput(
    val photo: DescriptionGenerationPhoto,
    val existingDescription: String?,
    val deviceLanguage: String,
    val prompt: String,
)

internal interface DescriptionGenerationItemStore {
    fun saveDraft(request: DescriptionGenerationRequest): DescriptionGenerationStep<Unit>

    fun patchDescription(
        householdId: String,
        itemId: String,
        description: String,
        requestingMember: RequestingMemberAttribution,
    ): DescriptionGenerationStep<Unit>
}

internal fun interface DescriptionGenerationPhotoLoader {
    fun load(location: String): DescriptionGenerationStep<DescriptionGenerationPhoto>
}

internal fun interface DescriptionGenerationFullPhotoUploader {
    fun upload(photo: DescriptionGenerationReplacementPhoto): DescriptionGenerationStep<Unit>
}

internal interface DescriptionGenerationUploadedPhotoLedger {
    fun isUploaded(): Boolean
    fun markUploaded()
}

internal fun interface DescriptionGenerationLocalPhotoSourceCleaner {
    fun clean(sourceUri: String): DescriptionGenerationStep<Unit>
}

internal fun interface DescriptionGenerator {
    fun generate(input: DescriptionGenerationModelInput): DescriptionGenerationStep<String>
}

internal class DescriptionGenerationWorkflow(
    private val itemStore: DescriptionGenerationItemStore,
    private val photoLoader: DescriptionGenerationPhotoLoader,
    private val generator: DescriptionGenerator,
    private val fullPhotoUploader: DescriptionGenerationFullPhotoUploader =
        DescriptionGenerationFullPhotoUploader { DescriptionGenerationStep.PermanentFailure },
    private val uploadedPhotoLedger: DescriptionGenerationUploadedPhotoLedger =
        EmptyDescriptionGenerationUploadedPhotoLedger,
    private val localPhotoSourceCleaner: DescriptionGenerationLocalPhotoSourceCleaner =
        DescriptionGenerationLocalPhotoSourceCleaner {
            DescriptionGenerationStep.Success(Unit)
        },
) {
    fun run(request: DescriptionGenerationRequest): DescriptionGenerationOutcome {
        when (itemStore.saveDraft(request)) {
            is DescriptionGenerationStep.Success -> Unit
            is DescriptionGenerationStep.PermanentFailureWithErrorType,
            DescriptionGenerationStep.PermanentFailure ->
                return DescriptionGenerationOutcome.PermanentSaveFailure
        }

        request.replacementPhoto?.let { replacement ->
            if (!uploadedPhotoLedger.isUploaded()) {
                when (fullPhotoUploader.upload(replacement)) {
                    is DescriptionGenerationStep.Success -> {
                        uploadedPhotoLedger.markUploaded()
                    }
                    is DescriptionGenerationStep.PermanentFailureWithErrorType,
                    DescriptionGenerationStep.PermanentFailure ->
                        return DescriptionGenerationOutcome.PermanentPhotoFailure
                }
            }
            when (localPhotoSourceCleaner.clean(replacement.source.uri)) {
                is DescriptionGenerationStep.Success -> Unit
                is DescriptionGenerationStep.PermanentFailureWithErrorType,
                DescriptionGenerationStep.PermanentFailure ->
                    return DescriptionGenerationOutcome.PermanentPhotoFailure
            }
        }

        val photo = when (
            val loaded = photoLoader.load(requireNotNull(request.item.photoUrl))
        ) {
            is DescriptionGenerationStep.Success -> loaded.value
            is DescriptionGenerationStep.PermanentFailureWithErrorType,
            DescriptionGenerationStep.PermanentFailure ->
                return DescriptionGenerationOutcome.PermanentPhotoFailure
        }
        val generated = when (
            val result = generator.generate(
                DescriptionGenerationModelInput(
                    photo = photo,
                    existingDescription = request.item.description,
                    deviceLanguage = request.deviceLanguage,
                    prompt = descriptionGenerationPrompt(
                        itemTitle = request.item.name,
                        existingDescription = request.item.description,
                        deviceLanguage = request.deviceLanguage,
                    ),
                ),
            )
        ) {
            is DescriptionGenerationStep.Success -> result.value.trimUnicodeWhitespace()
            is DescriptionGenerationStep.PermanentFailureWithErrorType ->
                return DescriptionGenerationOutcome.PermanentGenerationFailureWithErrorType(
                    result.errorType,
                )
            DescriptionGenerationStep.PermanentFailure ->
                return DescriptionGenerationOutcome.PermanentGenerationFailure
        }
        if (
            generated.isEmpty() ||
            generated.codePointCount(0, generated.length) > ItemFormPolicy.MAX_DESCRIPTION_LENGTH
        ) {
            return DescriptionGenerationOutcome.PermanentGenerationFailure
        }

        return when (
            val result = itemStore.patchDescription(
                householdId = request.householdId,
                itemId = request.item.id,
                description = generated,
                requestingMember = request.requestingMember,
            )
        ) {
            is DescriptionGenerationStep.Success -> DescriptionGenerationOutcome.Success
            is DescriptionGenerationStep.PermanentFailureWithErrorType ->
                DescriptionGenerationOutcome.PermanentGenerationFailureWithErrorType(
                    result.errorType,
                )
            DescriptionGenerationStep.PermanentFailure ->
                DescriptionGenerationOutcome.PermanentGenerationFailure
        }
    }
}

private object EmptyDescriptionGenerationUploadedPhotoLedger :
    DescriptionGenerationUploadedPhotoLedger {
    override fun isUploaded() = false
    override fun markUploaded() = Unit
}

internal fun descriptionGenerationPrompt(
    itemTitle: String,
    existingDescription: String?,
    deviceLanguage: String,
): String = """
    Write one concise plain-text paragraph describing the $itemTitle shown in the photo.
    If the photo contains multiple things, describe only $itemTitle.
    Preserve every factual statement from the Human-written Description below, rewriting only
    for clarity. Add only identifying details that are clearly visible in the photo. Do not make
    unsupported brand or model claims. Do not use Markdown, headings, or lists.
    Use the Human-written Description's language when it is detectable; otherwise use the
    captured device language: $deviceLanguage.

    Human-written Description:
    ${existingDescription.orEmpty()}
""".trimIndent()

internal class WorkManagerInventoryDescriptionGenerationWork(
    context: Context,
) : InventoryDescriptionGenerationWork {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workManager = WorkManager.getInstance(context)
    private val workStore = DescriptionGenerationWorkStore(context.noBackupFilesDir)
    private val requestCapture = DescriptionGenerationRequestCapture(firebaseInventoryPhotoStore())
    private val observers = mutableSetOf<(DescriptionGenerationWorkState) -> Unit>()
    private val workInfoObserver = Observer<List<WorkInfo>> { emitState() }
    private val completedOutcomeObserver = object : FileObserver(
        File(
            context.noBackupFilesDir,
            "$DESCRIPTION_GENERATION_WORK_DIRECTORY/$DESCRIPTION_GENERATION_COMPLETED_DIRECTORY",
        ).apply { mkdirs() }.absolutePath,
        FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE,
    ) {
        override fun onEvent(event: Int, path: String?) {
            mainHandler.post { emitState() }
        }
    }

    init {
        workManager.getWorkInfosByTagLiveData(DESCRIPTION_GENERATION_WORK_TAG)
            .observeForever(workInfoObserver)
        completedOutcomeObserver.startWatching()
    }

    override fun submit(
        request: DescriptionGenerationRequest,
        replacementPhoto: ItemPhoto?,
    ): PendingDescriptionGeneration {
        val capturedRequest = requestCapture.capture(request, replacementPhoto)
        val id = workStore.enqueue(capturedRequest)
        try {
            val work = OneTimeWorkRequestBuilder<InventoryDescriptionGenerationWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(WORK_REQUEST_ID, id)
                        .putString(WORK_HOUSEHOLD_ID, capturedRequest.householdId)
                        .build(),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(DESCRIPTION_GENERATION_WORK_TAG)
                .build()
            workManager.enqueue(work)
        } catch (failure: RuntimeException) {
            workStore.discardPending(id)
            throw failure
        }
        requestCapture.uploadThumbnailInBackground(capturedRequest)
        emitState()
        return PendingDescriptionGeneration(id, capturedRequest)
    }

    override fun observe(
        onChanged: (DescriptionGenerationWorkState) -> Unit,
    ): InventorySubscription {
        observers += onChanged
        onChanged(workStore.snapshot())
        return InventorySubscription { observers -= onChanged }
    }

    override fun consumeOutcome(id: String) {
        workStore.consume(id)
        emitState()
    }

    private fun emitState() {
        val state = workStore.snapshot()
        observers.toList().forEach { observer -> observer(state) }
    }
}

internal class InventoryDescriptionGenerationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val workStore = DescriptionGenerationWorkStore(applicationContext.noBackupFilesDir)
        val requestId = inputData.getString(WORK_REQUEST_ID) ?: id.toString()
        val householdId = inputData.getString(WORK_HOUSEHOLD_ID)
        val request = workStore.pendingRequest(requestId)
        if (request == null) {
            if (householdId != null) {
                workStore.completeUnreadableRequest(requestId, householdId)
            } else {
                workStore.discardPending(requestId)
            }
            return Result.failure(failureData(DescriptionGenerationOutcome.PermanentSaveFailure))
        }
        val workflow = DescriptionGenerationWorkflow(
            itemStore = FirebaseDescriptionGenerationItemStore(),
            photoLoader = FirebaseDescriptionGenerationPhotoLoader(),
            generator = FirebaseGeminiDescriptionGenerator(),
            fullPhotoUploader = FirebaseDescriptionGenerationFullPhotoUploader(),
            uploadedPhotoLedger = workStore.uploadedPhotoLedger(requestId),
            localPhotoSourceCleaner = AndroidDescriptionGenerationLocalPhotoSourceCleaner(
                applicationContext,
            ),
        )
        val outcome = workflow.run(request)
        workStore.complete(
            id = requestId,
            outcome = outcome,
        )
        return when (outcome) {
            DescriptionGenerationOutcome.Success -> Result.success()
            DescriptionGenerationOutcome.PermanentSaveFailure,
            DescriptionGenerationOutcome.PermanentPhotoFailure,
            DescriptionGenerationOutcome.PermanentGenerationFailure,
            is DescriptionGenerationOutcome.PermanentGenerationFailureWithErrorType,
            -> Result.failure(failureData(outcome))
        }
    }
}

private class FirebaseDescriptionGenerationItemStore(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : DescriptionGenerationItemStore {
    override fun saveDraft(
        request: DescriptionGenerationRequest,
    ): DescriptionGenerationStep<Unit> = firebaseStep {
        val item = request.item
        Tasks.await(
            itemDocument(request.householdId, item.id).update(
                mapOf(
                    ITEM_NAME_FIELD to item.name,
                    ITEM_PHOTO_URL_FIELD to item.photoUrl,
                    ITEM_PHOTO_THUMBNAIL_URL_FIELD to item.photoThumbnailUrl,
                    ITEM_DESCRIPTION_FIELD to item.description,
                    ITEM_TAGS_FIELD to item.tags,
                    ITEM_WEB_URL_FIELD to item.webUrl,
                    ITEM_UPDATED_AT_FIELD to FieldValue.serverTimestamp(),
                    ITEM_UPDATED_BY_ID_FIELD to request.requestingMember.id,
                    ITEM_UPDATED_BY_DISPLAY_NAME_FIELD to request.requestingMember.displayName,
                ),
            ),
        )
    }

    override fun patchDescription(
        householdId: String,
        itemId: String,
        description: String,
        requestingMember: RequestingMemberAttribution,
    ): DescriptionGenerationStep<Unit> = firebaseStep {
        Tasks.await(
            itemDocument(householdId, itemId).update(
                mapOf(
                    ITEM_DESCRIPTION_FIELD to description,
                    ITEM_UPDATED_AT_FIELD to FieldValue.serverTimestamp(),
                    ITEM_UPDATED_BY_ID_FIELD to requestingMember.id,
                    ITEM_UPDATED_BY_DISPLAY_NAME_FIELD to requestingMember.displayName,
                ),
            ),
        )
    }

    private fun itemDocument(householdId: String, itemId: String) = firestore
        .collection(HOUSEHOLDS_COLLECTION)
        .document(householdId)
        .collection(ITEMS_COLLECTION)
        .document(itemId)
}

private class FirebaseDescriptionGenerationPhotoLoader(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) : DescriptionGenerationPhotoLoader {
    override fun load(location: String): DescriptionGenerationStep<DescriptionGenerationPhoto> =
        firebaseStep {
            val bytes = Tasks.await(
                storage.getReferenceFromUrl(location).getBytes(MAX_INLINE_PHOTO_BYTES),
            )
            DescriptionGenerationPhoto(bytes)
        }
}

private class FirebaseDescriptionGenerationFullPhotoUploader(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) : DescriptionGenerationFullPhotoUploader {
    private val webPMetadata = StorageMetadata.Builder()
        .setContentType(DESCRIPTION_GENERATION_WEBP_CONTENT_TYPE)
        .build()

    override fun upload(
        photo: DescriptionGenerationReplacementPhoto,
    ): DescriptionGenerationStep<Unit> = firebaseStep {
        val upload = storage.reference
            .child(photo.revision.fullStoragePath)
            .putFile(photo.source.uri.toUri(), webPMetadata)
        try {
            Tasks.await(upload).let { }
        } catch (failure: InterruptedException) {
            upload.cancel()
            throw failure
        }
    }
}

private class AndroidDescriptionGenerationLocalPhotoSourceCleaner(
    private val context: Context,
) : DescriptionGenerationLocalPhotoSourceCleaner {
    override fun clean(sourceUri: String): DescriptionGenerationStep<Unit> = try {
        context.contentResolver.delete(sourceUri.toUri(), null, null)
        DescriptionGenerationStep.Success(Unit)
    } catch (failure: Exception) {
        classifyDescriptionGenerationFailure(failure)
    }
}

private class FirebaseGeminiDescriptionGenerator(
    private val modelConfig: DescriptionGenerationModelConfig =
        RemoteConfigDescriptionGenerationModelConfig(),
) : DescriptionGenerator {
    override fun generate(
        input: DescriptionGenerationModelInput,
    ): DescriptionGenerationStep<String> {
        val bitmap = BitmapFactory.decodeByteArray(input.photo.bytes, 0, input.photo.bytes.size)
            ?: return DescriptionGenerationStep.PermanentFailure
        return try {
            val model = Firebase.ai(
                backend = GenerativeBackend.googleAI(),
                useLimitedUseAppCheckTokens = false,
            )
                .generativeModel(modelConfig.modelName())
            val prompt = content {
                image(bitmap)
                text(input.prompt)
            }
            val response = runBlocking { model.generateContent(prompt) }
            DescriptionGenerationStep.Success(response.text.orEmpty())
        } catch (failure: Exception) {
            failure.toDescriptionGenerationFailure()
        } finally {
            bitmap.recycle()
        }
    }
}

private fun <T> firebaseStep(block: () -> T): DescriptionGenerationStep<T> = try {
    DescriptionGenerationStep.Success(block())
} catch (failure: Exception) {
    failure.toDescriptionGenerationFailure()
}

internal fun classifyDescriptionGenerationFailure(
    failure: Throwable,
): DescriptionGenerationStep<Nothing> {
    val unwrapped = failure.unwrapExecutionFailure()
    if (unwrapped.isResourceExhausted()) {
        return DescriptionGenerationStep.PermanentFailureWithErrorType(
            GEMINI_ERROR_TYPE_RESOURCE_EXHAUSTED,
        )
    }
    return classifyDescriptionGenerationFailure(unwrapped.descriptionGenerationFailureCategory())
}

private fun Throwable.isResourceExhausted(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is QuotaExceededException ||
            current is FirebaseTooManyRequestsException ||
            current.message?.contains(GEMINI_ERROR_TYPE_RESOURCE_EXHAUSTED, ignoreCase = true) == true
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

internal fun classifyDescriptionGenerationFailure(
    category: DescriptionGenerationFailureCategory,
): DescriptionGenerationStep<Nothing> =
    DescriptionGenerationStep.PermanentFailure

private fun Throwable.toDescriptionGenerationFailure(): DescriptionGenerationStep<Nothing> =
    classifyDescriptionGenerationFailure(this)

private fun Throwable.unwrapExecutionFailure(): Throwable =
    if (this is ExecutionException && cause != null) requireNotNull(cause) else this

private fun Throwable.descriptionGenerationFailureCategory():
    DescriptionGenerationFailureCategory = when (this) {
    is IOException,
    is InterruptedException,
    is FirebaseNetworkException,
    -> DescriptionGenerationFailureCategory.Connectivity
    is QuotaExceededException -> DescriptionGenerationFailureCategory.Permanent
    is FirebaseTooManyRequestsException -> DescriptionGenerationFailureCategory.Throttling
    is RequestTimeoutException,
    is ServerException,
    is ServiceConnectionHandshakeFailedException,
    is UnknownException,
    -> DescriptionGenerationFailureCategory.RemoteService
    is FirebaseFirestoreException -> if (code.isTransient()) {
        DescriptionGenerationFailureCategory.RemoteService
    } else {
        DescriptionGenerationFailureCategory.Permanent
    }
    is StorageException -> if (errorCode.isTransientStorageError()) {
        DescriptionGenerationFailureCategory.RemoteService
    } else {
        DescriptionGenerationFailureCategory.Permanent
    }
    else -> DescriptionGenerationFailureCategory.Permanent
}

private fun FirebaseFirestoreException.Code.isTransient(): Boolean = when (this) {
    FirebaseFirestoreException.Code.ABORTED,
    FirebaseFirestoreException.Code.CANCELLED,
    FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
    FirebaseFirestoreException.Code.INTERNAL,
    FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
    FirebaseFirestoreException.Code.UNAVAILABLE,
    FirebaseFirestoreException.Code.UNKNOWN,
    -> true
    else -> false
}

private fun Int.isTransientStorageError(): Boolean = when (this) {
    StorageException.ERROR_RETRY_LIMIT_EXCEEDED,
    StorageException.ERROR_QUOTA_EXCEEDED,
    StorageException.ERROR_UNKNOWN,
    -> true
    else -> false
}

internal class DescriptionGenerationWorkStore(baseDirectory: File) {
    private val rootDirectory = File(baseDirectory, DESCRIPTION_GENERATION_WORK_DIRECTORY)
    private val pendingDirectory = File(rootDirectory, DESCRIPTION_GENERATION_PENDING_DIRECTORY)
    private val completedDirectory = File(rootDirectory, DESCRIPTION_GENERATION_COMPLETED_DIRECTORY)

    fun enqueue(request: DescriptionGenerationRequest): String {
        ensureDirectory(pendingDirectory)
        return File.createTempFile(DESCRIPTION_GENERATION_REQUEST_PREFIX, ".bin", pendingDirectory)
            .also { file ->
                DataOutputStream(file.outputStream().buffered()).use { output ->
                    output.writeRequest(request)
                }
            }
            .nameWithoutExtension
    }

    fun pendingRequest(id: String): DescriptionGenerationRequest? =
        readRequest(resolve(pendingDirectory, id))

    fun uploadedPhotoLedger(id: String): DescriptionGenerationUploadedPhotoLedger =
        FileDescriptionGenerationUploadedPhotoLedger(
            marker = resolveWithExtension(pendingDirectory, id, UPLOADED_PHOTO_MARKER_EXTENSION),
        )

    fun complete(
        id: String,
        outcome: DescriptionGenerationOutcome,
    ) {
        val request = pendingRequest(id) ?: return
        writeCompleted(id, request.householdId, outcome)
        discardPending(id)
    }

    fun completeUnreadableRequest(id: String, householdId: String) {
        writeCompleted(id, householdId, DescriptionGenerationOutcome.PermanentSaveFailure)
        discardPending(id)
    }

    private fun writeCompleted(
        id: String,
        householdId: String,
        outcome: DescriptionGenerationOutcome,
    ) {
        ensureDirectory(completedDirectory)
        val destination = resolve(completedDirectory, id) ?: return
        val temporary = File.createTempFile("completed-", ".tmp", completedDirectory)
        DataOutputStream(temporary.outputStream().buffered()).use { output ->
            output.writeUTF(householdId)
            output.writeUTF(outcome.storageName)
            if (outcome is DescriptionGenerationOutcome.PermanentGenerationFailureWithErrorType) {
                output.writeUTF(outcome.errorType)
            }
        }
        check(temporary.renameTo(destination)) {
            "Description Generation outcome storage is unavailable"
        }
    }

    fun snapshot(): DescriptionGenerationWorkState = DescriptionGenerationWorkState(
        pending = workFiles(pendingDirectory).mapNotNull { file ->
            readRequest(file)?.let { request ->
                PendingDescriptionGeneration(file.nameWithoutExtension, request)
            }
        },
        completed = workFiles(completedDirectory).mapNotNull(::readCompleted),
    )

    fun consume(id: String) {
        resolve(completedDirectory, id)?.delete()
    }

    fun discardPending(id: String) {
        resolve(pendingDirectory, id)?.delete()
        resolveWithExtension(
            pendingDirectory,
            id,
            UPLOADED_PHOTO_MARKER_EXTENSION,
        )?.delete()
    }

    private fun readCompleted(file: File): CompletedDescriptionGeneration? = runCatching {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val householdId = input.readUTF()
            val outcomeName = input.readUTF()
            CompletedDescriptionGeneration(
                id = file.nameWithoutExtension,
                householdId = householdId,
                outcome = when (outcomeName) {
                    "Success" -> DescriptionGenerationOutcome.Success
                    "PermanentSaveFailure" -> DescriptionGenerationOutcome.PermanentSaveFailure
                    "PermanentPhotoFailure" ->
                        DescriptionGenerationOutcome.PermanentPhotoFailure
                    "PermanentGenerationFailure" ->
                        DescriptionGenerationOutcome.PermanentGenerationFailure
                    "PermanentGenerationFailureWithErrorType" ->
                        DescriptionGenerationOutcome.PermanentGenerationFailureWithErrorType(
                            input.readUTF(),
                        )
                    else -> error("Unknown Description Generation outcome: $outcomeName")
                },
            )
        }
    }.getOrNull()

    private fun readRequest(file: File?): DescriptionGenerationRequest? = file?.let { resolved ->
        runCatching {
            DataInputStream(resolved.inputStream().buffered()).use(DataInputStream::readRequest)
        }.getOrNull()
    }

    private fun workFiles(directory: File): List<File> = directory.listFiles()
        .orEmpty()
        .filter { file -> file.extension == "bin" }
        .sortedWith(compareBy(File::lastModified, File::getName))

    private fun resolve(directory: File, id: String): File? = runCatching {
        resolveWithExtension(directory, id, "bin")
    }.getOrNull()

    private fun resolveWithExtension(
        directory: File,
        id: String,
        extension: String,
    ): File? {
        val expectedDirectory = directory.canonicalFile
        return File(directory, "$id.$extension").canonicalFile.takeIf { candidate ->
            candidate.parentFile == expectedDirectory
        }
    }

    private fun ensureDirectory(directory: File) {
        check(directory.mkdirs() || directory.isDirectory) {
            "Description Generation work storage is unavailable"
        }
    }
}

private class FileDescriptionGenerationUploadedPhotoLedger(
    private val marker: File?,
) : DescriptionGenerationUploadedPhotoLedger {
    override fun isUploaded(): Boolean = marker?.isFile == true

    override fun markUploaded() {
        val file = requireNotNull(marker)
        check(file.createNewFile() || file.isFile) {
            "Description Generation photo checkpoint storage is unavailable"
        }
    }
}

private fun DataOutputStream.writeRequest(request: DescriptionGenerationRequest) {
    writeUTF(request.householdId)
    writeUTF(request.item.id)
    writeUTF(request.item.name)
    writeNullableString(request.item.parentItemId)
    writeNullableString(request.item.photoUrl)
    writeNullableString(request.item.photoThumbnailUrl)
    writeNullableString(request.item.description)
    writeInt(request.item.tags.size)
    request.item.tags.forEach(::writeUTF)
    writeNullableString(request.item.webUrl)
    writeUTF(request.requestingMember.id)
    writeUTF(request.requestingMember.displayName)
    writeUTF(request.deviceLanguage)
    writeBoolean(request.replacementPhoto != null)
    request.replacementPhoto?.let { replacement ->
        writeUTF(replacement.revision.fullStoragePath)
        writeUTF(replacement.revision.thumbnailStoragePath)
        writeUTF(replacement.source.uri)
        writeUTF(replacement.source.thumbnailUri)
    }
}

private fun DataInputStream.readRequest(): DescriptionGenerationRequest {
    val request = DescriptionGenerationRequest(
        householdId = readUTF(),
        item = Item(
            id = readUTF(),
            name = readUTF(),
            parentItemId = readNullableString(),
            photoUrl = readNullableString(),
            photoThumbnailUrl = readNullableString(),
            description = readNullableString(),
            tags = List(readInt()) { readUTF() },
            webUrl = readNullableString(),
        ),
        requestingMember = RequestingMemberAttribution(readUTF(), readUTF()),
        deviceLanguage = readUTF(),
    )
    val replacement = try {
        if (readBoolean()) {
            DescriptionGenerationReplacementPhoto(
                revision = ItemPhotoRevision(
                    locations = ItemPhotoLocations(
                        full = requireNotNull(request.item.photoUrl),
                        thumbnail = requireNotNull(request.item.photoThumbnailUrl),
                    ),
                    fullStoragePath = readUTF(),
                    thumbnailStoragePath = readUTF(),
                ),
                source = ItemPhoto(
                    uri = readUTF(),
                    thumbnailUri = readUTF(),
                ),
            )
        } else {
            null
        }
    } catch (_: EOFException) {
        null
    }
    return request.copy(replacementPhoto = replacement)
}

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    value?.let(::writeUTF)
}

private fun DataInputStream.readNullableString(): String? =
    if (readBoolean()) readUTF() else null

private fun failureData(outcome: DescriptionGenerationOutcome): Data = Data.Builder()
    .putString(WORK_FAILURE_OUTCOME, outcome.storageName)
    .build()

private const val MAX_INLINE_PHOTO_BYTES = 20L * 1024L * 1024L
private const val HOUSEHOLDS_COLLECTION = "households"
private const val ITEMS_COLLECTION = "items"
private const val ITEM_NAME_FIELD = "name"
private const val ITEM_PHOTO_URL_FIELD = "photoUrl"
private const val ITEM_PHOTO_THUMBNAIL_URL_FIELD = "photoThumbnailUrl"
private const val ITEM_DESCRIPTION_FIELD = "description"
private const val ITEM_TAGS_FIELD = "tags"
private const val ITEM_WEB_URL_FIELD = "webUrl"
private const val ITEM_UPDATED_AT_FIELD = "updatedAt"
private const val ITEM_UPDATED_BY_ID_FIELD = "updatedById"
private const val ITEM_UPDATED_BY_DISPLAY_NAME_FIELD = "updatedByDisplayName"
private const val DESCRIPTION_GENERATION_WORK_DIRECTORY = "description-generation-work"
private const val DESCRIPTION_GENERATION_PENDING_DIRECTORY = "pending"
private const val DESCRIPTION_GENERATION_COMPLETED_DIRECTORY = "completed"
private const val DESCRIPTION_GENERATION_REQUEST_PREFIX = "request-"
private const val UPLOADED_PHOTO_MARKER_EXTENSION = "uploaded"
private const val DESCRIPTION_GENERATION_WORK_TAG = "description-generation"
private const val WORK_REQUEST_ID = "request-id"
private const val WORK_HOUSEHOLD_ID = "household-id"
private const val WORK_FAILURE_OUTCOME = "failure-outcome"
private const val DESCRIPTION_GENERATION_WEBP_CONTENT_TYPE = "image/webp"
private const val GEMINI_ERROR_TYPE_RESOURCE_EXHAUSTED = "RESOURCE_EXHAUSTED"
