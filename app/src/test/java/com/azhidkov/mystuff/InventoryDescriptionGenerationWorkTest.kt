package com.azhidkov.mystuff

import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.ServerException
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InventoryDescriptionGenerationWorkTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `workflow saves the draft before using captured Gemini input and patching Description`() {
        val events = mutableListOf<String>()
        val photo = FakeDescriptionGenerationPhoto
        val store = RecordingDescriptionGenerationItemStore(events)
        val loader = RecordingDescriptionGenerationPhotoLoader(events, photo)
        val generator = RecordingDescriptionGenerator(
            events = events,
            output = DescriptionGenerationStep.Success("  A blue cordless drill.  "),
        )
        val workflow = DescriptionGenerationWorkflow(store, loader, generator)
        val request = descriptionGenerationRequest()

        val outcome = workflow.run(request)

        assertEquals(DescriptionGenerationOutcome.Success, outcome)
        assertEquals(
            listOf(
                "save:Member facts",
                "load:${request.item.photoUrl}",
                "generate:Member facts:fr-FR",
                "patch:A blue cordless drill.:member-1:Alex",
            ),
            events,
        )
        assertSame(photo, generator.inputs.single().photo)
        assertEquals("Member facts", generator.inputs.single().existingDescription)
        assertEquals("fr-FR", generator.inputs.single().deviceLanguage)
        assertTrue(
            generator.inputs.single().prompt.contains(
                "preserve every factual statement",
                ignoreCase = true,
            ),
        )
        assertTrue(generator.inputs.single().prompt.contains("Hammer Drill"))
        assertTrue(generator.inputs.single().prompt.contains("multiple things"))
        assertTrue(generator.inputs.single().prompt.contains("clearly visible"))
        assertTrue(generator.inputs.single().prompt.contains("brand or model"))
        assertTrue(generator.inputs.single().prompt.contains("plain-text paragraph"))
    }

    @Test
    fun `replacement workflow saves uploads cleans source then generates from stored revision`() {
        val events = mutableListOf<String>()
        val request = replacementDescriptionGenerationRequest()
        val workflow = DescriptionGenerationWorkflow(
            itemStore = RecordingDescriptionGenerationItemStore(events),
            photoLoader = RecordingDescriptionGenerationPhotoLoader(
                events,
                FakeDescriptionGenerationPhoto,
            ),
            generator = RecordingDescriptionGenerator(
                events,
                DescriptionGenerationStep.Success("Generated replacement"),
            ),
            fullPhotoUploader = RecordingDescriptionGenerationPhotoUploader(events),
            uploadedPhotoLedger = RecordingUploadedPhotoLedger(events),
            localPhotoSourceCleaner = RecordingLocalPhotoSourceCleaner(events),
        )

        val outcome = workflow.run(request)

        assertEquals(DescriptionGenerationOutcome.Success, outcome)
        assertEquals(
            listOf(
                "save:Member facts",
                "upload:households/household-1/items/drill-$REPLACEMENT_REVISION.webp:" +
                    "content://mystuff/new-full.webp",
                "mark-uploaded",
                "cleanup:content://mystuff/new-full.webp",
                "load:${request.item.photoUrl}",
                "generate:Member facts:fr-FR",
                "patch:Generated replacement:member-1:Alex",
            ),
            events,
        )
    }

    @Test
    fun `replacement capture allocates one revision and schedules only its thumbnail independently`() {
        val photos = RecordingDescriptionGenerationInventoryPhotoStore()
        val capture = DescriptionGenerationRequestCapture(photos)
        val replacement = ItemPhoto(
            "content://mystuff/new-full.webp",
            "content://mystuff/new-thumb.webp",
        )

        val request = capture.capture(descriptionGenerationRequest(), replacement)
        capture.uploadThumbnailInBackground(request)

        assertEquals(1, photos.allocatedRevisions)
        assertEquals(
            "gs://mystuff/households/household-1/items/drill-$REPLACEMENT_REVISION.webp",
            request.item.photoUrl,
        )
        assertEquals(
            "gs://mystuff/households/household-1/items/drill-$REPLACEMENT_REVISION-thumb.webp",
            request.item.photoThumbnailUrl,
        )
        assertEquals(
            DescriptionGenerationReplacementPhoto(
                revision = ItemPhotoRevision(
                    locations = ItemPhotoLocations(
                        full = "gs://mystuff/households/household-1/items/" +
                            "drill-$REPLACEMENT_REVISION.webp",
                        thumbnail = "gs://mystuff/households/household-1/items/" +
                            "drill-$REPLACEMENT_REVISION-thumb.webp",
                    ),
                    fullStoragePath =
                        "households/household-1/items/drill-$REPLACEMENT_REVISION.webp",
                    thumbnailStoragePath =
                        "households/household-1/items/drill-$REPLACEMENT_REVISION-thumb.webp",
                ),
                source = replacement,
            ),
            request.replacementPhoto,
        )
        assertEquals(listOf(replacement), photos.thumbnailUploads)
        assertTrue(photos.fullUploads.isEmpty())

        photos.thumbnailFailure = IllegalStateException("thumbnail scheduler unavailable")
        capture.uploadThumbnailInBackground(request)

        assertEquals(2, photos.thumbnailAttempts)
    }

    @Test
    fun `workflow trims valid output and rejects blank or oversized output without patching`() {
        val validStore = RecordingDescriptionGenerationItemStore(mutableListOf())
        val valid = workflow(
            store = validStore,
            generated = DescriptionGenerationStep.Success("  ${"🏠".repeat(2_000)}  "),
        ).run(descriptionGenerationRequest())

        assertEquals(DescriptionGenerationOutcome.Success, valid)
        assertEquals("🏠".repeat(2_000), validStore.patchedDescriptions.single())

        for (invalidOutput in listOf(" \n\t ", "🏠".repeat(2_001))) {
            val store = RecordingDescriptionGenerationItemStore(mutableListOf())

            val outcome = workflow(
                store = store,
                generated = DescriptionGenerationStep.Success(invalidOutput),
            ).run(descriptionGenerationRequest())

            assertEquals(DescriptionGenerationOutcome.PermanentGenerationFailure, outcome)
            assertTrue(store.patchedDescriptions.isEmpty())
        }
    }

    @Test
    fun `workflow reports failures as permanent stage outcomes`() {
        val failingWorkflows = listOf(
            DescriptionGenerationWorkflow(
                RecordingDescriptionGenerationItemStore(
                    mutableListOf(),
                    saveOutcome = DescriptionGenerationStep.PermanentFailure,
                ),
                successfulLoader(),
                successfulGenerator(),
            ),
            DescriptionGenerationWorkflow(
                RecordingDescriptionGenerationItemStore(mutableListOf()),
                FixedDescriptionGenerationPhotoLoader(DescriptionGenerationStep.PermanentFailure),
                successfulGenerator(),
            ),
            DescriptionGenerationWorkflow(
                RecordingDescriptionGenerationItemStore(mutableListOf()),
                successfulLoader(),
                FixedDescriptionGenerator(DescriptionGenerationStep.PermanentFailure),
            ),
            DescriptionGenerationWorkflow(
                RecordingDescriptionGenerationItemStore(
                    mutableListOf(),
                    patchOutcome = DescriptionGenerationStep.PermanentFailure,
                ),
                successfulLoader(),
                successfulGenerator(),
            ),
        )

        val expectedOutcomes = listOf(
            DescriptionGenerationOutcome.PermanentSaveFailure,
            DescriptionGenerationOutcome.PermanentPhotoFailure,
            DescriptionGenerationOutcome.PermanentGenerationFailure,
            DescriptionGenerationOutcome.PermanentGenerationFailure,
        )
        failingWorkflows.zip(expectedOutcomes).forEach { (workflow, expectedOutcome) ->
            assertEquals(
                expectedOutcome,
                workflow.run(descriptionGenerationRequest()),
            )
        }

        val replacementUploadFailure = DescriptionGenerationWorkflow(
            itemStore = RecordingDescriptionGenerationItemStore(mutableListOf()),
            photoLoader = successfulLoader(),
            generator = successfulGenerator(),
            fullPhotoUploader = DescriptionGenerationFullPhotoUploader {
                DescriptionGenerationStep.PermanentFailure
            },
        )
        assertEquals(
            DescriptionGenerationOutcome.PermanentPhotoFailure,
            replacementUploadFailure.run(replacementDescriptionGenerationRequest()),
        )
    }

    @Test
    fun `remote failure classifier reports connectivity throttling and service failures as permanent`() {
        val failureCategories = listOf(
            DescriptionGenerationFailureCategory.Connectivity,
            DescriptionGenerationFailureCategory.Throttling,
            DescriptionGenerationFailureCategory.RemoteService,
        )

        failureCategories.forEach { category ->
            assertEquals(
                DescriptionGenerationStep.PermanentFailure,
                classifyDescriptionGenerationFailure(category),
            )
        }
        assertEquals(
            DescriptionGenerationStep.PermanentFailure,
            classifyDescriptionGenerationFailure(IOException("offline")),
        )
        assertEquals(
            DescriptionGenerationStep.PermanentFailure,
            classifyDescriptionGenerationFailure(
                ExecutionException(IOException("wrapped offline failure")),
            ),
        )
        assertEquals(
            DescriptionGenerationStep.PermanentFailure,
            classifyDescriptionGenerationFailure(DescriptionGenerationFailureCategory.Permanent),
        )
    }

    @Test
    fun `Firebase AI quota exhaustion becomes a visible permanent generation failure`() {
        val failure = QuotaExceededException::class.java
            .getDeclaredConstructor(String::class.java, Throwable::class.java)
            .newInstance("RESOURCE_EXHAUSTED", null)

        val classified = classifyDescriptionGenerationFailure(failure)

        assertTrue(classified is DescriptionGenerationStep.PermanentFailureWithErrorType)
        assertEquals(
            "RESOURCE_EXHAUSTED",
            (classified as DescriptionGenerationStep.PermanentFailureWithErrorType).errorType,
        )
    }

    @Test
    fun `Firebase AI RESOURCE_EXHAUSTED server failure becomes a visible permanent generation failure`() {
        val failure = ServerException::class.java
            .getDeclaredConstructor(String::class.java, Throwable::class.java)
            .newInstance("RESOURCE_EXHAUSTED", null)

        val classified = classifyDescriptionGenerationFailure(failure)

        assertTrue(classified is DescriptionGenerationStep.PermanentFailureWithErrorType)
        assertEquals(
            "RESOURCE_EXHAUSTED",
            (classified as DescriptionGenerationStep.PermanentFailureWithErrorType).errorType,
        )
    }

    @Test
    fun `workflow distinguishes permanent Save and Description Generation failures`() {
        val saveFailure = DescriptionGenerationWorkflow(
            RecordingDescriptionGenerationItemStore(
                mutableListOf(),
                saveOutcome = DescriptionGenerationStep.PermanentFailure,
            ),
            successfulLoader(),
            successfulGenerator(),
        )
        val generationFailure = DescriptionGenerationWorkflow(
            RecordingDescriptionGenerationItemStore(mutableListOf()),
            successfulLoader(),
            FixedDescriptionGenerator(DescriptionGenerationStep.PermanentFailure),
        )
        val typedGenerationFailure = DescriptionGenerationWorkflow(
            RecordingDescriptionGenerationItemStore(mutableListOf()),
            successfulLoader(),
            FixedDescriptionGenerator(
                DescriptionGenerationStep.PermanentFailureWithErrorType("RESOURCE_EXHAUSTED"),
            ),
        )

        assertEquals(
            DescriptionGenerationOutcome.PermanentSaveFailure,
            saveFailure.run(descriptionGenerationRequest()),
        )
        assertEquals(
            DescriptionGenerationOutcome.PermanentGenerationFailure,
            generationFailure.run(descriptionGenerationRequest()),
        )
        assertEquals(
            DescriptionGenerationOutcome.PermanentGenerationFailureWithErrorType(
                "RESOURCE_EXHAUSTED",
            ),
            typedGenerationFailure.run(descriptionGenerationRequest()),
        )
    }

    @Test
    fun `workflow distinguishes a permanent full-photo load failure`() {
        val workflow = DescriptionGenerationWorkflow(
            RecordingDescriptionGenerationItemStore(mutableListOf()),
            FixedDescriptionGenerationPhotoLoader(DescriptionGenerationStep.PermanentFailure),
            successfulGenerator(),
        )

        assertEquals(
            DescriptionGenerationOutcome.PermanentPhotoFailure,
            workflow.run(descriptionGenerationRequest()),
        )
    }

    @Test
    fun `permanent replacement upload failure preserves saved Member Description`() {
        val events = mutableListOf<String>()
        val store = RecordingDescriptionGenerationItemStore(events)
        val workflow = DescriptionGenerationWorkflow(
            itemStore = store,
            photoLoader = successfulLoader(),
            generator = successfulGenerator(),
            fullPhotoUploader = RecordingDescriptionGenerationPhotoUploader(
                events,
                DescriptionGenerationStep.PermanentFailure,
            ),
            uploadedPhotoLedger = RecordingUploadedPhotoLedger(events),
        )

        val outcome = workflow.run(replacementDescriptionGenerationRequest())

        assertEquals(DescriptionGenerationOutcome.PermanentPhotoFailure, outcome)
        assertEquals("Member facts", store.currentDescription)
        assertFalse(events.any { it.startsWith("load:") || it.startsWith("patch:") })
    }

    @Test
    fun `new Item save mode survives work-store recreation`() {
        val directory = temporaryFolder.newFolder("new-item-description-generation")
        val request = replacementDescriptionGenerationRequest().copy(
            saveMode = DescriptionGenerationSaveMode.Create,
        )
        val id = DescriptionGenerationWorkStore(directory).enqueue(request)

        assertEquals(
            request,
            DescriptionGenerationWorkStore(directory).pendingRequest(id),
        )
    }

    @Test
    fun `permanent outcome survives work-store recreation until consumed`() {
        val directory = temporaryFolder.newFolder("description-generation")
        val request = descriptionGenerationRequest()
        val firstStore = DescriptionGenerationWorkStore(directory)
        val id = firstStore.enqueue(request)

        firstStore.complete(
            id = id,
            outcome = DescriptionGenerationOutcome.PermanentGenerationFailure,
        )

        val restored = DescriptionGenerationWorkStore(directory)
        assertEquals(
            listOf(
                CompletedDescriptionGeneration(
                    id = id,
                    householdId = request.householdId,
                    outcome = DescriptionGenerationOutcome.PermanentGenerationFailure,
                ),
            ),
            restored.snapshot().completed,
        )

        restored.consume(id)

        assertTrue(DescriptionGenerationWorkStore(directory).snapshot().completed.isEmpty())
    }

    @Test
    fun `LLM error type survives work-store recreation until shown`() {
        val directory = temporaryFolder.newFolder("typed-description-generation")
        val request = descriptionGenerationRequest()
        val firstStore = DescriptionGenerationWorkStore(directory)
        val id = firstStore.enqueue(request)

        firstStore.complete(
            id = id,
            outcome = DescriptionGenerationOutcome.PermanentGenerationFailureWithErrorType(
                "RESOURCE_EXHAUSTED",
            ),
        )

        assertEquals(
            listOf(
                CompletedDescriptionGeneration(
                    id = id,
                    householdId = request.householdId,
                    outcome = DescriptionGenerationOutcome.PermanentGenerationFailureWithErrorType(
                        "RESOURCE_EXHAUSTED",
                    ),
                ),
            ),
            DescriptionGenerationWorkStore(directory).snapshot().completed,
        )
    }

    @Test
    fun `unreadable request retains a permanent Save outcome for its Household`() {
        val directory = temporaryFolder.newFolder("unreadable-description-generation")
        val store = DescriptionGenerationWorkStore(directory)

        store.completeUnreadableRequest(
            id = "missing-request",
            householdId = "household-1",
        )

        assertEquals(
            listOf(
                CompletedDescriptionGeneration(
                    id = "missing-request",
                    householdId = "household-1",
                    outcome = DescriptionGenerationOutcome.PermanentSaveFailure,
                ),
            ),
            DescriptionGenerationWorkStore(directory).snapshot().completed,
        )
    }

    @Test
    fun `valid generated Description overwrites a newer Description`() {
        val events = mutableListOf<String>()
        val store = RecordingDescriptionGenerationItemStore(events)
        val generator = RecordingDescriptionGenerator(
            events = events,
            output = DescriptionGenerationStep.Success("Generated replacement"),
            beforeOutput = { store.currentDescription = "A newer Member edit" },
        )

        val outcome = DescriptionGenerationWorkflow(
            store,
            RecordingDescriptionGenerationPhotoLoader(events, FakeDescriptionGenerationPhoto),
            generator,
        ).run(descriptionGenerationRequest())

        assertEquals(DescriptionGenerationOutcome.Success, outcome)
        assertEquals("Generated replacement", store.currentDescription)
        assertFalse(events.any { it.contains("A newer Member edit") })
    }
}

