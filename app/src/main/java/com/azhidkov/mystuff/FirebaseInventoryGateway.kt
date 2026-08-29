package com.azhidkov.mystuff

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage
import java.time.Instant
import java.util.UUID

class FirebaseInventoryGateway internal constructor(
    private val store: InventoryDocumentStore,
    private val photoStore: InventoryPhotoStore,
    private val attachmentGateway: ItemAttachmentGateway?,
) : InventoryGateway {
    internal constructor(
        store: InventoryDocumentStore,
        photoStore: InventoryPhotoStore,
    ) : this(store, photoStore, null)

    constructor() : this(
        store = FirestoreInventoryDocumentStore(),
        photoStore = firebaseInventoryPhotoStore(),
        attachmentGateway = FirebaseItemAttachmentGateway(),
    )

    override fun observe(
        household: Household,
        onResult: (Result<Inventory>) -> Unit,
    ): InventorySubscription = store.observeItems(household.id) { result ->
        onResult(
            result.mapCatching { documents ->
                Inventory.from(
                    household = household,
                    items = documents.map { it.toItem(household.id) },
                )
            },
        )
    }

    override fun newItemId(householdId: String): String = store.newItemId(householdId)

    override fun createItem(
        householdId: String,
        parentItemId: String,
        creator: AuthenticatedIdentity,
        details: ItemDetails,
        photo: ItemPhoto?,
        onResult: (Result<Item>) -> Unit,
    ) {
        details.validationFailure()?.let { error ->
            onResult(Result.failure(IllegalArgumentException(error)))
            return
        }
        val itemId = newItemId(householdId)
        val photoPlan = photo?.let { newPhotoPlan(householdId, itemId) }
        createItemDocument(
            householdId = householdId,
            parentItemId = parentItemId,
            creator = creator,
            itemId = itemId,
            details = details,
            photoAttachmentId = photoPlan?.attachmentId?.takeIf { attachmentGateway == null },
            photoLocations = photoPlan?.revision?.locations?.takeIf { attachmentGateway == null },
        ) { result ->
            val item = result.getOrNull()
            if (item == null || photo == null || photoPlan == null || attachmentGateway == null) {
                uploadPhotoAndComplete(result, photoPlan, photo, onResult)
                return@createItemDocument
            }
            createPhotoAttachment(householdId, item, photoPlan) { attachmentResult ->
                attachmentResult
                    .onSuccess {
                        projectCreatedPhoto(
                            householdId = householdId,
                            item = item,
                            photoPlan = photoPlan,
                            updater = creator,
                            photo = photo,
                            onResult = onResult,
                        )
                    }
                    .onFailure { onResult(Result.failure(it)) }
            }
        }
    }

    override fun updateItem(
        householdId: String,
        item: Item,
        updater: AuthenticatedIdentity,
        details: ItemDetails,
        photoUpdate: ItemPhotoUpdate,
        onResult: (Result<Item>) -> Unit,
    ) {
        details.validationFailure()?.let { error ->
            onResult(Result.failure(IllegalArgumentException(error)))
            return
        }
        val photoPlan = when (photoUpdate) {
            ItemPhotoUpdate.Unchanged -> ItemPhotoUpdatePlan(
                attachmentId = item.photoAttachmentId,
                full = item.photoUrl,
                thumbnail = item.photoThumbnailUrl,
                revision = null,
            )
            ItemPhotoUpdate.Removed -> ItemPhotoUpdatePlan(
                attachmentId = null,
                full = null,
                thumbnail = null,
                revision = null,
            )
            is ItemPhotoUpdate.Replaced -> newPhotoPlan(householdId, item.id).let {
                ItemPhotoUpdatePlan(
                    attachmentId = it.attachmentId,
                    full = requireNotNull(it.revision).locations.full,
                    thumbnail = requireNotNull(it.revision).locations.thumbnail,
                    revision = it.revision,
                )
            }
        }
        val updated = item.copy(
            name = details.name,
            description = details.description,
            tags = details.tags,
            webUrl = details.webUrl,
            photoAttachmentId = photoPlan.attachmentId,
            photoUrl = photoPlan.full,
            photoThumbnailUrl = photoPlan.thumbnail,
        )
        val data = mapOf(
            NAME to updated.name,
            PHOTO_ATTACHMENT_ID to updated.photoAttachmentId,
            PHOTO_URL to updated.photoUrl,
            PHOTO_THUMBNAIL_URL to updated.photoThumbnailUrl,
            DESCRIPTION to updated.description,
            TAGS to updated.tags,
            WEB_URL to updated.webUrl,
            UPDATED_AT to store.serverTimestamp,
            UPDATED_BY_ID to updater.id,
            UPDATED_BY_DISPLAY_NAME to updater.attributionDisplayName(),
        )
        val persistUpdate = {
            store.updateItem(householdId, item.id, data) { result ->
                result.onFailure { failure ->
                    if (photoUpdate is ItemPhotoUpdate.Replaced) {
                        discardPhotoAttachment(householdId, item, photoPlan) {
                            onResult(Result.failure(failure))
                        }
                    } else {
                        onResult(Result.failure(failure))
                    }
                }
                .onSuccess {
                    finishPhotoUpdate(
                        householdId = householdId,
                        item = item,
                        updated = updated,
                        photoUpdate = photoUpdate,
                        photoPlan = photoPlan,
                        onResult = onResult,
                    )
                }
            }
        }
        if (
            photoUpdate is ItemPhotoUpdate.Replaced &&
            attachmentGateway != null &&
            photoPlan.attachmentId != null
        ) {
            createPhotoAttachment(householdId, item, photoPlan) { result ->
                result.onSuccess { persistUpdate() }
                    .onFailure { onResult(Result.failure(it)) }
            }
        } else {
            persistUpdate()
        }
    }

    private fun newPhotoPlan(householdId: String, itemId: String): ItemPhotoUpdatePlan {
        val attachmentId = attachmentGateway?.newAttachmentId(householdId, itemId)
        val revision = if (attachmentId == null) {
            photoStore.newRevision(householdId, itemId)
        } else {
            photoStore.newAttachmentRevision(householdId, itemId, attachmentId)
        }
        return ItemPhotoUpdatePlan(
            attachmentId = attachmentId,
            full = revision.locations.full,
            thumbnail = revision.locations.thumbnail,
            revision = revision,
        )
    }

    private fun createPhotoAttachment(
        householdId: String,
        item: Item,
        photoPlan: ItemPhotoUpdatePlan,
        onResult: (Result<ItemAttachment>) -> Unit,
    ) {
        requireNotNull(attachmentGateway).create(
            household = householdFor(householdId),
            item = item,
            attachmentId = requireNotNull(photoPlan.attachmentId),
            contentType = OPTIMIZED_ATTACHMENT_IMAGE_CONTENT_TYPE,
            displayUrl = requireNotNull(photoPlan.revision).locations.full,
            onResult = onResult,
        )
    }

    private fun projectCreatedPhoto(
        householdId: String,
        item: Item,
        photoPlan: ItemPhotoUpdatePlan,
        updater: AuthenticatedIdentity,
        photo: ItemPhoto,
        onResult: (Result<Item>) -> Unit,
    ) {
        val projected = item.copy(
            photoAttachmentId = photoPlan.attachmentId,
            photoUrl = photoPlan.full,
            photoThumbnailUrl = photoPlan.thumbnail,
        )
        store.updateItem(
            householdId = householdId,
            itemId = item.id,
            data = mapOf(
                PHOTO_ATTACHMENT_ID to projected.photoAttachmentId,
                PHOTO_URL to projected.photoUrl,
                PHOTO_THUMBNAIL_URL to projected.photoThumbnailUrl,
                UPDATED_AT to store.serverTimestamp,
                UPDATED_BY_ID to updater.id,
                UPDATED_BY_DISPLAY_NAME to updater.attributionDisplayName(),
            ),
        ) { result ->
            result.onSuccess {
                uploadPhotoAndComplete(Result.success(projected), photoPlan, photo, onResult)
            }.onFailure { failure ->
                discardPhotoAttachment(householdId, item, photoPlan) {
                    onResult(Result.failure(failure))
                }
            }
        }
    }

    private fun discardPhotoAttachment(
        householdId: String,
        item: Item,
        photoPlan: ItemPhotoUpdatePlan,
        onComplete: () -> Unit,
    ) {
        val gateway = attachmentGateway
        val attachmentId = photoPlan.attachmentId
        if (gateway == null || attachmentId == null) {
            onComplete()
            return
        }
        gateway.delete(
            household = householdFor(householdId),
            item = item,
            attachment = projectedAttachment(item, attachmentId),
        ) { onComplete() }
    }

    private fun uploadPhotoAndComplete(
        result: Result<Item>,
        photoPlan: ItemPhotoUpdatePlan?,
        photo: ItemPhoto?,
        onResult: (Result<Item>) -> Unit,
    ) {
        try {
            if (result.isSuccess && photo != null && photoPlan?.revision != null) {
                photoStore.uploadInBackground(requireNotNull(photoPlan.revision), photo)
            }
        } catch (_: RuntimeException) {
            // The Item exists and remains usable with its photo placeholder.
        } finally {
            onResult(result)
        }
    }

    private fun finishPhotoUpdate(
        householdId: String,
        item: Item,
        updated: Item,
        photoUpdate: ItemPhotoUpdate,
        photoPlan: ItemPhotoUpdatePlan,
        onResult: (Result<Item>) -> Unit,
    ) {
        try {
            if (photoUpdate is ItemPhotoUpdate.Replaced && photoPlan.revision != null) {
                photoStore.uploadInBackground(photoPlan.revision, photoUpdate.photo)
            }
        } catch (_: RuntimeException) {
            // The Item remains usable with its photo placeholder.
        }

        val oldAttachmentId = item.photoAttachmentId
        val shouldDeleteOldAttachment =
            attachmentGateway != null &&
                oldAttachmentId != null &&
                photoUpdate !is ItemPhotoUpdate.Unchanged
        if (!shouldDeleteOldAttachment) {
            if (photoUpdate is ItemPhotoUpdate.Removed) {
                deleteStoredPhoto(item)
            }
            onResult(Result.success(updated))
            return
        }

        attachmentGateway.delete(
            household = householdFor(householdId),
            item = item,
            attachment = projectedAttachment(item, requireNotNull(oldAttachmentId)),
        ) { result ->
            result.onSuccess {
                if (photoUpdate is ItemPhotoUpdate.Removed) deleteStoredPhoto(item)
                onResult(Result.success(updated))
            }.onFailure { onResult(Result.failure(it)) }
        }
    }

    private fun deleteStoredPhoto(item: Item) {
        photoStore.deleteInBackground(
            StoredItemPhotoLocations(
                full = item.photoUrl,
                thumbnail = item.photoThumbnailUrl,
            ),
        )
    }

    private fun householdFor(householdId: String): Household = Household(
        id = householdId,
        ownerMemberId = "",
        rootItem = Item(
            id = householdId,
            name = "",
            parentItemId = null,
            photoUrl = null,
            description = null,
            tags = emptyList(),
        ),
    )

    private fun projectedAttachment(item: Item, attachmentId: String) = ItemAttachment(
        id = attachmentId,
        itemId = item.id,
        createdAt = Instant.EPOCH,
        contentType = OPTIMIZED_ATTACHMENT_IMAGE_CONTENT_TYPE,
        displayUrl = item.photoUrl.orEmpty(),
    )

    private fun createItemDocument(
        householdId: String,
        parentItemId: String,
        creator: AuthenticatedIdentity,
        itemId: String,
        details: ItemDetails,
        photoAttachmentId: String?,
        photoLocations: ItemPhotoLocations?,
        onResult: (Result<Item>) -> Unit,
    ) {
        val item = Item(
            id = itemId,
            name = details.name,
            parentItemId = parentItemId,
            photoAttachmentId = photoAttachmentId,
            photoUrl = photoLocations?.full,
            description = details.description,
            tags = details.tags,
            photoThumbnailUrl = photoLocations?.thumbnail,
            webUrl = details.webUrl,
        )
        val displayName = creator.attributionDisplayName()
        val data = mapOf(
            HOUSEHOLD_ID to householdId,
            NAME to item.name,
            PARENT_ITEM_ID to parentItemId,
            PHOTO_ATTACHMENT_ID to photoAttachmentId,
            PHOTO_URL to photoLocations?.full,
            PHOTO_THUMBNAIL_URL to photoLocations?.thumbnail,
            DESCRIPTION to item.description,
            TAGS to item.tags,
            WEB_URL to item.webUrl,
            CREATED_AT to store.serverTimestamp,
            UPDATED_AT to store.serverTimestamp,
            CREATED_BY_ID to creator.id,
            CREATED_BY_DISPLAY_NAME to displayName,
            UPDATED_BY_ID to creator.id,
            UPDATED_BY_DISPLAY_NAME to displayName,
        )
        store.createItem(householdId, itemId, data) { result ->
            onResult(result.map { item })
        }
    }
}

