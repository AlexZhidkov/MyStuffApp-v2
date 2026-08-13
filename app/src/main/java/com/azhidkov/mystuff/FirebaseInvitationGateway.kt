package com.azhidkov.mystuff

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.time.Instant

class FirebaseInvitationGateway(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : InvitationGateway {
    override fun load(
        householdId: String,
        onResult: (Result<List<HouseholdInvitation>>) -> Unit,
    ) {
        firestore.collection(INVITATIONS)
            .whereEqualTo(HOUSEHOLD_ID, householdId)
            .get()
            .addOnSuccessListener { snapshot ->
                onResult(
                    runCatching {
                        snapshot.documents.map(DocumentSnapshot::toInvitation)
                    },
                )
            }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    override fun create(
        householdId: String,
        intendedEmail: String,
        onResult: (Result<HouseholdInvitation>) -> Unit,
    ) {
        val invitationReference = firestore.collection(INVITATIONS).document()
        val draftReference = firestore.collection(INVITATION_DRAFTS)
            .document(invitationReference.id)
        beginInvitationDraft(
            draftReference = draftReference,
            householdId = householdId,
            intendedEmail = intendedEmail,
            onReady = { invitation ->
                firestore.batch()
                    .set(invitationReference, invitation.toDocument())
                    .delete(draftReference)
                    .commit()
                    .addOnSuccessListener { onResult(Result.success(invitation)) }
                    .addOnFailureListener { failure ->
                        discardDraft(draftReference, failure, onResult)
                    }
            },
            onFailure = { failure -> onResult(Result.failure(failure)) },
        )
    }

    override fun revoke(
        invitation: HouseholdInvitation,
        onResult: (Result<HouseholdInvitation>) -> Unit,
    ) {
        firestore.collection(INVITATIONS).document(invitation.id)
            .update(STATUS, REVOKED)
            .addOnSuccessListener {
                onResult(
                    Result.success(invitation.copy(storedStatus = InvitationStatus.Revoked)),
                )
            }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    override fun expire(
        invitation: HouseholdInvitation,
        onResult: (Result<HouseholdInvitation>) -> Unit,
    ) {
        firestore.collection(INVITATIONS).document(invitation.id)
            .update(STATUS, EXPIRED)
            .addOnSuccessListener {
                onResult(
                    Result.success(invitation.copy(storedStatus = InvitationStatus.Expired)),
                )
            }
            .addOnFailureListener { failure -> onResult(Result.failure(failure)) }
    }

    override fun replace(
        invitation: HouseholdInvitation,
        intendedEmail: String,
        onResult: (Result<InvitationReplacement>) -> Unit,
    ) {
        val previousReference = firestore.collection(INVITATIONS).document(invitation.id)
        val replacementReference = firestore.collection(INVITATIONS).document()
        val draftReference = firestore.collection(INVITATION_DRAFTS)
            .document(replacementReference.id)
        beginInvitationDraft(
            draftReference = draftReference,
            householdId = invitation.householdId,
            intendedEmail = intendedEmail,
            replacesInvitationId = invitation.id,
            onReady = { replacement ->
                val previous = invitation.copy(
                    storedStatus = InvitationStatus.Replaced,
                    replacedByInvitationId = replacement.id,
                )
                firestore.batch()
                    .update(
                        previousReference,
                        mapOf(
                            STATUS to REPLACED,
                            REPLACED_BY_INVITATION_ID to replacement.id,
                        ),
                    )
                    .set(replacementReference, replacement.toDocument())
                    .delete(draftReference)
                    .commit()
                    .addOnSuccessListener {
                        onResult(Result.success(InvitationReplacement(previous, replacement)))
                    }
                    .addOnFailureListener { failure ->
                        discardDraft(draftReference, failure, onResult)
                    }
            },
            onFailure = { failure -> onResult(Result.failure(failure)) },
        )
    }
}

private fun invitationDraftDocument(
    householdId: String,
    intendedEmail: String,
    replacesInvitationId: String? = null,
): Map<String, Any?> = mapOf(
    HOUSEHOLD_ID to householdId,
    INTENDED_EMAIL to intendedEmail,
    CREATED_AT to FieldValue.serverTimestamp(),
    REPLACES_INVITATION_ID to replacesInvitationId,
)

private fun beginInvitationDraft(
    draftReference: DocumentReference,
    householdId: String,
    intendedEmail: String,
    replacesInvitationId: String? = null,
    onReady: (HouseholdInvitation) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    draftReference.set(
        invitationDraftDocument(
            householdId = householdId,
            intendedEmail = intendedEmail,
            replacesInvitationId = replacesInvitationId,
        ),
    ).addOnSuccessListener {
        draftReference.get()
            .addOnSuccessListener { snapshot ->
                runCatching(snapshot::toPendingInvitation)
                    .onSuccess(onReady)
                    .onFailure { failure ->
                        draftReference.delete()
                        onFailure(failure)
                    }
            }
            .addOnFailureListener { failure ->
                draftReference.delete()
                onFailure(failure)
            }
    }.addOnFailureListener(onFailure)
}

private fun <T> discardDraft(
    draftReference: DocumentReference,
    failure: Throwable,
    onResult: (Result<T>) -> Unit,
) {
    draftReference.delete().addOnCompleteListener {
        onResult(Result.failure(failure))
    }
}

private fun DocumentSnapshot.toPendingInvitation(): HouseholdInvitation {
    val createdAt = requiredTimestamp(CREATED_AT)
    return HouseholdInvitation(
        id = id,
        householdId = requiredString(HOUSEHOLD_ID),
        intendedEmail = requiredString(INTENDED_EMAIL),
        createdAt = createdAt.toInstant(),
        expiresAt = Timestamp(
            createdAt.seconds + SEVEN_DAYS_SECONDS,
            createdAt.nanoseconds,
        ).toInstant(),
        storedStatus = InvitationStatus.Pending,
        replacesInvitationId = optionalString(REPLACES_INVITATION_ID),
        replacedByInvitationId = null,
    )
}

private fun HouseholdInvitation.toDocument(): Map<String, Any?> = mapOf(
    HOUSEHOLD_ID to householdId,
    INTENDED_EMAIL to intendedEmail,
    CREATED_AT to createdAt.toTimestamp(),
    EXPIRES_AT to expiresAt.toTimestamp(),
    STATUS to PENDING,
    REPLACES_INVITATION_ID to replacesInvitationId,
    REPLACED_BY_INVITATION_ID to null,
)

private fun DocumentSnapshot.toInvitation(): HouseholdInvitation = HouseholdInvitation(
        id = id,
        householdId = requiredString(HOUSEHOLD_ID),
        intendedEmail = requiredString(INTENDED_EMAIL),
        createdAt = requiredTimestamp(CREATED_AT).toInstant(),
        expiresAt = requiredTimestamp(EXPIRES_AT).toInstant(),
        storedStatus = requiredString(STATUS).toInvitationStatus(),
        replacesInvitationId = optionalString(REPLACES_INVITATION_ID),
        replacedByInvitationId = optionalString(REPLACED_BY_INVITATION_ID),
)

private fun DocumentSnapshot.requiredString(field: String): String =
    getString(field) ?: throw InvitationDataException()

private fun DocumentSnapshot.requiredTimestamp(field: String): Timestamp =
    getTimestamp(field) ?: throw InvitationDataException()

private fun DocumentSnapshot.optionalString(field: String): String? {
    val value = get(field)
    if (value != null && value !is String) throw InvitationDataException()
    return value
}

private fun Instant.toTimestamp(): Timestamp = Timestamp(epochSecond, nano)

private fun String.toInvitationStatus(): InvitationStatus = when (this) {
    PENDING -> InvitationStatus.Pending
    ACCEPTED -> InvitationStatus.Accepted
    REVOKED -> InvitationStatus.Revoked
    REPLACED -> InvitationStatus.Replaced
    EXPIRED -> InvitationStatus.Expired
    else -> throw InvitationDataException()
}

private const val INVITATIONS = "invitations"
private const val INVITATION_DRAFTS = "invitationDrafts"
private const val HOUSEHOLD_ID = "householdId"
private const val INTENDED_EMAIL = "intendedEmail"
private const val CREATED_AT = "createdAt"
private const val EXPIRES_AT = "expiresAt"
private const val STATUS = "status"
private const val REPLACES_INVITATION_ID = "replacesInvitationId"
private const val REPLACED_BY_INVITATION_ID = "replacedByInvitationId"
private const val PENDING = "pending"
private const val ACCEPTED = "accepted"
private const val REVOKED = "revoked"
private const val REPLACED = "replaced"
private const val EXPIRED = "expired"
private const val SEVEN_DAYS_SECONDS = 7L * 24 * 60 * 60

private class InvitationDataException : IllegalStateException(
    "Invitation data is incomplete. Please try again.",
)
