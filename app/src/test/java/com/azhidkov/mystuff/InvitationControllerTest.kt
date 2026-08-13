package com.azhidkov.mystuff

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class InvitationControllerTest {
    @Test
    fun `Household Owner creates a pending invitation for a normalized Google email`() {
        val gateway = FakeInvitationGateway()
        val controller = InvitationController(
            household = household(ownerMemberId = "member-1"),
            currentMemberId = "member-1",
            gateway = gateway,
            now = { NOW },
        )

        controller.create("  Sam@Example.com ")

        assertEquals("household-1", gateway.createdForHouseholdId)
        assertEquals("sam@example.com", gateway.createdForEmail)
        assertEquals(InvitationStatus.Pending, controller.state.invitations.single().statusAt(NOW))
    }

    @Test
    fun `Household Owner revokes a pending invitation`() {
        val pending = invitation(id = "invitation-1")
        val gateway = FakeInvitationGateway(initialInvitations = listOf(pending))
        val controller = InvitationController(
            household = household(ownerMemberId = "member-1"),
            currentMemberId = "member-1",
            gateway = gateway,
            now = { NOW },
        )

        controller.revoke("invitation-1")

        assertEquals("invitation-1", gateway.revokedInvitationId)
        assertEquals(InvitationStatus.Revoked, controller.state.invitations.single().statusAt(NOW))
    }

    @Test
    fun `Household Owner replaces an invitation with a fresh link`() {
        val pending = invitation(id = "invitation-1")
        val gateway = FakeInvitationGateway(initialInvitations = listOf(pending))
        val controller = InvitationController(
            household = household(ownerMemberId = "member-1"),
            currentMemberId = "member-1",
            gateway = gateway,
            now = { NOW },
        )

        controller.replace("invitation-1", "new@example.com")

        assertEquals("invitation-1", gateway.replacedInvitationId)
        assertEquals("new@example.com", gateway.replacementEmail)
        assertEquals(
            setOf(InvitationStatus.Replaced, InvitationStatus.Pending),
            controller.state.invitations.map { it.statusAt(NOW) }.toSet(),
        )
        assertEquals(
            "invitation-2",
            controller.state.invitations.single { it.id == "invitation-1" }.replacedByInvitationId,
        )
    }

    @Test
    fun `non-Owner has no invitation controls and cannot invoke gateway operations`() {
        val gateway = FakeInvitationGateway()
        val controller = InvitationController(
            household = household(ownerMemberId = "member-1"),
            currentMemberId = "member-2",
            gateway = gateway,
            now = { NOW },
        )

        controller.create("sam@example.com")
        controller.revoke("invitation-1")
        controller.replace("invitation-1", "sam@example.com")

        assertEquals(false, controller.state.canManage)
        assertEquals(0, gateway.loadCalls)
        assertEquals(null, gateway.createdForEmail)
        assertEquals(null, gateway.revokedInvitationId)
        assertEquals(null, gateway.replacedInvitationId)
    }

    @Test
    fun `pending invitation becomes expired at its seven-day boundary`() {
        val invitation = invitation(id = "invitation-1")

        assertEquals(
            InvitationStatus.Pending,
            invitation.statusAt(invitation.expiresAt.minusNanos(1)),
        )
        assertEquals(InvitationStatus.Expired, invitation.statusAt(invitation.expiresAt))
        assertEquals(InvitationStatus.Expired, invitation.statusAt(invitation.expiresAt.plusSeconds(1)))
    }

    @Test
    fun `Household Owner records an elapsed invitation as expired`() {
        val pending = invitation(
            id = "invitation-1",
            createdAt = NOW.minusSeconds(SEVEN_DAYS_SECONDS + 1),
            expiresAt = NOW.minusSeconds(1),
        )
        val gateway = FakeInvitationGateway(initialInvitations = listOf(pending))
        val controller = InvitationController(
            household = household(ownerMemberId = "member-1"),
            currentMemberId = "member-1",
            gateway = gateway,
            now = { NOW },
        )

        controller.expire("invitation-1")

        assertEquals("invitation-1", gateway.expiredInvitationId)
        assertEquals(InvitationStatus.Expired, controller.state.invitations.single().storedStatus)
    }
}

private class FakeInvitationGateway(
    private val initialInvitations: List<HouseholdInvitation> = emptyList(),
) : InvitationGateway {
    var loadCalls = 0
        private set
    var createdForHouseholdId: String? = null
        private set
    var createdForEmail: String? = null
        private set
    var revokedInvitationId: String? = null
        private set
    var replacedInvitationId: String? = null
        private set
    var replacementEmail: String? = null
        private set
    var expiredInvitationId: String? = null
        private set

    override fun load(
        householdId: String,
        onResult: (Result<List<HouseholdInvitation>>) -> Unit,
    ) {
        loadCalls += 1
        onResult(Result.success(initialInvitations))
    }

    override fun create(
        householdId: String,
        intendedEmail: String,
        onResult: (Result<HouseholdInvitation>) -> Unit,
    ) {
        createdForHouseholdId = householdId
        createdForEmail = intendedEmail
        onResult(
            Result.success(
                invitation(
                    id = "invitation-1",
                    intendedEmail = intendedEmail,
                ),
            ),
        )
    }

    override fun revoke(
        invitation: HouseholdInvitation,
        onResult: (Result<HouseholdInvitation>) -> Unit,
    ) {
        revokedInvitationId = invitation.id
        onResult(Result.success(invitation.copy(storedStatus = InvitationStatus.Revoked)))
    }

    override fun expire(
        invitation: HouseholdInvitation,
        onResult: (Result<HouseholdInvitation>) -> Unit,
    ) {
        expiredInvitationId = invitation.id
        onResult(Result.success(invitation.copy(storedStatus = InvitationStatus.Expired)))
    }

    override fun replace(
        invitation: HouseholdInvitation,
        intendedEmail: String,
        onResult: (Result<InvitationReplacement>) -> Unit,
    ) {
        replacedInvitationId = invitation.id
        replacementEmail = intendedEmail
        val replacement = invitation(
            id = "invitation-2",
            intendedEmail = intendedEmail,
            replacesInvitationId = invitation.id,
        )
        onResult(
            Result.success(
                InvitationReplacement(
                    previous = invitation.copy(
                        storedStatus = InvitationStatus.Replaced,
                        replacedByInvitationId = replacement.id,
                    ),
                    replacement = replacement,
                ),
            ),
        )
    }
}

private fun household(ownerMemberId: String) = Household(
    id = "household-1",
    ownerMemberId = ownerMemberId,
    rootItem = Item(
        id = "household-1",
        name = "Our Home",
        parentItemId = null,
        photoUrl = null,
        description = null,
        tags = emptyList(),
    ),
)

private fun invitation(
    id: String,
    intendedEmail: String = "sam@example.com",
    status: InvitationStatus = InvitationStatus.Pending,
    createdAt: Instant = NOW,
    expiresAt: Instant = NOW.plusSeconds(SEVEN_DAYS_SECONDS),
    replacesInvitationId: String? = null,
    replacedByInvitationId: String? = null,
) = HouseholdInvitation(
    id = id,
    householdId = "household-1",
    intendedEmail = intendedEmail,
    createdAt = createdAt,
    expiresAt = expiresAt,
    storedStatus = status,
    replacesInvitationId = replacesInvitationId,
    replacedByInvitationId = replacedByInvitationId,
)

private val NOW: Instant = Instant.parse("2026-08-13T10:00:00Z")
private const val SEVEN_DAYS_SECONDS = 7L * 24 * 60 * 60
