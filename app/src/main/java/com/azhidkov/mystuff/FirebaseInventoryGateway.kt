package com.azhidkov.mystuff

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage
import java.util.UUID

class FirebaseInventoryGateway internal constructor(
    private val store: InventoryDocumentStore,
    private val photoStore: InventoryPhotoStore,
) : InventoryGateway {
    constructor() : this(
        store = FirestoreInventoryDocumentStore(),
        photoStore = firebaseInventoryPhotoStore(),
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
        val itemId = store.newItemId(householdId)
        val photoRevision = photo?.let { photoStore.newRevision(householdId, itemId) }
        createItemDocument(
            householdId = householdId,
            parentItemId = parentItemId,
            creator = creator,
            itemId = itemId,
            details = details,
            photoLocations = photoRevision?.locations,
        ) { result ->
            try {
                result.onSuccess {
                    if (photo != null && photoRevision != null) {
                        photoStore.uploadInBackground(photoRevision, photo)
                    }
                }
            } catch (_: RuntimeException) {
                // The Item exists and remains usable with its photo placeholder.
            } finally {
                onResult(result)
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
                full = item.photoUrl,
                thumbnail = item.photoThumbnailUrl,
                afterDocumentUpdate = {},
            )
            ItemPhotoUpdate.Removed -> ItemPhotoUpdatePlan(
                full = null,
                thumbnail = null,
                afterDocumentUpdate = {
                    photoStore.deleteInBackground(
                        StoredItemPhotoLocations(
                            full = item.photoUrl,
                            thumbnail = item.photoThumbnailUrl,
                        ),
                    )
                },
            )
            is ItemPhotoUpdate.Replaced -> photoStore.newRevision(householdId, item.id).let {
                ItemPhotoUpdatePlan(
                    full = it.locations.full,
                    thumbnail = it.locations.thumbnail,
                    afterDocumentUpdate = {
                        photoStore.uploadInBackground(it, photoUpdate.photo)
                    },
                )
            }
        }
        val updated = item.copy(
            name = details.name,
            description = details.description,
            tags = details.tags,
            webUrl = details.webUrl,
            photoUrl = photoPlan.full,
            photoThumbnailUrl = photoPlan.thumbnail,
        )
        val data = mapOf(
            NAME to updated.name,
            PHOTO_URL to updated.photoUrl,
            PHOTO_THUMBNAIL_URL to updated.photoThumbnailUrl,
            DESCRIPTION to updated.description,
            TAGS to updated.tags,
            WEB_URL to updated.webUrl,
            UPDATED_AT to store.serverTimestamp,
            UPDATED_BY_ID to updater.id,
            UPDATED_BY_DISPLAY_NAME to updater.attributionDisplayName(),
        )
        store.updateItem(householdId, item.id, data) { result ->
            val completed = result.mapCatching {
                photoPlan.afterDocumentUpdate()
                updated
            }
            onResult(completed)
        }
    }

    private fun createItemDocument(
        householdId: String,
        parentItemId: String,
        creator: AuthenticatedIdentity,
        itemId: String,
        details: ItemDetails,
        photoLocations: ItemPhotoLocations?,
        onResult: (Result<Item>) -> Unit,
    ) {
        val item = Item(
            id = itemId,
            name = details.name,
            parentItemId = parentItemId,
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
    val full: String?,
    val thumbnail: String?,
    val afterDocumentUpdate: () -> Unit,
)

internal interface InventoryPhotoStore {
    fun newRevision(householdId: String, itemId: String): ItemPhotoRevision

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
