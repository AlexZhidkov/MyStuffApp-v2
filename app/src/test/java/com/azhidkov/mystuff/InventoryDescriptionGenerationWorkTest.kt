package com.azhidkov.mystuff

import java.io.IOException
import java.util.concurrent.ExecutionException
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
        assertTrue(generator.inputs.single().prompt.contains("clearly visible"))
        assertTrue(generator.inputs.single().prompt.contains("brand or model"))
        assertTrue(generator.inputs.single().prompt.contains("plain-text paragraph"))
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
    fun `workflow retries retryable failures from every remote stage`() {
        val retryingWorkflows = listOf(
            DescriptionGenerationWorkflow(
                RecordingDescriptionGenerationItemStore(
                    mutableListOf(),
                    saveOutcome = DescriptionGenerationStep.RetryableFailure,
                ),
                successfulLoader(),
                successfulGenerator(),
            ),
            DescriptionGenerationWorkflow(
                RecordingDescriptionGenerationItemStore(mutableListOf()),
                FixedDescriptionGenerationPhotoLoader(DescriptionGenerationStep.RetryableFailure),
                successfulGenerator(),
            ),
            DescriptionGenerationWorkflow(
                RecordingDescriptionGenerationItemStore(mutableListOf()),
                successfulLoader(),
                FixedDescriptionGenerator(DescriptionGenerationStep.RetryableFailure),
            ),
            DescriptionGenerationWorkflow(
                RecordingDescriptionGenerationItemStore(
                    mutableListOf(),
                    patchOutcome = DescriptionGenerationStep.RetryableFailure,
                ),
                successfulLoader(),
                successfulGenerator(),
            ),
        )

        retryingWorkflows.forEach { workflow ->
            assertEquals(
                DescriptionGenerationOutcome.Retry,
                workflow.run(descriptionGenerationRequest()),
            )
        }
    }

    @Test
    fun `remote failure classifier retries connectivity throttling and service failures`() {
        val retryableCategories = listOf(
            DescriptionGenerationFailureCategory.Connectivity,
            DescriptionGenerationFailureCategory.Throttling,
            DescriptionGenerationFailureCategory.RemoteService,
        )

        retryableCategories.forEach { category ->
            assertEquals(
                DescriptionGenerationStep.RetryableFailure,
                classifyDescriptionGenerationFailure(category),
            )
        }
        assertEquals(
            DescriptionGenerationStep.RetryableFailure,
            classifyDescriptionGenerationFailure(IOException("offline")),
        )
        assertEquals(
            DescriptionGenerationStep.RetryableFailure,
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

        assertEquals(
            DescriptionGenerationOutcome.PermanentSaveFailure,
            saveFailure.run(descriptionGenerationRequest()),
        )
        assertEquals(
            DescriptionGenerationOutcome.PermanentGenerationFailure,
            generationFailure.run(descriptionGenerationRequest()),
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

private class FixedDescriptionGenerator(
    private val output: DescriptionGenerationStep<String>,
) : DescriptionGenerator {
    override fun generate(input: DescriptionGenerationModelInput) = output
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
