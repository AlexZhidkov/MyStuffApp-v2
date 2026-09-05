package com.azhidkov.mystuff.ui

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    private val writeTemporaryFile: (File, ByteArray) -> Unit = File::writeBytes,
) {
    private val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preparations = ConcurrentHashMap<String, Deferred<T?>>()

    /** Register before publishing the photo location so readers wait for local bytes, not Firebase. */
    fun prepare(location: String, source: suspend () -> ByteArray) {
        val preparation = preparationScope.async(start = CoroutineStart.LAZY) {
            try {
                cacheBytes(location, source())
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                null // A missing local source must not prevent the normal remote load.
            }
        }
        if (preparations.putIfAbsent(location, preparation) != null) {
            preparation.cancel()
            return
        }
        preparation.invokeOnCompletion { preparations.remove(location, preparation) }
        preparation.start()
    }

    fun memoryValue(location: String): T? = memory.get(location)

    suspend fun cachedValue(location: String): T? =
        memory.get(location) ?: preparations[location]?.await() ?: withContext(Dispatchers.IO) {
            memory.get(location) ?: run {
                val cacheFile = directory.resolve(thumbnailCacheFileName(location))
                if (!cacheFile.isFile) return@run null
                try {
                    decode(cacheFile.readBytes()).also { decoded ->
                        memory.put(location, decoded)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    cacheFile.delete()
                    null
                }
            }
        }

    suspend fun load(location: String): T = cachedValue(location) ?: withContext(Dispatchers.IO) {
        memory.get(location) ?: cacheBytes(location, download(location))
    }

    private suspend fun cacheBytes(location: String, bytes: ByteArray): T {
        val decoded = decode(bytes)
        try {
            writeAtomically(directory.resolve(thumbnailCacheFileName(location)), bytes)
        } catch (_: Exception) {
            // Disk caching is best effort; the decoded thumbnail remains useful in memory.
        }
        return decoded.also { memory.put(location, it) }
    }

    private fun writeAtomically(cacheFile: File, bytes: ByteArray) {
        directory.mkdirs()
        val temporaryFile = File.createTempFile(cacheFile.name, ".part", directory)
        try {
            writeTemporaryFile(temporaryFile, bytes)
            try {
                Files.move(
                    temporaryFile.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporaryFile.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporaryFile.delete()
        }
    }
}

internal class StoredPhotoLoader<T>(
    private val thumbnails: ThumbnailCache<T>,
    private val download: suspend (location: String, maxBytes: Long) -> ByteArray,
    private val decode: suspend (ByteArray) -> T,
) {
    fun prepareThumbnail(location: String, source: suspend () -> ByteArray) =
        thumbnails.prepare(location, source)

    fun thumbnailMemoryValue(location: String): T? = thumbnails.memoryValue(location)

    suspend fun cachedThumbnailValue(location: String): T? = thumbnails.cachedValue(location)

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

internal class AttachmentDisplayPhotoCache<T>(
    private val directory: File,
    private val download: suspend (String) -> ByteArray,
    private val decode: suspend (ByteArray) -> T,
    private val writeTemporaryFile: (File, ByteArray) -> Unit = File::writeBytes,
) {
    fun remove(location: String) {
        directory.resolve(attachmentDisplayCacheFileName(location)).delete()
    }

    suspend fun load(location: String): T = withContext(Dispatchers.IO) {
        val cacheFile = directory.resolve(attachmentDisplayCacheFileName(location))
        val cached = readCached(cacheFile)
        if (cached != null) return@withContext cached

        val bytes = download(location)
        val decoded = decode(bytes)
        runCatching { writeAtomically(cacheFile, bytes) }
        decoded
    }

    private suspend fun readCached(cacheFile: File): T? {
        if (!cacheFile.isFile) return null
        return try {
            decode(cacheFile.readBytes())
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            cacheFile.delete()
            null
        }
    }

    private fun writeAtomically(cacheFile: File, bytes: ByteArray) {
        directory.mkdirs()
        val temporaryFile = File.createTempFile(cacheFile.name, ".part", directory)
        try {
            writeTemporaryFile(temporaryFile, bytes)
            try {
                Files.move(
                    temporaryFile.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporaryFile.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporaryFile.delete()
        }
    }
}

internal fun thumbnailCacheFileName(location: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(location.encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return "$digest.webp"
}

internal fun attachmentDisplayCacheFileName(location: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(location.encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return "$digest.image"
}

internal fun thumbnailMemoryCacheMaxBytes(maxHeapBytes: Long): Long = maxHeapBytes / 8L

internal const val MAX_FULL_PHOTO_DOWNLOAD_BYTES = 2L * 1024 * 1024
internal const val MAX_THUMBNAIL_DOWNLOAD_BYTES = 256L * 1024
