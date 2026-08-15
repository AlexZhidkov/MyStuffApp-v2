package com.azhidkov.mystuff

data class ItemPhoto(
    val uri: String,
    val thumbnailUri: String = uri,
)

enum class ItemPhotoVariant(
    val fileSuffix: String,
    val maxSide: Int,
    val webPQuality: Int,
) {
    Full(fileSuffix = ".webp", maxSide = 1_024, webPQuality = 75),
    Thumbnail(fileSuffix = "-thumb.webp", maxSide = 256, webPQuality = 68),
}

data class ItemPhotoLocations(
    val full: String,
    val thumbnail: String,
)
