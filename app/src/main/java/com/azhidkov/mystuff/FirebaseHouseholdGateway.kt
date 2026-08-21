package com.azhidkov.mystuff

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class FirebaseHouseholdGateway internal constructor(
    private val store: HouseholdDocumentStore,
) : HouseholdGateway {
    constructor() : this(FirestoreHouseholdDocumentStore())

    override fun findForMember(
        memberId: String,
        onResult: (Result<Household?>) -> Unit,
    ) {
        store.findHouseholdIdForMember(memberId) { membershipResult ->
            membershipResult.onSuccess { householdId ->
                if (householdId == null) {
                    onResult(Result.success(null))
                    return@onSuccess
                }
                store.loadHousehold(householdId) { documentsResult ->
                    onResult(documentsResult.mapCatching(HouseholdDocuments::toHousehold))
                }
            }.onFailure { failure ->
                onResult(Result.failure(failure))
            }
        }
    }

    override fun create(
        owner: AuthenticatedIdentity,
        name: String,
        onResult: (Result<Household>) -> Unit,
    ) {
        val householdId = store.newHouseholdId()
        val documents = newHouseholdDocuments(
            householdId = householdId,
            owner = owner,
            name = name,
            serverTimestamp = store.serverTimestamp,
        )
        store.createHousehold(owner.id, documents) { result ->
            onResult(result.map { documents.toHousehold() })
        }
    }
}

internal data class HouseholdDocuments(
    val householdId: String,
    val household: Map<String, Any?>,
    val rootItem: Map<String, Any?>,
) {
    fun toHousehold(): Household {
        val householdName = household.string(NAME)
        val ownerMemberId = household.string(OWNER_MEMBER_ID)
        val rootItemId = household.string(ROOT_ITEM_ID)
        val rootItemName = rootItem.string(NAME)
        if (rootItemId != householdId || householdName != rootItemName) {
            throw HouseholdDataException()
        }

        return Household(
            id = householdId,
            ownerMemberId = ownerMemberId,
            rootItem = Item(
                id = rootItemId,
                name = rootItemName,
                parentItemId = rootItem.nullableString(PARENT_ITEM_ID),
                photoUrl = rootItem.nullableString(PHOTO_URL),
                description = rootItem.nullableString(DESCRIPTION),
                tags = rootItem[TAGS]
                    ?.let { rawTags ->
                        (rawTags as? List<*>)
                            ?.map { it as? String ?: throw HouseholdDataException() }
                    }
                    ?: throw HouseholdDataException(),
                webUrl = rootItem.nullableString(WEB_URL),
            ),
        )
    }
}

internal interface HouseholdDocumentStore {
    val serverTimestamp: Any

    fun newHouseholdId(): String

    fun findHouseholdIdForMember(
        memberId: String,
        onResult: (Result<String?>) -> Unit,
    )

    fun loadHousehold(
        householdId: String,
        onResult: (Result<HouseholdDocuments>) -> Unit,
    )

    fun createHousehold(
        memberId: String,
        documents: HouseholdDocuments,
        onResult: (Result<Unit>) -> Unit,
    )
}