private class RecordingDescriptionGenerationItemStore(
    private val events: MutableList<String>,
    private val saveOutcome: DescriptionGenerationStep<Unit> =
        DescriptionGenerationStep.Success(Unit),
    private val patchOutcome: DescriptionGenerationStep<Unit> =
        DescriptionGenerationStep.Success(Unit),
) : DescriptionGenerationItemStore {
    val patchedDescriptions = mutableListOf<String>()
    var currentDescription: String? = null

    override fun saveDraft(request: DescriptionGenerationRequest): DescriptionGenerationStep<Unit> {
        events += "save:${request.item.description}"
        if (saveOutcome is DescriptionGenerationStep.Success) {
            currentDescription = request.item.description
        }
        return saveOutcome
    }

    override fun patchDescription(
        householdId: String,
        itemId: String,
        description: String,
        requestingMember: RequestingMemberAttribution,
    ): DescriptionGenerationStep<Unit> {
        events += "patch:$description:${requestingMember.id}:${requestingMember.displayName}"
        if (patchOutcome is DescriptionGenerationStep.Success) {
            patchedDescriptions += description
            currentDescription = description
        }
        return patchOutcome
    }
}

private class RecordingDescriptionGenerationPhotoLoader(
    private val events: MutableList<String>,
    private val photo: DescriptionGenerationPhoto,
) : DescriptionGenerationPhotoLoader {
    override fun load(location: String): DescriptionGenerationStep<DescriptionGenerationPhoto> {
        events += "load:$location"
        return DescriptionGenerationStep.Success(photo)
    }
}

