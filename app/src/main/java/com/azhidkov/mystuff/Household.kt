package com.azhidkov.mystuff

data class Item(
    val id: String,
    val name: String,
    val parentItemId: String?,
    val photoUrl: String?,
    val description: String?,
    val tags: List<String>,
    val photoThumbnailUrl: String? = null,
    val webUrl: String? = null,
    /** The designated Item Photo's Item Attachment, when the photo is migrated. */
    val photoAttachmentId: String? = null,
    /** The shared position of this Item among the Child Items of its Parent Item. */
    val displayOrder: Long? = null,
)

data class Household(
    val id: String,
    val ownerMemberId: String,
    val rootItem: Item,
)

interface HouseholdGateway {
    fun findForMember(
        memberId: String,
        onResult: (Result<Household?>) -> Unit,
    )

    fun create(
        owner: AuthenticatedIdentity,
        name: String,
        onResult: (Result<Household>) -> Unit,
    )
}

internal object NoHouseholdGateway : HouseholdGateway {
    override fun findForMember(
        memberId: String,
        onResult: (Result<Household?>) -> Unit,
    ) {
        onResult(Result.success(null))
    }

    override fun create(
        owner: AuthenticatedIdentity,
        name: String,
        onResult: (Result<Household>) -> Unit,
    ) {
        onResult(Result.failure(UnsupportedOperationException("Household creation is unavailable.")))
    }
}
