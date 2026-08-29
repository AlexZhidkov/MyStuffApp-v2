package com.azhidkov.mystuff

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import java.time.Instant

interface ItemAttachmentGateway {
    fun observe(
        household: Household,
        item: Item,
        onResult: (Result<List<ItemAttachment>>) -> Unit,
    ): InventorySubscription

    fun newAttachmentId(
        householdId: String,
        itemId: String,
    ): String

    fun create(
        household: Household,
        item: Item,
        attachmentId: String,
        contentType: String,
        displayUrl: String,
        onResult: (Result<ItemAttachment>) -> Unit,
    )

    fun delete(
        household: Household,
        item: Item,
        attachment: ItemAttachment,
        onResult: (Result<Unit>) -> Unit,
    )
}

class FirebaseItemAttachmentGateway internal constructor(
    private val store: ItemAttachmentDocumentStore,
) : ItemAttachmentGateway {
    constructor() : this(FirestoreItemAttachmentDocumentStore())

    override fun observe(
        household: Household,
        item: Item,
        onResult: (Result<List<ItemAttachment>>) -> Unit,
    ): InventorySubscription {
        if (item.isRootOf(household)) {
            onResult(Result.failure(invalidAttachmentOwner()))
            return InventorySubscription {}
        }
        return store.observeAttachments(household.id, item.id) { result ->
            onResult(
                result.mapCatching { documents ->
                    documents.map { it.toItemAttachment(item.id) }
                },
            )
        }
    }

    override fun newAttachmentId(householdId: String, itemId: String): String =
        store.newAttachmentId(householdId, itemId)

    override fun create(
        household: Household,
        item: Item,
        attachmentId: String,
        contentType: String,
        displayUrl: String,
        onResult: (Result<ItemAttachment>) -> Unit,
    ) {
        if (item.isRootOf(household)) {
            onResult(Result.failure(invalidAttachmentOwner()))
            return
        }
        if (attachmentId.isBlank() || contentType.isBlank() || displayUrl.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Item Attachment metadata is incomplete.")))
            return
        }
        store.createAttachment(
            householdId = household.id,
            itemId = item.id,
            attachmentId = attachmentId,
            data = mapOf(
                CREATED_AT to store.serverTimestamp,
                CONTENT_TYPE to contentType,
                DISPLAY_URL to displayUrl,
            ),
            onResult = { result ->
                onResult(result.mapCatching { it.toItemAttachment(item.id) })
            },
        )
    }

    override fun delete(
        household: Household,
        item: Item,
        attachment: ItemAttachment,
        onResult: (Result<Unit>) -> Unit,
    ) {
        if (item.isRootOf(household) || attachment.itemId != item.id) {
            onResult(Result.failure(invalidAttachmentOwner()))
            return
        }
        store.deleteAttachment(household.id, item.id, attachment.id, onResult)
    }
}

internal data class ItemAttachmentDocument(
    val id: String,
    val data: Map<String, Any?>,
) {
    fun toItemAttachment(expectedItemId: String): ItemAttachment {
        return ItemAttachment(
            id = id,
            itemId = expectedItemId,
            createdAt = data.requiredTimestamp(CREATED_AT).toInstant(),
            contentType = data.requiredString(CONTENT_TYPE),
            displayUrl = data.requiredString(DISPLAY_URL),
        )
    }
}

internal interface ItemAttachmentDocumentStore {
    val serverTimestamp: Any

    fun observeAttachments(
        householdId: String,
        itemId: String,
        onResult: (Result<List<ItemAttachmentDocument>>) -> Unit,
    ): InventorySubscription

    fun newAttachmentId(
        householdId: String,
        itemId: String,
    ): String

    fun createAttachment(
        householdId: String,
        itemId: String,
        attachmentId: String,
        data: Map<String, Any?>,
        onResult: (Result<ItemAttachmentDocument>) -> Unit,
    )

    fun deleteAttachment(
        householdId: String,
        itemId: String,
        attachmentId: String,
        onResult: (Result<Unit>) -> Unit,
    )
}

private class FirestoreItemAttachmentDocumentStore(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : ItemAttachmentDocumentStore {
    override val serverTimestamp: Any
        get() = FieldValue.serverTimestamp()

    override fun observeAttachments(
        householdId: String,
        itemId: String,
        onResult: (Result<List<ItemAttachmentDocument>>) -> Unit,
    ): InventorySubscription {
        val registration = attachments(householdId, itemId)
            .orderBy(CREATED_AT)
            .addSnapshotListener { snapshot, failure ->
                if (failure != null) {
                    onResult(Result.failure(failure))
                } else {
                    onResult(
                        runCatching {
                            requireNotNull(snapshot).documents.map { document ->
                                ItemAttachmentDocument(
                                    document.id,
                                    document.getData(ServerTimestampBehavior.ESTIMATE)
                                        ?: emptyMap(),
                                )
                            }
                        },
                    )
                }
            }
        return InventorySubscription(registration::remove)
    }

    override fun newAttachmentId(householdId: String, itemId: String): String =
        attachments(householdId, itemId).document().id

    override fun createAttachment(
        householdId: String,
        itemId: String,
        attachmentId: String,
        data: Map<String, Any?>,
        onResult: (Result<ItemAttachmentDocument>) -> Unit,
    ) {
        val reference = attachments(householdId, itemId).document(attachmentId)
        reference.set(data)
            .addOnSuccessListener {
                reference.get()
                    .addOnSuccessListener { document ->
                        onResult(
                            runCatching {
                                ItemAttachmentDocument(
                                    id = document.id,
                                    data = document.data ?: throw InvalidItemAttachmentException(),
                                )
                            },
                        )
                    }
                    .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
            }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    override fun deleteAttachment(
        householdId: String,
        itemId: String,
        attachmentId: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        attachments(householdId, itemId).document(attachmentId).delete()
            .addOnSuccessListener { onResult(Result.success(Unit)) }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    private fun attachments(householdId: String, itemId: String) = firestore
        .collection(HOUSEHOLDS)
        .document(householdId)
        .collection(ITEMS)
        .document(itemId)
        .collection(ATTACHMENTS)
}

private fun Item.isRootOf(household: Household): Boolean =
    id == household.rootItem.id || parentItemId == null

private fun invalidAttachmentOwner() = IllegalArgumentException(
    "The Household cannot own Item Attachments.",
)

private fun Map<String, Any?>.requiredString(key: String): String =
    this[key] as? String ?: throw InvalidItemAttachmentException()

private fun Map<String, Any?>.requiredTimestamp(key: String): Timestamp =
    this[key] as? Timestamp ?: throw InvalidItemAttachmentException()

private class InvalidItemAttachmentException : IllegalStateException(
    "Item Attachment data is incomplete.",
)

private const val HOUSEHOLDS = "households"
private const val ITEMS = "items"
private const val ATTACHMENTS = "attachments"
private const val CREATED_AT = "createdAt"
private const val CONTENT_TYPE = "contentType"
private const val DISPLAY_URL = "displayUrl"
