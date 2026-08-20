package com.azhidkov.mystuff.ui

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface ThumbnailMemoryCache<T> {
    fun get(location: String): T?

    fun put(location: String, value: T)
}

internal class SizedLruMemoryCache<T>(
    private val maxSizeBytes: Long,
    private val sizeOf: (T) -> Int,
) : ThumbnailMemoryCache<T> {
    private val values = LinkedHashMap<String, T>(0, 0.75f, true)
    private var sizeBytes = 0L

    init {
        require(maxSizeBytes > 0) { "Memory cache size must be positive" }
    }

    @Synchronized
    override fun get(location: String): T? = values[location]

    @Synchronized
    override fun put(location: String, value: T) {
        val valueSize = sizeOf(value).also {
            require(it >= 0) { "Cached value size must not be negative" }
        }
        values.put(location, value)?.let { replaced ->
            sizeBytes -= sizeOf(replaced)
        }
        sizeBytes += valueSize

        val iterator = values.entries.iterator()
        while (sizeBytes > maxSizeBytes && iterator.hasNext()) {
            val entry = iterator.next()
            sizeBytes -= sizeOf(entry.value)
            iterator.remove()
        }
    }
}

internal class ThumbnailCache<T>(
    private val directory: File,
    private val memory: ThumbnailMemoryCache<T>,
    private val download: suspend (String) -> ByteArray,
    private val decode: suspend (ByteArray) -> T,
) {
    fun memoryValue(location: String): T? = memory.get(location)

    suspend fun load(location: String): T = memory.get(location) ?: withContext(Dispatchers.IO) {
        memory.get(location) ?: run {
            val cacheFile = directory.resolve(thumbnailCacheFileName(location))
            val bytes = if (cacheFile.isFile) {
                cacheFile.readBytes()
            } else {
                download(location).also { downloaded ->
                    directory.mkdirs()
                    cacheFile.writeBytes(downloaded)
                }
            }
            decode(bytes).also { decoded -> memory.put(location, decoded) }
        }
    }
}

internal class StoredPhotoLoader<T>(
    private val thumbnails: ThumbnailCache<T>,
    private val download: suspend (location: String, maxBytes: Long) -> ByteArray,
    private val decode: suspend (ByteArray) -> T,
) {
    fun memoryValue(location: String, presentation: ItemPhotoPresentation): T? =
        when (presentation) {
            ItemPhotoPresentation.Compact -> thumbnails.memoryValue(location)
            ItemPhotoPresentation.Detail -> null
        }

    suspend fun load(location: String, presentation: ItemPhotoPresentation): T =
        when (presentation) {
            ItemPhotoPresentation.Compact -> thumbnails.load(location)
            ItemPhotoPresentation.Detail -> decode(download(location, MAX_FULL_PHOTO_DOWNLOAD_BYTES))
        }
}

internal fun thumbnailCacheFileName(location: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(location.encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return "$digest.webp"
}

internal fun thumbnailMemoryCacheMaxBytes(maxHeapBytes: Long): Long = maxHeapBytes / 8L

internal const val MAX_FULL_PHOTO_DOWNLOAD_BYTES = 2L * 1024 * 1024
internal const val MAX_THUMBNAIL_DOWNLOAD_BYTES = 256L * 1024
