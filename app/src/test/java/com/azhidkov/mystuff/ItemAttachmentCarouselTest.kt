package com.azhidkov.mystuff

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemAttachmentCarouselTest {
    @Test
    fun `carousel starts with Item Photo and appends every other attachment oldest first`() {
        val item = carouselItem(photoAttachmentId = "photo")
        val attachments = listOf(
            carouselAttachment("newer", 3),
            carouselAttachment("photo", 1),
            carouselAttachment("older", 0),
        )

        assertEquals(
            listOf(
                CarouselImage.ItemPhoto(item),
                CarouselImage.Attachment(attachments[2]),
                CarouselImage.Attachment(attachments[0]),
            ),
            item.carouselImages(attachments),
        )
    }

    @Test
    fun `attachment badge excludes projected Item Photo and includes pending records`() {
        val item = carouselItem(photoAttachmentId = "photo")

        assertEquals(
            2,
            item.otherAttachmentCount(
                listOf(
                    carouselAttachment("photo", 1),
                    carouselAttachment("pending", 2),
                    carouselAttachment("receipt", 3),
                ),
            ),
        )
    }
}

private fun carouselItem(photoAttachmentId: String?) = Item(
    id = "item-1",
    name = "Drill",
    parentItemId = "household-1",
    photoUrl = "gs://mystuff/photo.webp",
    description = null,
    tags = emptyList(),
    photoAttachmentId = photoAttachmentId,
)

private fun carouselAttachment(id: String, seconds: Long) = ItemAttachment(
    id = id,
    itemId = "item-1",
    createdAt = Instant.ofEpochSecond(seconds),
    contentType = OPTIMIZED_ATTACHMENT_IMAGE_CONTENT_TYPE,
    displayUrl = "gs://mystuff/$id.webp",
)
