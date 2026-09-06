package com.azhidkov.mystuff.ui

import com.azhidkov.mystuff.Item
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ItemPhotoPresentation {
    Detail,
    Compact,
}

internal sealed interface PhotoLoadState<out T> {
    data object Loading : PhotoLoadState<Nothing>

    data object Unavailable : PhotoLoadState<Nothing>

    data class Available<T>(
        val value: T,
        val resolution: PhotoResolution = PhotoResolution.Full,
    ) : PhotoLoadState<T>
}

internal enum class PhotoResolution {
    Preview,
    Full,
}

internal fun storedPhotoLocation(item: Item, presentation: ItemPhotoPresentation): String? =
    when (presentation) {
        ItemPhotoPresentation.Detail -> item.photoUrl
        ItemPhotoPresentation.Compact -> item.photoThumbnailUrl
    }

private fun <T> detailStateWithPreview(
    current: PhotoLoadState<T>,
    preview: T,
): PhotoLoadState<T> =
    if (
        current is PhotoLoadState.Available &&
        current.resolution == PhotoResolution.Full
    ) {
        current
    } else {
        PhotoLoadState.Available(preview, PhotoResolution.Preview)
    }

private fun <T> detailStateWithFullLoad(
    current: PhotoLoadState<T>,
    fullLoad: PhotoLoadState<T>,
): PhotoLoadState<T> =
    when {
        fullLoad is PhotoLoadState.Available ->
            PhotoLoadState.Available(fullLoad.value, PhotoResolution.Full)
        current is PhotoLoadState.Available &&
            current.resolution == PhotoResolution.Preview -> current
        else -> fullLoad
    }

internal interface ThumbnailMemoryCache<T> {
    fun get(location: String): T?

    fun put(location: String, value: T)

    fun remove(location: String)

    fun clear()
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

    @Synchronized
    override fun remove(location: String) {
        values.remove(location)?.let { removed ->
            sizeBytes -= sizeOf(removed)
        }
    }

