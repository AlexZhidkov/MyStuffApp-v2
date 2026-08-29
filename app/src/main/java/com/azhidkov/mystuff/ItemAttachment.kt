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
