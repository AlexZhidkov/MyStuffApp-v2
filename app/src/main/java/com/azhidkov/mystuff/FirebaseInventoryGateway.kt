package com.azhidkov.mystuff

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage

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
        name: String,
        photo: ItemPhoto?,
        onResult: (Result<Item>) -> Unit,
    ) {
        val itemId = store.newItemId(householdId)
        val locations = photo?.let { photoStore.locations(householdId, itemId) }
        createItemDocument(
            householdId = householdId,
            parentItemId = parentItemId,
            creator = creator,
            itemId = itemId,
            name = name,
            photoLocations = locations,
        ) { result ->
            try {
                result.onSuccess {
                    if (photo != null) {
                        photoStore.uploadInBackground(householdId, itemId, photo)
                    }
                }
            } catch (_: RuntimeException) {
                // The Item exists and remains usable with its photo placeholder.
            } finally {
                onResult(result)
            }
        }
    }

    private fun createItemDocument(
        householdId: String,
        parentItemId: String,
        creator: AuthenticatedIdentity,
        itemId: String,
        name: String,
        photoLocations: ItemPhotoLocations?,
        onResult: (Result<Item>) -> Unit,
    ) {
        val item = Item(
            id = itemId,
            name = name,
            parentItemId = parentItemId,
            photoUrl = photoLocations?.full,
            description = null,
            tags = emptyList(),
            photoThumbnailUrl = photoLocations?.thumbnail,
        )
        val displayName = creator.displayName?.takeIf(String::isNotBlank)
            ?: creator.email?.takeIf(String::isNotBlank)
            ?: "Household Member"
        val data = mapOf(
            HOUSEHOLD_ID to householdId,
            NAME to item.name,
            PARENT_ITEM_ID to parentItemId,
            PHOTO_URL to photoLocations?.full,
            PHOTO_THUMBNAIL_URL to photoLocations?.thumbnail,
            DESCRIPTION to null,
            TAGS to emptyList<String>(),
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

internal interface InventoryPhotoStore {
    fun locations(householdId: String, itemId: String): ItemPhotoLocations

    fun uploadInBackground(
        householdId: String,
        itemId: String,
        photo: ItemPhoto,
    )

    fun deleteInBackground(householdId: String, itemIds: Collection<String>)
}

private fun firebaseInventoryPhotoStore(): InventoryPhotoStore {
    val storage = FirebaseStorage.getInstance()
    return BackgroundInventoryPhotoStore(
        bucketUrl = storage.reference.toString(),
        queue = WorkManagerPhotoTransferQueue(FirebaseApp.getInstance().applicationContext),
    )
}

internal fun photoStoragePath(
    householdId: String,
    itemId: String,
    variant: ItemPhotoVariant,
): String = "households/$householdId/items/$itemId" +
    variant.fileSuffix

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

private const val HOUSEHOLDS = "households"
private const val ITEMS = "items"
private const val HOUSEHOLD_ID = "householdId"
private const val NAME = "name"
private const val PARENT_ITEM_ID = "parentItemId"
private const val PHOTO_URL = "photoUrl"
private const val PHOTO_THUMBNAIL_URL = "photoThumbnailUrl"
private const val DESCRIPTION = "description"
private const val TAGS = "tags"
private const val CREATED_AT = "createdAt"
private const val UPDATED_AT = "updatedAt"
private const val CREATED_BY_ID = "createdById"
private const val CREATED_BY_DISPLAY_NAME = "createdByDisplayName"
private const val UPDATED_BY_ID = "updatedById"
private const val UPDATED_BY_DISPLAY_NAME = "updatedByDisplayName"