    @Synchronized
    override fun clear() {
        values.clear()
        sizeBytes = 0
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
    private val commitLock = Any()
    private var sessionGeneration = 0L
    private val locationGenerations = mutableMapOf<String, Long>()

    /** Register before publishing the photo location so readers wait for local bytes, not Firebase. */
    fun prepare(location: String, source: suspend () -> ByteArray) {
        val requestGeneration = generationFor(location)
        val preparation = preparationScope.async(start = CoroutineStart.LAZY) {
            try {
                cacheBytes(location, source(), requestGeneration)
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

    suspend fun cachedValue(location: String): T? {
        val requestGeneration = generationFor(location)
        return cachedValue(location, requestGeneration)
    }

    private suspend fun cachedValue(location: String, requestGeneration: CacheGeneration): T? =
        memory.get(location) ?: preparations[location]?.await() ?: withContext(Dispatchers.IO) {
            memory.get(location) ?: run {
                val cacheFile = directory.resolve(thumbnailCacheFileName(location))
                if (!cacheFile.isFile) return@run null
                try {
                    decode(cacheFile.readBytes()).also { decoded ->
                        commit(location, requestGeneration) { memory.put(location, decoded) }
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    cacheFile.delete()
                    null
                }
            }
        }

    suspend fun load(location: String): T {
        val requestGeneration = generationFor(location)
        return cachedValue(location, requestGeneration) ?: withContext(Dispatchers.IO) {
            memory.get(location) ?: cacheBytes(
                location,
                download(location),
                requestGeneration,
            )
        }
    }

    fun remove(location: String) {
        preparations.remove(location)?.cancel()
        synchronized(commitLock) {
            locationGenerations[location] = locationGenerations.getOrDefault(location, 0L) + 1
            memory.remove(location)
            directory.resolve(thumbnailCacheFileName(location)).delete()
        }
    }

    fun clear() {
        val pending = preparations.values.toList()
        preparations.clear()
        synchronized(commitLock) {
            sessionGeneration += 1
            locationGenerations.clear()
            memory.clear()
            directory.listFiles().orEmpty().forEach(File::delete)
        }
        pending.forEach(Deferred<*>::cancel)
    }

    private suspend fun cacheBytes(
        location: String,
        bytes: ByteArray,
        requestGeneration: CacheGeneration,
    ): T {
        val decoded = decode(bytes)
        commit(location, requestGeneration) {
            try {
                writeCacheFileAtomically(
                    directory.resolve(thumbnailCacheFileName(location)),
                    bytes,
                    writeTemporaryFile,
                )
            } catch (_: Exception) {
                // Disk caching is best effort; the decoded thumbnail remains useful in memory.
            }
            memory.put(location, decoded)
        }
        return decoded
    }

    private fun generationFor(location: String): CacheGeneration = synchronized(commitLock) {
        CacheGeneration(
            session = sessionGeneration,
            location = locationGenerations.getOrDefault(location, 0L),
        )
    }

    private inline fun commit(
        location: String,
        requestGeneration: CacheGeneration,
        block: () -> Unit,
    ) {
        synchronized(commitLock) {
            if (requestGeneration != generationFor(location)) {
                throw CancellationException("The Item Photo cache session changed")
            }
            block()
        }
    }

    private data class CacheGeneration(
        val session: Long,
        val location: Long,
    )
}

internal sealed interface ItemPhotoRequest {
    data class Stored(
        val location: String,
        val presentation: ItemPhotoPresentation,
        val previewLocation: String? = null,
    ) : ItemPhotoRequest

    data class AttachmentDisplay(
        val location: String,
        val previewLocation: String? = null,
    ) : ItemPhotoRequest
}

internal class ItemPhotoLoader<T>(
    private val thumbnails: ThumbnailCache<T>,
    private val attachmentDisplays: AttachmentDisplayPhotoCache<T>,
    private val downloadStoredPhoto: suspend (location: String, maxBytes: Long) -> ByteArray,
    private val decode: suspend (ByteArray) -> T,
    private val waitForRetry: suspend (Long) -> Unit = { delay(it) },
    private val sessionIdentityFile: File? = null,
) {
    private val requestLock = Any()
    private val activeRequests = mutableSetOf<Job>()
    private var sessionInitialized = false
    private var memberId: String? = null
    private var observedThumbnailLocations = emptySet<String>()

    fun states(request: ItemPhotoRequest): Flow<PhotoLoadState<T>> = callbackFlow {
        val requestJob = launch {
            var current = initialState(request)
            val stateLock = Mutex()

            suspend fun update(transform: (PhotoLoadState<T>) -> PhotoLoadState<T>) {
                stateLock.withLock {
                    val updated = transform(current)
                    if (updated != current) {
                        current = updated
                        send(updated)
                    }
                }
            }

            send(current)
            when (request) {
                is ItemPhotoRequest.Stored -> coroutineScope {
                    if (request.presentation == ItemPhotoPresentation.Detail) {
                        request.previewLocation?.let { previewLocation ->
                            launch {
                                thumbnails.cachedValue(previewLocation)?.let { preview ->
                                    update { detailStateWithPreview(it, preview) }
                                }
                            }
                        }
                    }
                    launch {
                        var retryDelayMillis = INITIAL_PHOTO_RETRY_DELAY_MILLIS
                        while (true) {
                            try {
                                val value = when (request.presentation) {
                                    ItemPhotoPresentation.Compact -> thumbnails.load(request.location)
                                    ItemPhotoPresentation.Detail -> decode(
                                        downloadStoredPhoto(
                                            request.location,
                                            MAX_FULL_PHOTO_DOWNLOAD_BYTES,
                                        ),
                                    )
                                }
                                update { currentState ->
                                    if (request.presentation == ItemPhotoPresentation.Detail) {
                                        detailStateWithFullLoad(
                                            currentState,
                                            PhotoLoadState.Available(value),
                                        )
                                    } else {
                                        PhotoLoadState.Available(value)
                                    }
                                }
                                break
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                update { currentState ->
                                    if (request.presentation == ItemPhotoPresentation.Detail) {
                                        detailStateWithFullLoad(
                                            currentState,
                                            PhotoLoadState.Unavailable,
                                        )
                                    } else {
                                        PhotoLoadState.Unavailable
                                    }
                                }
                                waitForRetry(retryDelayMillis)
                                retryDelayMillis = (retryDelayMillis * 2)
                                    .coerceAtMost(MAX_PHOTO_RETRY_DELAY_MILLIS)
                            }
                        }
                    }
                }
                is ItemPhotoRequest.AttachmentDisplay -> coroutineScope {
                    request.previewLocation?.let { previewLocation ->
                        launch {
                            thumbnails.cachedValue(previewLocation)?.let { preview ->
                                update { detailStateWithPreview(it, preview) }
                            }
                        }
                    }
                    launch {
                        val full = try {
                            PhotoLoadState.Available(attachmentDisplays.load(request.location))
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            PhotoLoadState.Unavailable
                        }
                        update { detailStateWithFullLoad(it, full) }
                    }
                }
            }
        }
        synchronized(requestLock) { activeRequests += requestJob }
        requestJob.invokeOnCompletion {
            synchronized(requestLock) { activeRequests -= requestJob }
            channel.close()
        }
        awaitClose { requestJob.cancel() }
    }

    fun initialState(request: ItemPhotoRequest): PhotoLoadState<T> {
        val previewLocation = when (request) {
            is ItemPhotoRequest.Stored -> request.previewLocation
            is ItemPhotoRequest.AttachmentDisplay -> request.previewLocation
        }
        return previewLocation
            ?.let(thumbnails::memoryValue)
            ?.let { PhotoLoadState.Available(it, PhotoResolution.Preview) }
            ?: when (request) {
                is ItemPhotoRequest.Stored -> if (
                    request.presentation == ItemPhotoPresentation.Compact
                ) {
                    thumbnails.memoryValue(request.location)
                        ?.let { PhotoLoadState.Available(it) }
                        ?: PhotoLoadState.Loading
                } else {
                    PhotoLoadState.Loading
                }
                is ItemPhotoRequest.AttachmentDisplay -> PhotoLoadState.Loading
            }
    }

    fun onItemPhotosChanged(thumbnailLocations: Set<String>) {
        val obsoleteLocations = synchronized(requestLock) {
            (observedThumbnailLocations - thumbnailLocations).also {
                observedThumbnailLocations = thumbnailLocations
            }
        }
        obsoleteLocations.forEach(thumbnails::remove)
    }

    fun prepareThumbnail(location: String, source: suspend () -> ByteArray) {
        thumbnails.prepare(location, source)
    }

    suspend fun prepareAttachmentDisplays(locations: List<String>) = coroutineScope {
        val jobs = locations.distinct().map { location ->
            launch { runCatching { attachmentDisplays.load(location) } }
        }
        synchronized(requestLock) { activeRequests += jobs }
        try {
            jobs.joinAll()
        } finally {
            synchronized(requestLock) { activeRequests -= jobs.toSet() }
        }
    }

    fun evictAttachmentDisplay(location: String) {
        attachmentDisplays.remove(location)
    }

    fun onSessionChanged(memberId: String?) {
        val (shouldClear, requestsToCancel) = synchronized(requestLock) {
            if (!sessionInitialized) {
                sessionInitialized = true
                this.memberId = memberId
                val persistedMemberId = sessionIdentityFile
                    ?.takeIf(File::isFile)
                    ?.let { runCatching(it::readText).getOrNull() }
                val clear = memberId == null ||
                    (sessionIdentityFile != null && persistedMemberId != memberId)
                clear to if (clear) activeRequests.toList() else emptyList()
            } else if (this.memberId == memberId) {
                false to emptyList()
            } else {
                this.memberId = memberId
                observedThumbnailLocations = emptySet()
                true to activeRequests.toList().also { activeRequests.clear() }
            }
        }
        if (shouldClear) {
            requestsToCancel.forEach(Job::cancel)
            thumbnails.clear()
            attachmentDisplays.clear()
        }
        sessionIdentityFile?.let { identityFile ->
            if (memberId == null) {
                identityFile.delete()
            } else {
                runCatching {
                    writeCacheFileAtomically(
                        identityFile,
                        memberId.encodeToByteArray(),
                        File::writeBytes,
                    )
                }
            }
        }
    }
}

internal class AttachmentDisplayPhotoCache<T>(
    private val directory: File,
    private val download: suspend (String) -> ByteArray,
    private val decode: suspend (ByteArray) -> T,
    private val writeTemporaryFile: (File, ByteArray) -> Unit = File::writeBytes,
) {
    private val commitLock = Any()

    @Volatile
    private var generation = 0L

    fun clear() {
        synchronized(commitLock) {
            generation += 1
            directory.listFiles().orEmpty().forEach(File::delete)
        }
    }

    fun remove(location: String) {
        synchronized(commitLock) {
            directory.resolve(attachmentDisplayCacheFileName(location)).delete()
        }
    }

    suspend fun load(location: String): T = withContext(Dispatchers.IO) {
        val requestGeneration = generation
        val cacheFile = directory.resolve(attachmentDisplayCacheFileName(location))
        val cached = readCached(cacheFile)
        if (cached != null) return@withContext cached

        val bytes = download(location)
        val decoded = decode(bytes)
        synchronized(commitLock) {
            if (requestGeneration != generation) {
                throw CancellationException("The Item Photo cache session changed")
            }
            runCatching { writeAtomically(cacheFile, bytes) }
        }
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
        writeCacheFileAtomically(cacheFile, bytes, writeTemporaryFile)
    }
}

private fun writeCacheFileAtomically(
    cacheFile: File,
    bytes: ByteArray,
    writeTemporaryFile: (File, ByteArray) -> Unit,
) {
    val directory = requireNotNull(cacheFile.parentFile)
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
internal const val INITIAL_PHOTO_RETRY_DELAY_MILLIS = 2_000L
internal const val MAX_PHOTO_RETRY_DELAY_MILLIS = 30_000L
