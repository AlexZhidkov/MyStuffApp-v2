package com.azhidkov.mystuff

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import java.util.Locale

data class HouseholdAccess(
    val email: String,
    val householdId: String,
    val memberId: String? = null,
    val memberDisplayName: String? = null,
    val memberEmail: String? = null,
) {
    val isClaimed: Boolean
        get() = memberId != null
}

interface HouseholdAccessGateway {
    fun load(
        householdId: String,
        onResult: (Result<List<HouseholdAccess>>) -> Unit,
    )

    fun add(
        householdId: String,
        email: String,
        onResult: (Result<HouseholdAccess>) -> Unit,
    )

    fun remove(
        householdId: String,
        email: String,
        onResult: (Result<Unit>) -> Unit,
    )
}

fun interface HouseholdAccessClaimGateway {
    fun claim(
        identity: AuthenticatedIdentity,
        onResult: (Result<String?>) -> Unit,
    )
}

internal object NoHouseholdAccessClaimGateway : HouseholdAccessClaimGateway {
    override fun claim(
        identity: AuthenticatedIdentity,
        onResult: (Result<String?>) -> Unit,
    ) = onResult(Result.success(null))
}

class FirebaseHouseholdAccessGateway(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTION_REGION),
) : HouseholdAccessGateway, HouseholdAccessClaimGateway {
    override fun load(
        householdId: String,
        onResult: (Result<List<HouseholdAccess>>) -> Unit,
    ) {
        firestore.collection(HOUSEHOLDS).document(householdId).collection(ACCESS)
            .get()
            .addOnSuccessListener { snapshot ->
                onResult(runCatching { snapshot.documents.map(DocumentSnapshot::toAccess) })
            }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    override fun add(
        householdId: String,
        email: String,
        onResult: (Result<HouseholdAccess>) -> Unit,
    ) {
        val access = HouseholdAccess(email = email, householdId = householdId)
        val reference = firestore.collection(HOUSEHOLDS).document(householdId)
            .collection(ACCESS).document(email)
        firestore.runTransaction { transaction ->
            if (transaction.get(reference).exists()) {
                throw IllegalStateException("That Google email already has Household Access.")
            }
            transaction.set(reference, access.toDocument())
        }
            .addOnSuccessListener { onResult(Result.success(access)) }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    override fun remove(
        householdId: String,
        email: String,
        onResult: (Result<Unit>) -> Unit,
    ) {
        functions.getHttpsCallable(REMOVE_FUNCTION)
            .call(mapOf("householdId" to householdId, "email" to email))
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    onResult(Result.success(Unit))
                } else {
                    onResult(
                        Result.failure(
                            task.exception
                                ?: IllegalStateException("Household Access could not be removed."),
                        ),
                    )
                }
            }
    }

    override fun claim(
        identity: AuthenticatedIdentity,
        onResult: (Result<String?>) -> Unit,
    ) {
        functions.getHttpsCallable(CLAIM_FUNCTION)
            .call()
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    onResult(
                        Result.failure(
                            task.exception
                                ?: IllegalStateException("Household Access could not be checked."),
                        ),
                    )
                    return@addOnCompleteListener
                }
                onResult(
                    runCatching {
                        val response = task.result?.data as? Map<*, *>
                            ?: error("Household Access returned an invalid response.")
                        response["householdId"] as? String
                    },
                )
            }
    }
}

