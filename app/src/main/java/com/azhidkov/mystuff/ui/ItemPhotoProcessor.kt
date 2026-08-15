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

internal object ItemPhotoProcessor {
    fun writeVariants(crop: Bitmap, directory: File): ItemPhotoFiles {
        directory.mkdirs()
        val stem = "cropped-${UUID.randomUUID()}"
        return ItemPhotoFiles(
            full = writeVariant(crop, directory, stem, ItemPhotoVariant.Full),
            thumbnail = writeVariant(crop, directory, stem, ItemPhotoVariant.Thumbnail),
        )
    }

    private fun writeVariant(
        crop: Bitmap,
        directory: File,
        stem: String,
        variant: ItemPhotoVariant,
    ): File {
        val output = crop.scaledWithin(variant.maxSide)
        val file = File(directory, "$stem${variant.fileSuffix}")
        file.outputStream().use { stream ->
            check(output.compress(webPFormat(), variant.webPQuality, stream))
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