private data class ItemPhotoUpdatePlan(
    val attachmentId: String?,
    val full: String?,
    val thumbnail: String?,
    val revision: ItemPhotoRevision?,
)

internal interface InventoryPhotoStore {
    fun newRevision(householdId: String, itemId: String): ItemPhotoRevision

    fun newAttachmentId(householdId: String, itemId: String): String = UUID.randomUUID().toString()

    fun newAttachmentRevision(
        householdId: String,
        itemId: String,
        attachmentId: String,
    ): ItemPhotoRevision = newRevision(householdId, itemId)

    fun uploadInBackground(
        revision: ItemPhotoRevision,
        photo: ItemPhoto,
    )

    fun uploadThumbnailInBackground(
        revision: ItemPhotoRevision,
        photo: ItemPhoto,
    )

    fun deleteInBackground(locations: StoredItemPhotoLocations)
}

internal fun firebaseInventoryPhotoStore(): InventoryPhotoStore {
    val storage = FirebaseStorage.getInstance()
    return BackgroundInventoryPhotoStore(
        bucketUrl = storage.reference.toString(),
        queue = WorkManagerPhotoTransferQueue(FirebaseApp.getInstance().applicationContext),
    )
}

internal fun photoStoragePath(
    householdId: String,
    itemId: String,
    revisionId: UUID,
    variant: ItemPhotoVariant,
): String = "households/$householdId/items/$itemId-$revisionId" +
    variant.fileSuffix

