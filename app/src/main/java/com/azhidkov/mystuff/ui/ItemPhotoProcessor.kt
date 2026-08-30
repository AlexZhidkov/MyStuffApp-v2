package com.azhidkov.mystuff.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.scale
import com.azhidkov.mystuff.ItemPhotoVariant
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

internal data class ItemPhotoFiles(
    val full: File,
    val thumbnail: File,
)

internal data class PhotoDimensions(
    val width: Int,
    val height: Int,
)

internal enum class PhotoProcessingPurpose {
    ItemPhoto,
    ItemAttachment,
}

internal object ItemPhotoProcessor {
    fun writeVariants(
        crop: Bitmap,
        directory: File,
        purpose: PhotoProcessingPurpose = PhotoProcessingPurpose.ItemPhoto,
    ): ItemPhotoFiles {
        directory.mkdirs()
        val stem = "cropped-${UUID.randomUUID()}"
        val profile = if (purpose == PhotoProcessingPurpose.ItemAttachment) {
            ATTACHMENT_PROFILE
        } else {
            ITEM_PHOTO_PROFILE
        }
        return ItemPhotoFiles(
            full = writeVariant(crop, directory, stem, profile.full),
            thumbnail = writeVariant(crop, directory, stem, profile.thumbnail),
        )
    }

    private fun writeVariant(
        crop: Bitmap,
        directory: File,
        stem: String,
        variant: PhotoEncodingVariant,
    ): File {
        val file = File(directory, "$stem${variant.fileSuffix}")
        var output = crop.scaledWithin(variant.maxSide)
        var quality = variant.webPQuality
        while (true) {
            file.outputStream().use { stream ->
                check(output.compress(webPFormat(), quality, stream))
            }
            if (file.length() <= variant.maxBytes) break
            if (quality > MIN_WEBP_QUALITY) {
                quality -= WEBP_QUALITY_STEP
            } else if (max(output.width, output.height) > 1) {
                val smaller = output.scaledWithin(
                    (max(output.width, output.height) * SIZE_REDUCTION_FACTOR)
                        .roundToInt()
                        .coerceAtLeast(1),
                )
                if (smaller !== output) {
                    if (output !== crop) output.recycle()
                    output = smaller
                    quality = variant.webPQuality
                } else {
                    break
                }
            } else {
                break
            }
        }
        if (output !== crop) output.recycle()
        return file
    }

    @Suppress("DEPRECATION")
    private fun webPFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
}

private data class PhotoEncodingVariant(
    val fileSuffix: String,
    val maxSide: Int,
    val webPQuality: Int,
    val maxBytes: Long,
)

private data class PhotoEncodingProfile(
    val full: PhotoEncodingVariant,
    val thumbnail: PhotoEncodingVariant,
)

private val ITEM_PHOTO_PROFILE = PhotoEncodingProfile(
    full = PhotoEncodingVariant(
        fileSuffix = ItemPhotoVariant.Full.fileSuffix,
        maxSide = ItemPhotoVariant.Full.maxSide,
        webPQuality = ItemPhotoVariant.Full.webPQuality,
        maxBytes = 2L * 1024 * 1024,
    ),
    thumbnail = PhotoEncodingVariant(
        fileSuffix = ItemPhotoVariant.Thumbnail.fileSuffix,
        maxSide = ItemPhotoVariant.Thumbnail.maxSide,
        webPQuality = ItemPhotoVariant.Thumbnail.webPQuality,
        maxBytes = 256L * 1024,
    ),
)

private val ATTACHMENT_PROFILE = PhotoEncodingProfile(
    full = PhotoEncodingVariant(
        fileSuffix = ItemPhotoVariant.Full.fileSuffix,
        maxSide = 2_048,
        webPQuality = 80,
        maxBytes = 2L * 1024 * 1024,
    ),
    thumbnail = ITEM_PHOTO_PROFILE.thumbnail,
)

private const val MIN_WEBP_QUALITY = 20
private const val WEBP_QUALITY_STEP = 5
private const val SIZE_REDUCTION_FACTOR = 0.85f

private fun Bitmap.scaledWithin(maxSide: Int): Bitmap {
    val dimensions = photoDimensions(width, height, maxSide)
    if (dimensions.width == width && dimensions.height == height) return this
    return scale(dimensions.width, dimensions.height)
}

internal fun photoDimensions(width: Int, height: Int, maxSide: Int): PhotoDimensions {
    val longestSide = max(width, height)
    if (longestSide <= maxSide) return PhotoDimensions(width, height)
    val scale = maxSide.toFloat() / longestSide
    return PhotoDimensions(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1),
    )
}
