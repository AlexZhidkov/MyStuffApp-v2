package com.azhidkov.mystuff

import java.time.Instant

/**
 * A supporting file owned by one non-root Item.
 *
 * The document identity and [createdAt] make the record stable and sortable;
 * [contentType] deliberately remains a MIME string so the record is not tied
 * to the current WebP-only file implementation.
 */
data class ItemAttachment(
    val id: String,
    val itemId: String,
    val createdAt: Instant,
    val contentType: String,
    val displayUrl: String,
)

internal object NoItemAttachmentGateway : ItemAttachmentGateway {
    override fun observe(
        household: Household,
        item: Item,
        onResult: (Result<List<ItemAttachment>>) -> Unit,
    ): InventorySubscription = InventorySubscription {}

    override fun newAttachmentId(householdId: String, itemId: String): String =
        throw UnsupportedOperationException("Item Attachments are unavailable.")

    override fun create(
        household: Household,
        item: Item,
        attachmentId: String,
        contentType: String,
        displayUrl: String,
        onResult: (Result<ItemAttachment>) -> Unit,
    ) = onResult(Result.failure(UnsupportedOperationException("Item Attachments are unavailable.")))

    override fun delete(
        household: Household,
        item: Item,
        attachment: ItemAttachment,
        onResult: (Result<Unit>) -> Unit,
    ) = onResult(Result.failure(UnsupportedOperationException("Item Attachments are unavailable.")))
}

sealed interface CarouselImage {
    data class ItemPhoto(val item: Item) : CarouselImage

    data class Attachment(val attachment: ItemAttachment) : CarouselImage
}

fun Item.carouselImages(attachments: List<ItemAttachment>): List<CarouselImage> = buildList {
    if (photoUrl != null) add(CarouselImage.ItemPhoto(this@carouselImages))
    attachments
        .asSequence()
        .filterNot { it.id == photoAttachmentId }
        .sortedBy(ItemAttachment::createdAt)
        .map(CarouselImage::Attachment)
        .forEach(::add)
}

fun Item.otherAttachmentCount(attachments: List<ItemAttachment>): Int =
    attachments.count { it.id != photoAttachmentId }

/** The current optimized image format used when a display file is created. */
const val OPTIMIZED_ATTACHMENT_IMAGE_CONTENT_TYPE = "image/webp"

/**
 * The initial attachment file layout. The extension is an implementation
 * detail of the current content type and can grow with future file types.
 */
fun itemAttachmentStoragePath(
    householdId: String,
    itemId: String,
    attachmentId: String,
    fileExtension: String = ".webp",
): String = "households/$householdId/items/$itemId/attachments/$attachmentId" +
    fileExtension.ensureFileExtension()

private fun String.ensureFileExtension(): String = when {
    isEmpty() -> ""
    startsWith('.') -> this
    else -> "." + this
}

fun itemAttachmentThumbnailStoragePath(
    householdId: String,
    itemId: String,
    attachmentId: String,
): String = "households/$householdId/items/$itemId/attachments/$attachmentId-thumb.webp"