internal data class ItemPhotoRevision(
    val locations: ItemPhotoLocations,
    val fullStoragePath: String,
    val thumbnailStoragePath: String,
)

internal data class StoredItemPhotoLocations(
    val full: String?,
    val thumbnail: String?,
) {
    fun presentLocations(): List<String> = listOfNotNull(full, thumbnail)
}

internal data class InventoryItemDocument(
    val id: String,
    val data: Map<String, Any?>,
) {
    fun toItem(expectedHouseholdId: String): Item {
        if (data.inventoryString(HOUSEHOLD_ID) != expectedHouseholdId) {
            throw InvalidInventoryException()
        }
        return Item(
            id = id,
            name = data.inventoryString(NAME),
            parentItemId = data.inventoryNullableString(PARENT_ITEM_ID),
            photoAttachmentId = data.inventoryNullableString(PHOTO_ATTACHMENT_ID),
            photoUrl = data.inventoryNullableString(PHOTO_URL),
            description = data.inventoryNullableString(DESCRIPTION),
            tags = data[TAGS]
                ?.let { rawTags ->
                    (rawTags as? List<*>)
                        ?.map { it as? String ?: throw InvalidInventoryException() }
                }
                ?: throw InvalidInventoryException(),
            photoThumbnailUrl = data.inventoryNullableString(PHOTO_THUMBNAIL_URL),
            webUrl = data.inventoryNullableString(WEB_URL),
        )
    }
}

