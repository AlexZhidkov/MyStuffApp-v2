package com.azhidkov.mystuff

import android.content.Context
import android.graphics.Bitmap
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
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

internal data class DescriptionGenerationRequest(
    val householdId: String,
    val item: Item,
    val requestingMemberId: String,
    val requestingMemberDisplayName: String,
    val deviceLanguage: String,
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

internal interface DescriptionGenerationPhoto

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
        requestingMemberId: String,
        requestingMemberDisplayName: String,
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
                requestingMemberId = request.requestingMemberId,
                requestingMemberDisplayName = request.requestingMemberDisplayName,
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

    override fun submit(request: DescriptionGenerationRequest) {
        val work = OneTimeWorkRequestBuilder<InventoryDescriptionGenerationWorker>()
            .setInputData(request.toWorkData())
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
    }
}

internal class InventoryDescriptionGenerationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val request = descriptionGenerationRequestFromWorkData(inputData)
            ?: return Result.failure(failureData(DescriptionGenerationOutcome.PermanentSaveFailure))
        val workflow = DescriptionGenerationWorkflow(
            itemStore = FirebaseDescriptionGenerationItemStore(),
            photoLoader = FirebaseDescriptionGenerationPhotoLoader(),
            generator = FirebaseGeminiDescriptionGenerator(),
        )
        return when (val outcome = workflow.run(request)) {
            DescriptionGenerationOutcome.Success -> Result.success()
            DescriptionGenerationOutcome.Retry -> Result.retry()
            DescriptionGenerationOutcome.PermanentSaveFailure,
            DescriptionGenerationOutcome.PermanentGenerationFailure,
            -> Result.failure(failureData(outcome))
        }
    }
}