private class RecordingDescriptionGenerator(
    private val events: MutableList<String>,
    private val output: DescriptionGenerationStep<String>,
    private val beforeOutput: () -> Unit = {},
) : DescriptionGenerator {
    val inputs = mutableListOf<DescriptionGenerationModelInput>()

    override fun generate(
        input: DescriptionGenerationModelInput,
    ): DescriptionGenerationStep<String> {
        inputs += input
        events += "generate:${input.existingDescription}:${input.deviceLanguage}"
        beforeOutput()
        return output
    }
}

private class FixedDescriptionGenerationPhotoLoader(
    private val output: DescriptionGenerationStep<DescriptionGenerationPhoto>,
) : DescriptionGenerationPhotoLoader {
    override fun load(location: String) = output
}

private class SequencedDescriptionGenerationPhotoLoader(
    private val events: MutableList<String>,
    private val outputs: ArrayDeque<DescriptionGenerationStep<DescriptionGenerationPhoto>>,
) : DescriptionGenerationPhotoLoader {
    override fun load(location: String): DescriptionGenerationStep<DescriptionGenerationPhoto> {
        events += "load:$location"
        return outputs.removeFirst()
    }
}

private class FixedDescriptionGenerator(
    private val output: DescriptionGenerationStep<String>,
) : DescriptionGenerator {
    override fun generate(input: DescriptionGenerationModelInput) = output
}