internal interface InventoryDocumentStore {
    val serverTimestamp: Any

    fun observeItems(
        householdId: String,
        onResult: (Result<List<InventoryItemDocument>>) -> Unit,
    ): InventorySubscription

    fun newItemId(householdId: String): String

    fun createItem(
        householdId: String,
        itemId: String,
        data: Map<String, Any?>,
        onResult: (Result<Unit>) -> Unit,
    )

    fun updateItem(
        householdId: String,
        itemId: String,
        data: Map<String, Any?>,
        onResult: (Result<Unit>) -> Unit,
    )
}

private class FirestoreInventoryDocumentStore(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : InventoryDocumentStore {
    override val serverTimestamp: Any
        get() = FieldValue.serverTimestamp()

    override fun observeItems(
        householdId: String,
        onResult: (Result<List<InventoryItemDocument>>) -> Unit,
    ): InventorySubscription {
        val registration = items(householdId).addSnapshotListener { snapshot, failure ->
            if (failure != null) {
                onResult(Result.failure(failure))
            } else {
                onResult(
                    runCatching {
                        requireNotNull(snapshot).documents.map { document ->
                            InventoryItemDocument(document.id, document.data ?: emptyMap())
                        }
                    },
                )
            }
        }
        return InventorySubscription(registration::remove)
    }

    override fun newItemId(householdId: String): String = items(householdId).document().id

    override fun createItem(
        householdId: String,
        itemId: String,
        data: Map<String, Any?>,
        onResult: (Result<Unit>) -> Unit,
    ) {
        items(householdId).document(itemId).set(data)
            .addOnSuccessListener { onResult(Result.success(Unit)) }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    override fun updateItem(
        householdId: String,
        itemId: String,
        data: Map<String, Any?>,
        onResult: (Result<Unit>) -> Unit,
    ) {
        items(householdId).document(itemId).update(data)
            .addOnSuccessListener { onResult(Result.success(Unit)) }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    private fun items(householdId: String) = firestore
        .collection(HOUSEHOLDS)
        .document(householdId)
        .collection(ITEMS)
}

private fun Map<String, Any?>.inventoryString(key: String): String =
    this[key] as? String ?: throw InvalidInventoryException()

private fun Map<String, Any?>.inventoryNullableString(key: String): String? {
    val value = this[key]
    if (value != null && value !is String) throw InvalidInventoryException()
    return value
}

internal fun AuthenticatedIdentity.attributionDisplayName(): String =
    displayName?.takeIf(String::isNotBlank)
        ?: email?.takeIf(String::isNotBlank)
        ?: "Household Member"

private const val HOUSEHOLDS = "households"
private const val ITEMS = "items"
private const val HOUSEHOLD_ID = "householdId"
private const val NAME = "name"
private const val PARENT_ITEM_ID = "parentItemId"
private const val PHOTO_ATTACHMENT_ID = "photoAttachmentId"
private const val PHOTO_URL = "photoUrl"
private const val PHOTO_THUMBNAIL_URL = "photoThumbnailUrl"
private const val DESCRIPTION = "description"
private const val TAGS = "tags"
private const val WEB_URL = "webUrl"
private const val CREATED_AT = "createdAt"
private const val UPDATED_AT = "updatedAt"
private const val CREATED_BY_ID = "createdById"
private const val CREATED_BY_DISPLAY_NAME = "createdByDisplayName"
private const val UPDATED_BY_ID = "updatedById"
private const val UPDATED_BY_DISPLAY_NAME = "updatedByDisplayName"
