package com.azhidkov.mystuff

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

class FirebaseHouseholdGateway internal constructor(
    private val store: HouseholdDocumentStore,
    private val accessClaimGateway: HouseholdAccessClaimGateway =
        NoHouseholdAccessClaimGateway,
) : HouseholdGateway {
    constructor(accessClaimGateway: HouseholdAccessClaimGateway) : this(
        store = FirestoreHouseholdDocumentStore(),
        accessClaimGateway = accessClaimGateway,
    )

    constructor() : this(
        store = FirestoreHouseholdDocumentStore(),
        accessClaimGateway = FirebaseHouseholdAccessGateway(),
    )

    override fun findForMember(
        identity: AuthenticatedIdentity,
        onResult: (Result<Household?>) -> Unit,
    ) {
        accessClaimGateway.claim(identity) { claimResult ->
            claimResult.onSuccess { loadMembership(identity.id, onResult) }
                .onFailure { failure -> onResult(Result.failure(failure)) }
        }
    }

    // Kept for the document-store seam tests; production always supplies the signed-in identity.
    internal fun findForMember(
        memberId: String,
        onResult: (Result<Household?>) -> Unit,
    ) = loadMembership(memberId, onResult)

    private fun loadMembership(
        memberId: String,
        onResult: (Result<Household?>) -> Unit,
    ) {
        var cachedHousehold: Household? = null
        // A cached membership may have been revoked, so it is only reused after the server
        // confirms the same summary. Both requests start immediately to avoid delaying startup.
        store.loadBootstrap(memberId, HouseholdDocumentSource.Cache) { result ->
            cachedHousehold = result.getOrNull()?.let { bootstrap ->
                runCatching(bootstrap::toHousehold).getOrNull()
            }
        }
        store.loadBootstrap(memberId, HouseholdDocumentSource.Server) { result ->
            result.onSuccess { bootstrap ->
                if (bootstrap == null) {
                    onResult(Result.success(null))
                } else if (bootstrap.isLegacy()) {
                    reopenLegacyMembership(bootstrap, onResult)
                } else {
                    onResult(
                        runCatching(bootstrap::toHousehold).map { authoritative ->
                            cachedHousehold?.takeIf { it == authoritative } ?: authoritative
                        },
                    )
                }
            }.onFailure { failure ->
                onResult(Result.failure(failure))
            }
        }
    }

    private fun reopenLegacyMembership(
        legacy: HouseholdBootstrapDocument,
        onResult: (Result<Household?>) -> Unit,
    ) {
        val householdId = runCatching { legacy.data.string(HOUSEHOLD_ID) }
            .getOrElse {
                onResult(Result.failure(it))
                return
            }
        store.loadHouseholdSummary(householdId) { result ->
            val upgradedResult = result.mapCatching(legacy::withHouseholdSummary)
            upgradedResult.onSuccess { upgraded ->
                store.saveBootstrap(upgraded) {}
            }
            onResult(upgradedResult.mapCatching(HouseholdBootstrapDocument::toHousehold))
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
    val membership: Map<String, Any?>,
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
            ownerEmail = household.nullableString(OWNER_EMAIL),
            useTags = household.booleanOrDefault(USE_TAGS),
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

internal data class HouseholdBootstrapDocument(
    val memberId: String,
    val data: Map<String, Any?>,
) {
    fun isLegacy(): Boolean =
        HOUSEHOLD_NAME !in data && OWNER_MEMBER_ID !in data && USE_TAGS !in data

    fun withHouseholdSummary(household: Map<String, Any?>): HouseholdBootstrapDocument = copy(
        data = data + mapOf(
            HOUSEHOLD_NAME to household.string(NAME),
            OWNER_MEMBER_ID to household.string(OWNER_MEMBER_ID),
            USE_TAGS to household.boolean(USE_TAGS),
        ) + household.nullableString(OWNER_EMAIL)?.let { mapOf(OWNER_EMAIL to it) }.orEmpty(),
    )

    fun toHousehold(): Household {
        val role = data.string(ROLE)
        val householdId = data.string(HOUSEHOLD_ID)
        val householdName = data.string(HOUSEHOLD_NAME)
        val ownerMemberId = data.string(OWNER_MEMBER_ID)
        if (
            role != OWNER && role != MEMBER ||
            (role == OWNER) != (memberId == ownerMemberId)
        ) {
            throw HouseholdDataException()
        }
        return Household(
            id = householdId,
            ownerMemberId = ownerMemberId,
            ownerEmail = data.nullableString(OWNER_EMAIL),
            useTags = data.boolean(USE_TAGS),
            rootItem = Item(
                id = householdId,
                name = householdName,
                parentItemId = null,
                photoUrl = null,
                description = null,
                tags = emptyList(),
            ),
        )
    }
}

internal enum class HouseholdDocumentSource {
    Cache,
    Server,
}

internal interface HouseholdDocumentStore {
    val serverTimestamp: Any

    fun newHouseholdId(): String

    fun loadBootstrap(
        memberId: String,
        source: HouseholdDocumentSource,
        onResult: (Result<HouseholdBootstrapDocument?>) -> Unit,
    )

    fun loadHouseholdSummary(
        householdId: String,
        onResult: (Result<Map<String, Any?>>) -> Unit,
    )

    fun saveBootstrap(
        bootstrap: HouseholdBootstrapDocument,
        onResult: (Result<Unit>) -> Unit,
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

    override fun loadBootstrap(
        memberId: String,
        source: HouseholdDocumentSource,
        onResult: (Result<HouseholdBootstrapDocument?>) -> Unit,
    ) {
        val firestoreSource = when (source) {
            HouseholdDocumentSource.Cache -> Source.CACHE
            HouseholdDocumentSource.Server -> Source.SERVER
        }
        firestore.collection(MEMBERSHIPS).document(memberId).get(firestoreSource)
            .addOnSuccessListener { membership ->
                onResult(
                    if (!membership.exists()) {
                        Result.success(null)
                    } else {
                        Result.success(
                            HouseholdBootstrapDocument(
                                memberId = memberId,
                                data = membership.data.orEmpty(),
                            ),
                        )
                    },
                )
            }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    override fun loadHouseholdSummary(
        householdId: String,
        onResult: (Result<Map<String, Any?>>) -> Unit,
    ) {
        firestore.collection(HOUSEHOLDS).document(householdId).get(Source.SERVER)
            .addOnSuccessListener { household ->
                val data = household.data
                onResult(
                    if (data == null) {
                        Result.failure(HouseholdDataException())
                    } else {
                        Result.success(data)
                    },
                )
            }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    override fun saveBootstrap(
        bootstrap: HouseholdBootstrapDocument,
        onResult: (Result<Unit>) -> Unit,
    ) {
        firestore.collection(MEMBERSHIPS).document(bootstrap.memberId).update(
            mapOf(
                HOUSEHOLD_NAME to bootstrap.data[HOUSEHOLD_NAME],
                OWNER_MEMBER_ID to bootstrap.data[OWNER_MEMBER_ID],
                OWNER_EMAIL to bootstrap.data[OWNER_EMAIL],
                USE_TAGS to bootstrap.data[USE_TAGS],
            ),
        ).addOnSuccessListener { onResult(Result.success(Unit)) }
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
            transaction.set(membershipReference, documents.membership)
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
        membership = mapOf(
            HOUSEHOLD_ID to householdId,
            ROLE to OWNER,
            HOUSEHOLD_NAME to name,
            OWNER_MEMBER_ID to owner.id,
            OWNER_EMAIL to requireNotNull(normalizeGoogleEmail(owner.email)),
            USE_TAGS to false,
        ),
        household = mapOf(
            NAME to name,
            OWNER_MEMBER_ID to owner.id,
            OWNER_EMAIL to requireNotNull(normalizeGoogleEmail(owner.email)),
            ROOT_ITEM_ID to householdId,
            USE_TAGS to false,
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

private fun Map<String, Any?>.booleanOrDefault(key: String, default: Boolean = false): Boolean {
    val value = this[key] ?: return default
    return value as? Boolean ?: throw HouseholdDataException()
}

private fun Map<String, Any?>.boolean(key: String): Boolean =
    this[key] as? Boolean ?: throw HouseholdDataException()

private const val MEMBERSHIPS = "memberships"
private const val HOUSEHOLDS = "households"
private const val ITEMS = "items"
private const val HOUSEHOLD_ID = "householdId"
private const val ROLE = "role"
private const val OWNER = "owner"
private const val MEMBER = "member"
private const val NAME = "name"
private const val HOUSEHOLD_NAME = "householdName"
private const val OWNER_MEMBER_ID = "ownerMemberId"
private const val OWNER_EMAIL = "ownerEmail"
private const val ROOT_ITEM_ID = "rootItemId"
private const val USE_TAGS = "useTags"
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