private class RecordingDescriptionGenerationPhotoUploader(
    private val events: MutableList<String>,
    private val output: DescriptionGenerationStep<Unit> = DescriptionGenerationStep.Success(Unit),
) : DescriptionGenerationFullPhotoUploader {
    override fun upload(
        photo: DescriptionGenerationReplacementPhoto,
    ): DescriptionGenerationStep<Unit> {
        events += "upload:${photo.revision.fullStoragePath}:${photo.source.uri}"
        return output
    }
}

private class RecordingUploadedPhotoLedger(
    private val events: MutableList<String>,
) : DescriptionGenerationUploadedPhotoLedger {
    private var uploaded = false

    override fun isUploaded() = uploaded

    override fun markUploaded() {
        uploaded = true
        events += "mark-uploaded"
    }
}

private class RecordingLocalPhotoSourceCleaner(
    private val events: MutableList<String>,
    private val outputs: ArrayDeque<DescriptionGenerationStep<Unit>> = ArrayDeque(
        listOf(DescriptionGenerationStep.Success(Unit)),
    ),
) : DescriptionGenerationLocalPhotoSourceCleaner {
    override fun clean(sourceUri: String): DescriptionGenerationStep<Unit> {
        events += "cleanup:$sourceUri"
        return if (outputs.size == 1) outputs.first() else outputs.removeFirst()
    }
}