private data class BitmapDescriptionGenerationPhoto(
    val bitmap: Bitmap,
) : DescriptionGenerationPhoto

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
                    ITEM_UPDATED_BY_ID_FIELD to request.requestingMemberId,
                    ITEM_UPDATED_BY_DISPLAY_NAME_FIELD to request.requestingMemberDisplayName,
                ),
            ),
        )
    }

    override fun patchDescription(
        householdId: String,
        itemId: String,
        description: String,
        requestingMemberId: String,
        requestingMemberDisplayName: String,
    ): DescriptionGenerationStep<Unit> = firebaseStep {
        Tasks.await(
            itemDocument(householdId, itemId).update(
                mapOf(
                    ITEM_DESCRIPTION_FIELD to description,
                    ITEM_UPDATED_AT_FIELD to FieldValue.serverTimestamp(),
                    ITEM_UPDATED_BY_ID_FIELD to requestingMemberId,
                    ITEM_UPDATED_BY_DISPLAY_NAME_FIELD to requestingMemberDisplayName,
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
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw InvalidStoredItemPhotoException()
            BitmapDescriptionGenerationPhoto(bitmap)
        }
}

private class FirebaseGeminiDescriptionGenerator : DescriptionGenerator {
    override fun generate(
        input: DescriptionGenerationModelInput,
    ): DescriptionGenerationStep<String> {
        val photo = input.photo as? BitmapDescriptionGenerationPhoto
            ?: return DescriptionGenerationStep.PermanentFailure
        return try {
            val model = Firebase.ai(backend = GenerativeBackend.googleAI())
                .generativeModel(DESCRIPTION_GENERATION_MODEL)
            val prompt = content {
                image(photo.bitmap)
                text(input.prompt)
            }
            val response = runBlocking { model.generateContent(prompt) }
            DescriptionGenerationStep.Success(response.text.orEmpty())
        } catch (failure: Exception) {
            failure.toDescriptionGenerationFailure()
        } finally {
            photo.bitmap.recycle()
        }
    }
}

private class InvalidStoredItemPhotoException : IllegalStateException()

private fun <T> firebaseStep(block: () -> T): DescriptionGenerationStep<T> = try {
    DescriptionGenerationStep.Success(block())
} catch (failure: Exception) {
    failure.toDescriptionGenerationFailure()
}

private fun Throwable.toDescriptionGenerationFailure(): DescriptionGenerationStep<Nothing> =
    if (unwrapExecutionFailure().isRetryableRemoteFailure()) {
        DescriptionGenerationStep.RetryableFailure
    } else {
        DescriptionGenerationStep.PermanentFailure
    }

private fun Throwable.unwrapExecutionFailure(): Throwable =
    if (this is ExecutionException && cause != null) requireNotNull(cause) else this

private fun Throwable.isRetryableRemoteFailure(): Boolean = when (this) {
    is IOException,
    is InterruptedException,
    is FirebaseNetworkException,
    is FirebaseTooManyRequestsException,
    is QuotaExceededException,
    is RequestTimeoutException,
    is ServerException,
    is ServiceConnectionHandshakeFailedException,
    is UnknownException,
    -> true
    is FirebaseFirestoreException -> code in setOf(
        FirebaseFirestoreException.Code.ABORTED,
        FirebaseFirestoreException.Code.CANCELLED,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.INTERNAL,
        FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.UNKNOWN,
    )
    is StorageException -> errorCode in setOf(
        StorageException.ERROR_RETRY_LIMIT_EXCEEDED,
        StorageException.ERROR_QUOTA_EXCEEDED,
        StorageException.ERROR_UNKNOWN,
    )
    else -> false
}

private fun DescriptionGenerationRequest.toWorkData(): Data = Data.Builder()
    .putString(WORK_HOUSEHOLD_ID, householdId)
    .putString(WORK_ITEM_ID, item.id)
    .putString(WORK_ITEM_NAME, item.name)
    .putString(WORK_PARENT_ITEM_ID, item.parentItemId)
    .putString(WORK_PHOTO_URL, item.photoUrl)
    .putString(WORK_PHOTO_THUMBNAIL_URL, item.photoThumbnailUrl)
    .putString(WORK_DESCRIPTION, item.description)
    .putStringArray(WORK_TAGS, item.tags.toTypedArray())
    .putString(WORK_WEB_URL, item.webUrl)
    .putString(WORK_REQUESTING_MEMBER_ID, requestingMemberId)
    .putString(WORK_REQUESTING_MEMBER_DISPLAY_NAME, requestingMemberDisplayName)
    .putString(WORK_DEVICE_LANGUAGE, deviceLanguage)
    .build()

private fun descriptionGenerationRequestFromWorkData(
    data: Data,
): DescriptionGenerationRequest? = runCatching {
    DescriptionGenerationRequest(
        householdId = requireNotNull(data.getString(WORK_HOUSEHOLD_ID)),
        item = Item(
            id = requireNotNull(data.getString(WORK_ITEM_ID)),
            name = requireNotNull(data.getString(WORK_ITEM_NAME)),
            parentItemId = requireNotNull(data.getString(WORK_PARENT_ITEM_ID)),
            photoUrl = requireNotNull(data.getString(WORK_PHOTO_URL)),
            photoThumbnailUrl = data.getString(WORK_PHOTO_THUMBNAIL_URL),
            description = data.getString(WORK_DESCRIPTION),
            tags = requireNotNull(data.getStringArray(WORK_TAGS)).toList(),
            webUrl = data.getString(WORK_WEB_URL),
        ),
        requestingMemberId = requireNotNull(data.getString(WORK_REQUESTING_MEMBER_ID)),
        requestingMemberDisplayName =
            requireNotNull(data.getString(WORK_REQUESTING_MEMBER_DISPLAY_NAME)),
        deviceLanguage = requireNotNull(data.getString(WORK_DEVICE_LANGUAGE)),
    )
}.getOrNull()

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
private const val WORK_HOUSEHOLD_ID = "household-id"
private const val WORK_ITEM_ID = "item-id"
private const val WORK_ITEM_NAME = "item-name"
private const val WORK_PARENT_ITEM_ID = "parent-item-id"
private const val WORK_PHOTO_URL = "photo-url"
private const val WORK_PHOTO_THUMBNAIL_URL = "photo-thumbnail-url"
private const val WORK_DESCRIPTION = "description"
private const val WORK_TAGS = "tags"
private const val WORK_WEB_URL = "web-url"
private const val WORK_REQUESTING_MEMBER_ID = "requesting-member-id"
private const val WORK_REQUESTING_MEMBER_DISPLAY_NAME = "requesting-member-display-name"
private const val WORK_DEVICE_LANGUAGE = "device-language"
private const val WORK_FAILURE_OUTCOME = "failure-outcome"
