package com.azhidkov.mystuff

import android.content.Context
import android.graphics.BitmapFactory
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

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

internal fun interface InventoryDescriptionGenerationWork {
    fun submit(request: DescriptionGenerationRequest)
}

internal object NoInventoryDescriptionGenerationWork : InventoryDescriptionGenerationWork {
    override fun submit(request: DescriptionGenerationRequest) = Unit
}

internal sealed interface DescriptionGenerationStep<out T> {
    data class Success<T>(val value: T) : DescriptionGenerationStep<T>
    data object RetryableFailure : DescriptionGenerationStep<Nothing>
    data object PermanentFailure : DescriptionGenerationStep<Nothing>
}

internal enum class DescriptionGenerationOutcome {
    Success,
    Retry,
    PermanentSaveFailure,
    PermanentGenerationFailure,
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
                return DescriptionGenerationOutcome.PermanentGenerationFailure
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
    private val requestStore = DescriptionGenerationRequestStore(context.noBackupFilesDir)

    override fun submit(request: DescriptionGenerationRequest) {
        val requestFile = requestStore.write(request)
        try {
            val work = OneTimeWorkRequestBuilder<InventoryDescriptionGenerationWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(WORK_REQUEST_FILE, requestFile.absolutePath)
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
                .build()
            workManager.enqueue(work)
        } catch (failure: RuntimeException) {
            requestStore.delete(requestFile)
            throw failure
        }
    }
}

internal class InventoryDescriptionGenerationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val requestStore = DescriptionGenerationRequestStore(applicationContext.noBackupFilesDir)
        val requestFile = inputData.getString(WORK_REQUEST_FILE)?.let(::File)
        val request = requestFile?.let(requestStore::read)
        if (request == null) {
            requestFile?.let(requestStore::delete)
            return Result.failure(failureData(DescriptionGenerationOutcome.PermanentSaveFailure))
        }
        val workflow = DescriptionGenerationWorkflow(
            itemStore = FirebaseDescriptionGenerationItemStore(),
            photoLoader = FirebaseDescriptionGenerationPhotoLoader(),
            generator = FirebaseGeminiDescriptionGenerator(),
        )
        val outcome = workflow.run(request)
        if (outcome != DescriptionGenerationOutcome.Retry) {
            requestStore.delete(requestFile)
        }
        return when (outcome) {
            DescriptionGenerationOutcome.Success -> Result.success()
            DescriptionGenerationOutcome.Retry -> Result.retry()
            DescriptionGenerationOutcome.PermanentSaveFailure,
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

private class DescriptionGenerationRequestStore(baseDirectory: File) {
    private val directory = File(baseDirectory, DESCRIPTION_GENERATION_REQUEST_DIRECTORY)

    fun write(request: DescriptionGenerationRequest): File {
        check(directory.mkdirs() || directory.isDirectory) {
            "Description Generation request storage is unavailable"
        }
        return File.createTempFile(DESCRIPTION_GENERATION_REQUEST_PREFIX, ".json", directory)
            .also { file -> file.writeText(request.toJson().toString()) }
    }

    fun read(file: File): DescriptionGenerationRequest? = resolve(file)?.let { resolved ->
        runCatching { descriptionGenerationRequestFromJson(JSONObject(resolved.readText())) }
            .getOrNull()
    }

    fun delete(file: File) {
        resolve(file)?.delete()
    }

    private fun resolve(file: File): File? = runCatching {
        val expectedDirectory = directory.canonicalFile
        file.canonicalFile.takeIf { candidate -> candidate.parentFile == expectedDirectory }
    }.getOrNull()
}

private fun DescriptionGenerationRequest.toJson(): JSONObject = JSONObject()
    .put(JSON_HOUSEHOLD_ID, householdId)
    .put(JSON_ITEM_ID, item.id)
    .put(JSON_ITEM_NAME, item.name)
    .put(JSON_PARENT_ITEM_ID, requireNotNull(item.parentItemId))
    .put(JSON_PHOTO_URL, requireNotNull(item.photoUrl))
    .put(JSON_PHOTO_THUMBNAIL_URL, item.photoThumbnailUrl ?: JSONObject.NULL)
    .put(JSON_DESCRIPTION, item.description ?: JSONObject.NULL)
    .put(JSON_TAGS, JSONArray(item.tags))
    .put(JSON_WEB_URL, item.webUrl ?: JSONObject.NULL)
    .put(JSON_REQUESTING_MEMBER_ID, requestingMember.id)
    .put(JSON_REQUESTING_MEMBER_DISPLAY_NAME, requestingMember.displayName)
    .put(JSON_DEVICE_LANGUAGE, deviceLanguage)

private fun descriptionGenerationRequestFromJson(json: JSONObject) =
    DescriptionGenerationRequest(
        householdId = json.getString(JSON_HOUSEHOLD_ID),
        item = Item(
            id = json.getString(JSON_ITEM_ID),
            name = json.getString(JSON_ITEM_NAME),
            parentItemId = json.getString(JSON_PARENT_ITEM_ID),
            photoUrl = json.getString(JSON_PHOTO_URL),
            photoThumbnailUrl = json.nullableString(JSON_PHOTO_THUMBNAIL_URL),
            description = json.nullableString(JSON_DESCRIPTION),
            tags = json.getJSONArray(JSON_TAGS).let { tags ->
                List(tags.length(), tags::getString)
            },
            webUrl = json.nullableString(JSON_WEB_URL),
        ),
        requestingMember = RequestingMemberAttribution(
            id = json.getString(JSON_REQUESTING_MEMBER_ID),
            displayName = json.getString(JSON_REQUESTING_MEMBER_DISPLAY_NAME),
        ),
        deviceLanguage = json.getString(JSON_DEVICE_LANGUAGE),
    )

private fun JSONObject.nullableString(key: String): String? =
    if (isNull(key)) null else getString(key)

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
private const val DESCRIPTION_GENERATION_REQUEST_DIRECTORY = "description-generation-work"
private const val DESCRIPTION_GENERATION_REQUEST_PREFIX = "request-"
private const val WORK_REQUEST_FILE = "request-file"
private const val WORK_FAILURE_OUTCOME = "failure-outcome"
private const val JSON_HOUSEHOLD_ID = "householdId"
private const val JSON_ITEM_ID = "itemId"
private const val JSON_ITEM_NAME = "itemName"
private const val JSON_PARENT_ITEM_ID = "parentItemId"
private const val JSON_PHOTO_URL = "photoUrl"
private const val JSON_PHOTO_THUMBNAIL_URL = "photoThumbnailUrl"
private const val JSON_DESCRIPTION = "description"
private const val JSON_TAGS = "tags"
private const val JSON_WEB_URL = "webUrl"
private const val JSON_REQUESTING_MEMBER_ID = "requestingMemberId"
private const val JSON_REQUESTING_MEMBER_DISPLAY_NAME = "requestingMemberDisplayName"
private const val JSON_DEVICE_LANGUAGE = "deviceLanguage"