private class RecordingDescriptionGenerationInventoryPhotoStore : InventoryPhotoStore {
    var allocatedRevisions = 0
    var thumbnailAttempts = 0
    var thumbnailFailure: RuntimeException? = null
    val fullUploads = mutableListOf<ItemPhoto>()
    val thumbnailUploads = mutableListOf<ItemPhoto>()

    override fun newRevision(householdId: String, itemId: String): ItemPhotoRevision {
        allocatedRevisions += 1
        val revision = UUID.fromString(REPLACEMENT_REVISION)
        val full = photoStoragePath(householdId, itemId, revision, ItemPhotoVariant.Full)
        val thumbnail = photoStoragePath(
            householdId,
            itemId,
            revision,
            ItemPhotoVariant.Thumbnail,
        )
        return ItemPhotoRevision(
            locations = ItemPhotoLocations("gs://mystuff/$full", "gs://mystuff/$thumbnail"),
            fullStoragePath = full,
            thumbnailStoragePath = thumbnail,
        )
    }

    override fun uploadInBackground(revision: ItemPhotoRevision, photo: ItemPhoto) {
        fullUploads += photo
    }

    override fun uploadThumbnailInBackground(revision: ItemPhotoRevision, photo: ItemPhoto) {
        thumbnailAttempts += 1
        thumbnailFailure?.let { throw it }
        thumbnailUploads += photo
    }