private class FirestoreHouseholdDocumentStore(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : HouseholdDocumentStore {
    override val serverTimestamp: Any
        get() = FieldValue.serverTimestamp()

    override fun newHouseholdId(): String = firestore.collection(HOUSEHOLDS).document().id

    override fun findHouseholdIdForMember(
        memberId: String,
        onResult: (Result<String?>) -> Unit,
    ) {
        firestore.collection(MEMBERSHIPS).document(memberId).get()
            .addOnSuccessListener { membership ->
                onResult(
                    if (!membership.exists()) {
                        Result.success(null)
                    } else {
                        runCatching {
                            membership.getString(HOUSEHOLD_ID) ?: throw HouseholdDataException()
                        }
                    },
                )
            }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    override fun loadHousehold(
        householdId: String,
        onResult: (Result<HouseholdDocuments>) -> Unit,
    ) {
        val householdReference = firestore.collection(HOUSEHOLDS).document(householdId)
        householdReference.get()
            .addOnSuccessListener { householdDocument ->
                val householdData = householdDocument.data
                val rootItemId = householdDocument.getString(ROOT_ITEM_ID)
                if (householdData == null || rootItemId == null) {
                    onResult(Result.failure(HouseholdDataException()))
                    return@addOnSuccessListener
                }
                householdReference.collection(ITEMS).document(rootItemId).get()
                    .addOnSuccessListener { rootItemDocument ->
                        val rootItemData = rootItemDocument.data
                        onResult(
                            if (rootItemData == null) {
                                Result.failure(HouseholdDataException())
                            } else {
                                Result.success(
                                    HouseholdDocuments(
                                        householdId = householdId,
                                        household = householdData,
                                        rootItem = rootItemData,
                                    ),
                                )
                            },
                        )
                    }
                    .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
            }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    override fun createHousehold(
        memberId: String,
        documents: HouseholdDocuments,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val membershipReference = firestore.collection(MEMBERSHIPS).document(memberId)
        val householdReference = firestore.collection(HOUSEHOLDS)
            .document(documents.householdId)
        val rootItemReference = householdReference.collection(ITEMS)
            .document(documents.householdId)

        firestore.runTransaction { transaction ->
            if (transaction.get(membershipReference).exists()) {
                throw ExistingHouseholdException()
            }
            transaction.set(
                membershipReference,
                mapOf(
                    HOUSEHOLD_ID to documents.householdId,
                    ROLE to OWNER,
                ),
            )
            transaction.set(householdReference, documents.household)
            transaction.set(rootItemReference, documents.rootItem)
        }.addOnSuccessListener {
            onResult(Result.success(Unit))
        }.addOnFailureListener { failure ->
            onResult(Result.failure(failure))
        }
    }
}

private fun newHouseholdDocuments(
    householdId: String,
    owner: AuthenticatedIdentity,
    name: String,
    serverTimestamp: Any,
): HouseholdDocuments {
    val ownerDisplayName = owner.displayName?.takeIf(String::isNotBlank)
        ?: owner.email?.takeIf(String::isNotBlank)
        ?: "Household Member"
    return HouseholdDocuments(
        householdId = householdId,
        household = mapOf(
            NAME to name,
            OWNER_MEMBER_ID to owner.id,
            ROOT_ITEM_ID to householdId,
            CREATED_AT to serverTimestamp,
        ),
        rootItem = mapOf(
            HOUSEHOLD_ID to householdId,
            NAME to name,
            PARENT_ITEM_ID to null,
            PHOTO_URL to null,
            DESCRIPTION to null,
            TAGS to emptyList<String>(),
            WEB_URL to null,
            CREATED_AT to serverTimestamp,
            UPDATED_AT to serverTimestamp,
            CREATED_BY_ID to owner.id,
            CREATED_BY_DISPLAY_NAME to ownerDisplayName,
            UPDATED_BY_ID to owner.id,
            UPDATED_BY_DISPLAY_NAME to ownerDisplayName,
        ),
    )
}

private fun Map<String, Any?>.string(key: String): String =
    this[key] as? String ?: throw HouseholdDataException()

private fun Map<String, Any?>.nullableString(key: String): String? {
    val value = this[key]
    if (value != null && value !is String) throw HouseholdDataException()
    return value
}

private const val MEMBERSHIPS = "memberships"
private const val HOUSEHOLDS = "households"
private const val ITEMS = "items"
private const val HOUSEHOLD_ID = "householdId"
private const val ROLE = "role"
private const val OWNER = "owner"
private const val NAME = "name"
private const val OWNER_MEMBER_ID = "ownerMemberId"
private const val ROOT_ITEM_ID = "rootItemId"
private const val PARENT_ITEM_ID = "parentItemId"
private const val PHOTO_URL = "photoUrl"
private const val DESCRIPTION = "description"
private const val TAGS = "tags"
private const val WEB_URL = "webUrl"
private const val CREATED_AT = "createdAt"
private const val UPDATED_AT = "updatedAt"
private const val CREATED_BY_ID = "createdById"
private const val CREATED_BY_DISPLAY_NAME = "createdByDisplayName"
private const val UPDATED_BY_ID = "updatedById"
private const val UPDATED_BY_DISPLAY_NAME = "updatedByDisplayName"

private class ExistingHouseholdException : IllegalStateException(
    "You already belong to a Household.",
)

private class HouseholdDataException : IllegalStateException(
    "Your Household data is incomplete. Please try again.",
)
