package com.azhidkov.mystuff

import java.time.Instant
import java.util.Locale

enum class InvitationStatus {
    Pending,
    Accepted,
    Revoked,
    Replaced,
    Expired,
}

data class HouseholdInvitation(
    val id: String,
    val householdId: String,
    val intendedEmail: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val storedStatus: InvitationStatus,
    val replacesInvitationId: String?,
    val replacedByInvitationId: String?,
) {
    val link: String
        get() = "mystuff://invitation/$id"

    fun statusAt(now: Instant): InvitationStatus =
        if (storedStatus == InvitationStatus.Pending && !now.isBefore(expiresAt)) {
            InvitationStatus.Expired
        } else {
            storedStatus
        }
}

data class InvitationReplacement(
    val previous: HouseholdInvitation,
    val replacement: HouseholdInvitation,
)

interface InvitationGateway {
    fun load(
        householdId: String,
        onResult: (Result<List<HouseholdInvitation>>) -> Unit,
    )

    fun create(
        householdId: String,
        intendedEmail: String,
        onResult: (Result<HouseholdInvitation>) -> Unit,
    )

    fun revoke(
        invitation: HouseholdInvitation,
        onResult: (Result<HouseholdInvitation>) -> Unit,
    )

    fun expire(
        invitation: HouseholdInvitation,
        onResult: (Result<HouseholdInvitation>) -> Unit,
    )

    fun replace(
        invitation: HouseholdInvitation,
        intendedEmail: String,
        onResult: (Result<InvitationReplacement>) -> Unit,
    )
}

data class InvitationUiState(
    val canManage: Boolean,
    val invitations: List<HouseholdInvitation> = emptyList(),
    val emailError: String? = null,
    val operationInProgress: Boolean = false,
    val errorMessage: String? = null,
)

class InvitationController(
    private val household: Household,
    currentMemberId: String,
    private val gateway: InvitationGateway,
    private val now: () -> Instant = Instant::now,
) {
    var state = InvitationUiState(canManage = household.ownerMemberId == currentMemberId)
        private set

    var onStateChanged: (InvitationUiState) -> Unit = {}

    init {
        if (state.canManage) load()
    }

    fun create(rawEmail: String) {
        if (!state.canManage || state.operationInProgress) return
        val intendedEmail = normalizedEmail(rawEmail) ?: run {
            updateState(state.copy(emailError = "Enter a valid Google email address."))
            return
        }

        updateState(
            state.copy(
                emailError = null,
                errorMessage = null,
                operationInProgress = true,
            ),
        )
        gateway.create(household.id, intendedEmail) { result ->
            result.onSuccess { invitation ->
                updateState(
                    state.copy(
                        invitations = (state.invitations + invitation)
                            .sortedByDescending(HouseholdInvitation::createdAt),
                        operationInProgress = false,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operationInProgress = false,
                        errorMessage = failure.message ?: "Couldn't create the invitation.",
                    ),
                )
            }
        }
    }

    fun revoke(invitationId: String) {
        if (!state.canManage || state.operationInProgress) return
        val invitation = state.invitations.singleOrNull { it.id == invitationId } ?: return
        if (invitation.statusAt(now()) != InvitationStatus.Pending) return

        updateState(state.copy(operationInProgress = true, errorMessage = null))
        gateway.revoke(invitation) { result ->
            result.onSuccess { revoked ->
                updateState(
                    state.copy(
                        invitations = state.invitations.replace(revoked),
                        operationInProgress = false,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operationInProgress = false,
                        errorMessage = failure.message ?: "Couldn't revoke the invitation.",
                    ),
                )
            }
        }
    }

    fun expire(invitationId: String) {
        if (!state.canManage) return
        val invitation = state.invitations.singleOrNull { it.id == invitationId } ?: return
        if (
            invitation.storedStatus != InvitationStatus.Pending ||
            invitation.statusAt(now()) != InvitationStatus.Expired
        ) {
            return
        }

        gateway.expire(invitation) { result ->
            result.onSuccess { expired ->
                updateState(
                    state.copy(
                        invitations = state.invitations.replace(expired),
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        errorMessage = failure.message ?: "Couldn't update the invitation status.",
                    ),
                )
            }
        }
    }

    fun replace(invitationId: String, rawEmail: String) {
        if (!state.canManage || state.operationInProgress) return
        val invitation = state.invitations.singleOrNull { it.id == invitationId } ?: return
        if (invitation.statusAt(now()) != InvitationStatus.Pending) return
        val intendedEmail = normalizedEmail(rawEmail) ?: run {
            updateState(state.copy(emailError = "Enter a valid Google email address."))
            return
        }

        updateState(
            state.copy(
                emailError = null,
                errorMessage = null,
                operationInProgress = true,
            ),
        )
        gateway.replace(invitation, intendedEmail) { result ->
            result.onSuccess { replacement ->
                updateState(
                    state.copy(
                        invitations = (state.invitations.replace(replacement.previous) +
                            replacement.replacement)
                            .sortedByDescending(HouseholdInvitation::createdAt),
                        operationInProgress = false,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operationInProgress = false,
                        errorMessage = failure.message ?: "Couldn't replace the invitation.",
                    ),
                )
            }
        }
    }

    private fun load() {
        updateState(state.copy(operationInProgress = true, errorMessage = null))
        gateway.load(household.id) { result ->
            result.onSuccess { invitations ->
                updateState(
                    state.copy(
                        invitations = invitations.sortedByDescending(HouseholdInvitation::createdAt),
                        operationInProgress = false,
                    ),
                )
            }.onFailure { failure ->
                updateState(
                    state.copy(
                        operationInProgress = false,
                        errorMessage = failure.message ?: "Couldn't load invitations.",
                    ),
                )
            }
        }
    }

    private fun updateState(newState: InvitationUiState) {
        state = newState
        onStateChanged(newState)
    }

    private fun normalizedEmail(rawEmail: String): String? {
        val email = rawEmail.trim().lowercase(Locale.ROOT)
        return email.takeIf(EMAIL_PATTERN::matches)
    }

    private companion object {
        val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}

private fun List<HouseholdInvitation>.replace(
    updated: HouseholdInvitation,
): List<HouseholdInvitation> = map { current ->
    if (current.id == updated.id) updated else current
}
