package com.azhidkov.mystuff

import android.content.Context
import android.graphics.BitmapFactory
import androidx.lifecycle.Observer
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

internal data class DescriptionGenerationRequest(
    val householdId: String,
    val item: Item,
    val requestingMember: RequestingMemberAttribution,
    val deviceLanguage: String,
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
    val request: DescriptionGenerationRequest,
    val outcome: DescriptionGenerationOutcome,
)

internal data class DescriptionGenerationWorkState(
    val pending: List<PendingDescriptionGeneration> = emptyList(),
    val completed: List<CompletedDescriptionGeneration> = emptyList(),
)

internal interface InventoryDescriptionGenerationWork {
    fun submit(request: DescriptionGenerationRequest): String

    fun observe(onChanged: (DescriptionGenerationWorkState) -> Unit): InventorySubscription

    fun consumeOutcome(id: String)
}

internal object NoInventoryDescriptionGenerationWork : InventoryDescriptionGenerationWork {
    override fun submit(request: DescriptionGenerationRequest) = ""

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
    data object RetryableFailure : DescriptionGenerationStep<Nothing>
    data object PermanentFailure : DescriptionGenerationStep<Nothing>
}

internal enum class DescriptionGenerationOutcome(
    val deferredErrorMessage: String? = null,
) {
    Success,
    Retry,
    PermanentSaveFailure("Couldn't save the Item."),
    PermanentPhotoFailure("Item saved, but couldn't upload its photo."),
    PermanentGenerationFailure("Item saved, but couldn't generate its description."),
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

internal fun interface DescriptionGenerator {
    fun generate(input: DescriptionGenerationModelInput): DescriptionGenerationStep<String>
}

internal class DescriptionGenerationWorkflow(
    private val itemStore: DescriptionGenerationItemStore,
    private val photoLoader: DescriptionGenerationPhotoLoader,
    private val generator: DescriptionGenerator,
) {
    fun run(request: DescriptionGenerationRequest): DescriptionGenerationOutcome {
        when (itemStore.saveDraft(request)) {
            is DescriptionGenerationStep.Success -> Unit
            DescriptionGenerationStep.RetryableFailure ->
                return DescriptionGenerationOutcome.Retry
            DescriptionGenerationStep.PermanentFailure ->
                return DescriptionGenerationOutcome.PermanentSaveFailure
        }

        val photo = when (
            val loaded = photoLoader.load(requireNotNull(request.item.photoUrl))
        ) {
            is DescriptionGenerationStep.Success -> loaded.value
            DescriptionGenerationStep.RetryableFailure ->
                return DescriptionGenerationOutcome.Retry
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
                        existingDescription = request.item.description,
                        deviceLanguage = request.deviceLanguage,
                    ),
                ),
            )
        ) {
            is DescriptionGenerationStep.Success -> result.value.trimUnicodeWhitespace()
            DescriptionGenerationStep.RetryableFailure ->
                return DescriptionGenerationOutcome.Retry
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
            itemStore.patchDescription(
                householdId = request.householdId,
                itemId = request.item.id,
                description = generated,
                requestingMember = request.requestingMember,
            )
        ) {
            is DescriptionGenerationStep.Success -> DescriptionGenerationOutcome.Success
            DescriptionGenerationStep.RetryableFailure -> DescriptionGenerationOutcome.Retry
            DescriptionGenerationStep.PermanentFailure ->
                DescriptionGenerationOutcome.PermanentGenerationFailure
        }
    }
}

internal fun descriptionGenerationPrompt(
    existingDescription: String?,
    deviceLanguage: String,
): String = """
    Write one concise plain-text paragraph describing the Item shown in the photo.
    Preserve every factual statement from the Member-written Description below, rewriting only
    for clarity. Add only identifying details that are clearly visible in the photo. Do not make
    unsupported brand or model claims. Do not use Markdown, headings, or lists.
    Use the Member-written Description's language when it is detectable; otherwise use the
    captured device language: $deviceLanguage.

    Member-written Description:
    ${existingDescription.orEmpty()}
""".trimIndent()