class HouseholdAccessController(
    private val household: Household,
    private val currentIdentity: AuthenticatedIdentity,
    private val gateway: HouseholdAccessGateway,
) {
    var state = HouseholdAccessUiState(
        canManage = household.ownerMemberId == currentIdentity.id,
    )
        private set

    var onStateChanged: (HouseholdAccessUiState) -> Unit = {}

    init {
        if (state.canManage) load()
    }

    fun add(rawEmail: String) {
        if (!state.canManage || state.operationInProgress) return
        val email = normalizeGoogleEmail(rawEmail) ?: run {
            updateState(state.copy(emailError = "Enter a valid Google email address."))
            return
        }
        if (email == normalizeGoogleEmail(currentIdentity.email)) {
            updateState(state.copy(emailError = "The Household Owner is already a Member."))
            return
        }
        if (state.access.any { it.email == email }) {
            updateState(state.copy(emailError = "That Google email already has Household Access."))
            return
        }

        updateState(
            state.copy(
                emailError = null,
                errorMessage = null,
                operationInProgress = true,
            ),
        )
        gateway.add(household.id, email) { result ->
            result.onSuccess { access ->
                updateState(
                    state.copy(
                        access = (state.access + access).sortedBy(HouseholdAccess::email),
                        operationInProgress = false,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operationInProgress = false,
                        errorMessage = failure.message ?: "Couldn’t add Household Access.",
                    ),
                )
            }
        }
    }

    fun remove(email: String) {
        if (!state.canManage || state.operationInProgress) return
        val access = state.access.singleOrNull { it.email == email } ?: return
        if (access.memberId == household.ownerMemberId) return

        updateState(state.copy(operationInProgress = true, errorMessage = null))
        gateway.remove(household.id, email) { result ->
            result.onSuccess {
                updateState(
                    state.copy(
                        access = state.access.filterNot { it.email == email },
                        operationInProgress = false,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operationInProgress = false,
                        errorMessage = failure.message ?: "Couldn’t remove Household Access.",
                    ),
                )
            }
        }
    }

    private fun load() {
        updateState(state.copy(operationInProgress = true, errorMessage = null))
        gateway.load(household.id) { result ->
            result.onSuccess { access ->
                updateState(
                    state.copy(
                        access = access.sortedBy(HouseholdAccess::email),
                        operationInProgress = false,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operationInProgress = false,
                        errorMessage = failure.message ?: "Couldn’t load Household Access.",
                    ),
                )
            }
        }
    }

    private fun updateState(newState: HouseholdAccessUiState) {
        state = newState
        onStateChanged(newState)
    }
}

data class HouseholdAccessUiState(
    val canManage: Boolean,
    val access: List<HouseholdAccess> = emptyList(),
    val emailError: String? = null,
    val operationInProgress: Boolean = false,
    val errorMessage: String? = null,
)

fun normalizeGoogleEmail(value: String?): String? {
    if (value == null) return null
    val email = value.trim().lowercase(Locale.ROOT)
    return email.takeIf(EMAIL_PATTERN::matches)
}

private fun DocumentSnapshot.toAccess(): HouseholdAccess = HouseholdAccess(
    email = requiredString(EMAIL),
    householdId = requiredString(HOUSEHOLD_ID),
    memberId = optionalString(MEMBER_ID),
    memberDisplayName = optionalString(MEMBER_DISPLAY_NAME),
    memberEmail = optionalString(MEMBER_EMAIL),
)

private fun HouseholdAccess.toDocument(): Map<String, Any?> = mapOf(
    HOUSEHOLD_ID to householdId,
    EMAIL to email,
    MEMBER_ID to memberId,
    MEMBER_DISPLAY_NAME to memberDisplayName,
    MEMBER_EMAIL to memberEmail,
    CREATED_AT to FieldValue.serverTimestamp(),
    CLAIMED_AT to null,
)

private fun DocumentSnapshot.requiredString(field: String): String =
    getString(field) ?: throw HouseholdAccessDataException()

private fun DocumentSnapshot.optionalString(field: String): String? {
    val value = get(field)
    if (value != null && value !is String) throw HouseholdAccessDataException()
    return value
}

private class HouseholdAccessDataException : IllegalStateException(
    "Household Access data is incomplete. Please try again.",
)

private const val HOUSEHOLDS = "households"
private const val ACCESS = "access"
private const val HOUSEHOLD_ID = "householdId"
private const val EMAIL = "email"
private const val MEMBER_ID = "memberId"
private const val MEMBER_DISPLAY_NAME = "memberDisplayName"
private const val MEMBER_EMAIL = "memberEmail"
private const val CREATED_AT = "createdAt"
private const val CLAIMED_AT = "claimedAt"
private const val FUNCTION_REGION = "australia-southeast1"
private const val CLAIM_FUNCTION = "claimHouseholdAccess"
private const val REMOVE_FUNCTION = "removeHouseholdAccess"
private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
