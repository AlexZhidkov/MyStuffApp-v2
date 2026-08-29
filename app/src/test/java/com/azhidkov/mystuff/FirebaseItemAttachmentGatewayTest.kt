package com.azhidkov.mystuff

import com.google.firebase.Timestamp
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseItemAttachmentGatewayTest {
    @Test
    fun `creating an Item Attachment writes only immutable file metadata`() {
        val createdAt = Timestamp(1_700_000_000, 123_000_000)
        val store = FakeItemAttachmentDocumentStore(
            serverTimestamp = Any(),
            createdDocument = ItemAttachmentDocument(
                id = "attachment-1",
                data = mapOf(
                    "createdAt" to createdAt,
                    "contentType" to "image/webp",
                    "displayUrl" to "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp",
                ),
            ),
        )
        val gateway = FirebaseItemAttachmentGateway(store)
        var result: Result<ItemAttachment>? = null

        gateway.create(
            household = attachmentHousehold(),
            item = attachmentItem(),
            attachmentId = "attachment-1",
            contentType = "image/webp",
            displayUrl = "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp",
        ) { result = it }

        assertEquals(
            mapOf(
                "createdAt" to store.serverTimestamp,
                "contentType" to "image/webp",
                "displayUrl" to "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp",
            ),
            store.createdData,
        )
        assertEquals(
            ItemAttachment(
                id = "attachment-1",
                itemId = "item-1",
                createdAt = Instant.ofEpochSecond(1_700_000_000, 123_000_000),
                contentType = "image/webp",
                displayUrl = "gs://mystuff/households/household-1/items/item-1/attachments/attachment-1.webp",
            ),
            result?.getOrThrow(),
        )
    }

    @Test
    fun `observing an Item returns attachments in creation order`() {
        val first = attachmentDocument("attachment-1", 1)
        val second = attachmentDocument("attachment-2", 2)
        val store = FakeItemAttachmentDocumentStore(
            documents = listOf(second, first),
        )
        val gateway = FirebaseItemAttachmentGateway(store)
        var result: Result<List<ItemAttachment>>? = null

        gateway.observe(attachmentHousehold(), attachmentItem()) { result = it }

        assertEquals(
            listOf("attachment-1", "attachment-2"),
            result?.getOrThrow()?.map(ItemAttachment::id),
        )
        assertEquals("item-1", store.observedItemId)
    }

    @Test
    fun `Household cannot own an Item Attachment`() {
        val store = FakeItemAttachmentDocumentStore()
        val gateway = FirebaseItemAttachmentGateway(store)
        var result: Result<ItemAttachment>? = null

        gateway.create(
            household = attachmentHousehold(),
            item = attachmentHousehold().rootItem,
            attachmentId = "attachment-1",
            contentType = "image/webp",
            displayUrl = "gs://mystuff/attachment.webp",
        ) { result = it }

        assertTrue(result?.isFailure == true)
        assertEquals(null, store.createdData)
    }

    @Test
    fun `Item Attachment storage uses nested Household Item and attachment locations`() {
        assertEquals(
            "households/household-1/items/item-1/attachments/attachment-1.webp",
            itemAttachmentStoragePath("household-1", "item-1", "attachment-1"),
        )
        assertEquals(
            "households/household-1/items/item-1/attachments/attachment-1.pdf",
            itemAttachmentStoragePath("household-1", "item-1", "attachment-1", "pdf"),
        )
    }
}

private class FakeItemAttachmentDocumentStore(
    private val documents: List<ItemAttachmentDocument> = emptyList(),
    override val serverTimestamp: Any = Any(),
    private val createdDocument: ItemAttachmentDocument? = null,
) : ItemAttachmentDocumentStore {
    var createdData: Map<String, Any?>? = null
        private set
    var observedItemId: String? = null
        private set

    override fun observeAttachments(
        householdId: String,
        itemId: String,
        onResult: (Result<List<ItemAttachmentDocument>>) -> Unit,
    ): InventorySubscription {
        observedItemId = itemId
        onResult(Result.success(documents.sortedBy { it.data["createdAt"] as Timestamp }))
        return InventorySubscription {}
    }

    override fun newAttachmentId(householdId: String, itemId: String): String = "attachment-1"

    override fun createAttachment(
        householdId: String,
        itemId: String,
        attachmentId: String,
        data: Map<String, Any?>,
        onResult: (Result<ItemAttachmentDocument>) -> Unit,
    ) {
        createdData = data
        onResult(Result.success(requireNotNull(createdDocument)))
    }

    override fun deleteAttachment(
        householdId: String,
        itemId: String,
        attachmentId: String,
        onResult: (Result<Unit>) -> Unit,
    ) = onResult(Result.success(Unit))
}

private fun attachmentDocument(id: String, seconds: Long) = ItemAttachmentDocument(
    id = id,
    data = mapOf(
        "createdAt" to Timestamp(seconds, 0),
        "contentType" to "image/webp",
        "displayUrl" to "gs://mystuff/$id.webp",
    ),
)

private fun attachmentHousehold() = Household(
    id = "household-1",
    ownerMemberId = "member-1",
    rootItem = attachmentItem(
        id = "household-1",
        name = "Our Home",
        parentItemId = null,
    ),
)

private fun attachmentItem(
    id: String = "item-1",
    name: String = "Drill",
    parentItemId: String? = "household-1",
) = Item(
    id = id,
    name = name,
    parentItemId = parentItemId,
    photoUrl = null,
    description = null,
    tags = emptyList(),
)