    override fun deleteInBackground(locations: StoredItemPhotoLocations) = Unit
}

private val FakeDescriptionGenerationPhoto = DescriptionGenerationPhoto(byteArrayOf(1, 2, 3))

private fun workflow(
    store: RecordingDescriptionGenerationItemStore,
    generated: DescriptionGenerationStep<String>,
) = DescriptionGenerationWorkflow(
    store,
    successfulLoader(),
    FixedDescriptionGenerator(generated),
)

private fun successfulLoader() = FixedDescriptionGenerationPhotoLoader(
    DescriptionGenerationStep.Success(FakeDescriptionGenerationPhoto),
)

private fun successfulGenerator() = FixedDescriptionGenerator(
    DescriptionGenerationStep.Success("Generated description"),
)

private fun descriptionGenerationRequest() = DescriptionGenerationRequest(
    householdId = "household-1",
    item = Item(
        id = "drill",
        name = "Hammer Drill",
        parentItemId = "household-1",
        photoUrl = "gs://mystuff/households/household-1/items/drill-revision.webp",
        photoThumbnailUrl =
            "gs://mystuff/households/household-1/items/drill-revision-thumb.webp",
        description = "Member facts",
        tags = listOf("Power Tools"),
        webUrl = "https://example.com/drill",
    ),
    requestingMember = RequestingMemberAttribution("member-1", "Alex"),
    deviceLanguage = "fr-FR",
)

private fun replacementDescriptionGenerationRequest() = descriptionGenerationRequest().copy(
    item = descriptionGenerationRequest().item.copy(
        photoUrl = "gs://mystuff/households/household-1/items/drill-$REPLACEMENT_REVISION.webp",
        photoThumbnailUrl =
            "gs://mystuff/households/household-1/items/drill-$REPLACEMENT_REVISION-thumb.webp",
    ),
    replacementPhoto = DescriptionGenerationReplacementPhoto(
        revision = ItemPhotoRevision(
            locations = ItemPhotoLocations(
                full = "gs://mystuff/households/household-1/items/" +
                    "drill-$REPLACEMENT_REVISION.webp",
                thumbnail = "gs://mystuff/households/household-1/items/" +
                    "drill-$REPLACEMENT_REVISION-thumb.webp",
            ),
            fullStoragePath =
                "households/household-1/items/drill-$REPLACEMENT_REVISION.webp",
            thumbnailStoragePath =
                "households/household-1/items/drill-$REPLACEMENT_REVISION-thumb.webp",
        ),
        source = ItemPhoto(
            uri = "content://mystuff/new-full.webp",
            thumbnailUri = "content://mystuff/new-thumb.webp",
        ),
    ),
)

private const val REPLACEMENT_REVISION = "11111111-1111-1111-1111-111111111111"