internal class WorkManagerInventoryDescriptionGenerationWork(
    context: Context,
) : InventoryDescriptionGenerationWork {
    private val workManager = WorkManager.getInstance(context)
    private val workStore = DescriptionGenerationWorkStore(context.noBackupFilesDir)
    private val observers = mutableSetOf<(DescriptionGenerationWorkState) -> Unit>()
    private val workInfoObserver = Observer<List<WorkInfo>> { emitState() }

    init {
        workManager.getWorkInfosByTagLiveData(DESCRIPTION_GENERATION_WORK_TAG)
            .observeForever(workInfoObserver)
    }

    override fun submit(request: DescriptionGenerationRequest): String {
        val id = workStore.enqueue(request)
        try {
            val work = OneTimeWorkRequestBuilder<InventoryDescriptionGenerationWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(WORK_REQUEST_ID, id)
                        .build(),
                )
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
                .addTag(DESCRIPTION_GENERATION_WORK_TAG)
                .build()
            workManager.enqueue(work)
        } catch (failure: RuntimeException) {
            workStore.discardPending(id)
            throw failure
        }
        emitState()
        return id
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
        val requestId = inputData.getString(WORK_REQUEST_ID)
        val request = requestId?.let(workStore::pendingRequest)
        if (request == null) {
            requestId?.let(workStore::discardPending)
            return Result.failure(failureData(DescriptionGenerationOutcome.PermanentSaveFailure))
        }
        val workflow = DescriptionGenerationWorkflow(
            itemStore = FirebaseDescriptionGenerationItemStore(),
            photoLoader = FirebaseDescriptionGenerationPhotoLoader(),
            generator = FirebaseGeminiDescriptionGenerator(),
        )
        val outcome = workflow.run(request)
        if (outcome != DescriptionGenerationOutcome.Retry) {
            workStore.complete(
                id = requestId,
                outcome = outcome,
            )
        }
        return when (outcome) {
            DescriptionGenerationOutcome.Success -> Result.success()
            DescriptionGenerationOutcome.Retry -> Result.retry()
            DescriptionGenerationOutcome.PermanentSaveFailure,
            DescriptionGenerationOutcome.PermanentPhotoFailure,
            DescriptionGenerationOutcome.PermanentGenerationFailure,
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

private class FirebaseGeminiDescriptionGenerator : DescriptionGenerator {
    override fun generate(
        input: DescriptionGenerationModelInput,
    ): DescriptionGenerationStep<String> {
        val bitmap = BitmapFactory.decodeByteArray(input.photo.bytes, 0, input.photo.bytes.size)
            ?: return DescriptionGenerationStep.PermanentFailure
        return try {
            val model = Firebase.ai(backend = GenerativeBackend.googleAI())
                .generativeModel(DESCRIPTION_GENERATION_MODEL)
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
): DescriptionGenerationStep<Nothing> = classifyDescriptionGenerationFailure(
    failure.unwrapExecutionFailure().descriptionGenerationFailureCategory(),
)

internal fun classifyDescriptionGenerationFailure(
    category: DescriptionGenerationFailureCategory,
): DescriptionGenerationStep<Nothing> =
    if (category != DescriptionGenerationFailureCategory.Permanent) {
        DescriptionGenerationStep.RetryableFailure
    } else {
        DescriptionGenerationStep.PermanentFailure
    }

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
    is FirebaseTooManyRequestsException,
    is QuotaExceededException,
    -> DescriptionGenerationFailureCategory.Throttling
    is RequestTimeoutException,
    is ServerException,
    is ServiceConnectionHandshakeFailedException,
    is UnknownException,
    -> DescriptionGenerationFailureCategory.RemoteService
    is FirebaseFirestoreException -> if (code.isRetryable()) {
        DescriptionGenerationFailureCategory.RemoteService
    } else {
        DescriptionGenerationFailureCategory.Permanent
    }
    is StorageException -> if (errorCode.isRetryableStorageError()) {
        DescriptionGenerationFailureCategory.RemoteService
    } else {
        DescriptionGenerationFailureCategory.Permanent
    }
    else -> DescriptionGenerationFailureCategory.Permanent
}

private fun FirebaseFirestoreException.Code.isRetryable(): Boolean = when (this) {
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

private fun Int.isRetryableStorageError(): Boolean = when (this) {
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

    fun complete(
        id: String,
        outcome: DescriptionGenerationOutcome,
    ) {
        val request = pendingRequest(id) ?: return
        ensureDirectory(completedDirectory)
        val destination = resolve(completedDirectory, id) ?: return
        val temporary = File.createTempFile("completed-", ".tmp", completedDirectory)
        DataOutputStream(temporary.outputStream().buffered()).use { output ->
            output.writeRequest(request)
            output.writeUTF(outcome.name)
        }
        check(temporary.renameTo(destination)) {
            "Description Generation outcome storage is unavailable"
        }
        discardPending(id)
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
    }

    private fun readCompleted(file: File): CompletedDescriptionGeneration? = runCatching {
        DataInputStream(file.inputStream().buffered()).use { input ->
            CompletedDescriptionGeneration(
                id = file.nameWithoutExtension,
                request = input.readRequest(),
                outcome = DescriptionGenerationOutcome.valueOf(input.readUTF()),
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
        val expectedDirectory = directory.canonicalFile
        File(directory, "$id.bin").canonicalFile.takeIf { candidate ->
            candidate.parentFile == expectedDirectory
        }
    }.getOrNull()

    private fun ensureDirectory(directory: File) {
        check(directory.mkdirs() || directory.isDirectory) {
            "Description Generation work storage is unavailable"
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
}

private fun DataInputStream.readRequest() = DescriptionGenerationRequest(
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

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    value?.let(::writeUTF)
}

private fun DataInputStream.readNullableString(): String? =
    if (readBoolean()) readUTF() else null

private fun failureData(outcome: DescriptionGenerationOutcome): Data = Data.Builder()
    .putString(WORK_FAILURE_OUTCOME, outcome.name)
    .build()

private const val DESCRIPTION_GENERATION_MODEL = "gemini-3.7-flash"
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
private const val DESCRIPTION_GENERATION_WORK_TAG = "description-generation"
private const val WORK_REQUEST_ID = "request-id"
private const val WORK_FAILURE_OUTCOME = "failure-outcome"
