package com.azhidkov.mystuff.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.azhidkov.mystuff.Item
import com.azhidkov.mystuff.ItemPhoto
import com.azhidkov.mystuff.R
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

internal fun showsPhotoPlaceholder(state: PhotoLoadState<*>): Boolean =
    state is PhotoLoadState.Unavailable

internal fun <T> detailStateWithPreview(
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

internal fun <T> detailStateWithFullLoad(
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

internal suspend fun <T> loadPhotoWithRetry(
    load: suspend () -> T,
    wait: suspend (Long) -> Unit,
    onState: (PhotoLoadState<T>) -> Unit,
) {
    var retryDelayMillis = INITIAL_PHOTO_RETRY_DELAY_MILLIS
    while (true) {
        onState(PhotoLoadState.Loading)
        try {
            onState(PhotoLoadState.Available(load()))
            return
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            onState(PhotoLoadState.Unavailable)
            wait(retryDelayMillis)
            retryDelayMillis =
                (retryDelayMillis * 2).coerceAtMost(MAX_PHOTO_RETRY_DELAY_MILLIS)
        }
    }
}

internal fun storedPhotoLocation(item: Item, presentation: ItemPhotoPresentation): String? =
    when (presentation) {
        ItemPhotoPresentation.Detail -> item.photoUrl
        ItemPhotoPresentation.Compact -> item.photoThumbnailUrl
    }

@Composable
internal fun rememberAttachmentDisplayPhoto(
    location: String,
    previewLocation: String? = null,
): State<PhotoLoadState<Bitmap>> {
    val context = LocalContext.current.applicationContext
    val loader = attachmentDisplayPhotoLoader(context)
    val previewLoader = storedPhotoBitmapLoader(context)
    return produceState<PhotoLoadState<Bitmap>>(
        initialValue = previewLocation
            ?.let(previewLoader::thumbnailMemoryValue)
            ?.let { PhotoLoadState.Available(it, PhotoResolution.Preview) }
            ?: PhotoLoadState.Loading,
        key1 = location,
        key2 = previewLocation,
    ) {
        if (previewLocation != null && value !is PhotoLoadState.Available) {
            launch {
                previewLoader.cachedThumbnailValue(previewLocation)?.let { preview ->
                    value = detailStateWithPreview(value, preview)
                }
            }
        }
        try {
            value = detailStateWithFullLoad(
                value,
                PhotoLoadState.Available(loader.load(location)),
            )
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            value = detailStateWithFullLoad(value, PhotoLoadState.Unavailable)
        }
    }
}

@Composable
internal fun LocalItemPhoto(
    photo: ItemPhoto,
    modifier: Modifier = Modifier,
) {
    val bitmap by rememberLocalPhotoBitmap(photo)
    PhotoBitmap(
        state = bitmap?.let { PhotoLoadState.Available(it) } ?: PhotoLoadState.Unavailable,
        modifier = modifier,
        placeholderSize = 64.dp,
    )
}

@Composable
internal fun StoredItemPhoto(
    item: Item,
    presentation: ItemPhotoPresentation,
    modifier: Modifier = Modifier,
) {
    val location = requireNotNull(storedPhotoLocation(item, presentation))
    val previewLocation = item.photoThumbnailUrl.takeIf {
        presentation == ItemPhotoPresentation.Detail
    }
    val state by rememberStoredPhotoBitmap(location, previewLocation, presentation)
    PhotoBitmap(
        state = state,
        modifier = modifier,
        placeholderSize = when (presentation) {
            ItemPhotoPresentation.Detail -> 64.dp
            ItemPhotoPresentation.Compact -> 32.dp
        },
    )
}

@Composable
private fun PhotoBitmap(
    state: PhotoLoadState<Bitmap>,
    modifier: Modifier,
    placeholderSize: Dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (state is PhotoLoadState.Available) {
            Crossfade(
                targetState = state,
                animationSpec = tween(FULL_PHOTO_CROSSFADE_MILLIS),
                label = "Item photo resolution",
            ) { available ->
                Image(
                    bitmap = available.value.asImageBitmap(),
                    contentDescription = stringResource(R.string.item_photo),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        } else if (showsPhotoPlaceholder(state)) {
            Icon(
                painter = painterResource(R.drawable.ic_photo),
                contentDescription = stringResource(R.string.item_photo_unavailable),
                modifier = Modifier.size(placeholderSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun rememberLocalPhotoBitmap(photo: ItemPhoto): State<Bitmap?> {
    val context = LocalContext.current
    return produceState(
        initialValue = null,
        key1 = photo,
    ) {
        value = runCatching { loadLocalPhotoBitmap(context, photo) }.getOrNull()
    }
}

@Composable
private fun rememberStoredPhotoBitmap(
    location: String,
    previewLocation: String?,
    presentation: ItemPhotoPresentation,
): State<PhotoLoadState<Bitmap>> {
    val context = LocalContext.current.applicationContext
    val loader = storedPhotoBitmapLoader(context)
    return key(location, previewLocation, presentation) {
        val initialValue = when (presentation) {
            ItemPhotoPresentation.Detail -> previewLocation
                ?.let(loader::thumbnailMemoryValue)
                ?.let { PhotoLoadState.Available(it, PhotoResolution.Preview) }
                ?: PhotoLoadState.Loading
            ItemPhotoPresentation.Compact -> loader.memoryValue(location, presentation)
                ?.let { PhotoLoadState.Available(it) }
                ?: PhotoLoadState.Loading
        }
        produceState(
            initialValue = initialValue,
            key1 = location,
            key2 = presentation,
        ) {
            if (presentation == ItemPhotoPresentation.Detail) {
                if (
                    previewLocation != null &&
                    value !is PhotoLoadState.Available
                ) {
                    launch {
                        loader.cachedThumbnailValue(previewLocation)?.let { preview ->
                            value = detailStateWithPreview(value, preview)
                        }
                    }
                }
                loadPhotoWithRetry(
                    load = { loader.load(location, presentation) },
                    wait = { delay(it) },
                    onState = { fullLoad ->
                        value = detailStateWithFullLoad(value, fullLoad)
                    },
                )
            } else if (value !is PhotoLoadState.Available) {
                loadPhotoWithRetry(
                    load = { loader.load(location, presentation) },
                    wait = { delay(it) },
                    onState = { value = it },
                )
            }
        }
    }
}

private suspend fun loadLocalPhotoBitmap(context: Context, photo: ItemPhoto): Bitmap =
    withContext(Dispatchers.IO) {
        decodePhoto(ImageDecoder.createSource(context.contentResolver, photo.uri.toUri()))
    }

private suspend fun downloadStoredPhotoBytes(location: String, maxBytes: Long): ByteArray =
    suspendCoroutine { continuation ->
        FirebaseStorage.getInstance()
            .getReferenceFromUrl(location)
            .getBytes(maxBytes)
            .addOnSuccessListener(continuation::resume)
            .addOnFailureListener(continuation::resumeWithException)
    }

private suspend fun downloadAttachmentDisplayPhotoBytes(location: String): ByteArray =
    suspendCoroutine { continuation ->
        FirebaseStorage.getInstance()
            .getReferenceFromUrl(location)
            .getBytes(Long.MAX_VALUE)
            .addOnSuccessListener(continuation::resume)
            .addOnFailureListener(continuation::resumeWithException)
    }

private suspend fun decodeStoredPhotoBitmap(bytes: ByteArray): Bitmap =
    withContext(Dispatchers.IO) {
        decodePhoto(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
    }

private fun storedPhotoBitmapLoader(context: Context): StoredPhotoLoader<Bitmap> =
    StoredPhotoBitmapLoaderHolder.loader ?: synchronized(StoredPhotoBitmapLoaderHolder) {
        StoredPhotoBitmapLoaderHolder.loader ?: StoredPhotoLoader(
            thumbnails = ThumbnailCache(
                directory = File(context.cacheDir, THUMBNAIL_CACHE_DIRECTORY),
                memory = SizedLruMemoryCache(
                    maxSizeBytes = thumbnailMemoryCacheMaxBytes(Runtime.getRuntime().maxMemory()),
                    sizeOf = Bitmap::getAllocationByteCount,
                ),
                download = { location ->
                    downloadStoredPhotoBytes(location, MAX_THUMBNAIL_DOWNLOAD_BYTES)
                },
                decode = ::decodeStoredPhotoBitmap,
            ),
            download = ::downloadStoredPhotoBytes,
            decode = ::decodeStoredPhotoBitmap,
        ).also { StoredPhotoBitmapLoaderHolder.loader = it }
    }

private object StoredPhotoBitmapLoaderHolder {
    @Volatile
    var loader: StoredPhotoLoader<Bitmap>? = null
}

private object AttachmentDisplayPhotoLoaderHolder {
    @Volatile
    var loader: AttachmentDisplayPhotoCache<Bitmap>? = null
}

internal fun attachmentDisplayPhotoLoader(context: Context): AttachmentDisplayPhotoCache<Bitmap> =
    AttachmentDisplayPhotoLoaderHolder.loader
        ?: synchronized(AttachmentDisplayPhotoLoaderHolder) {
            AttachmentDisplayPhotoLoaderHolder.loader
                ?: AttachmentDisplayPhotoCache(
                    directory = File(context.cacheDir, ATTACHMENT_DISPLAY_CACHE_DIRECTORY),
                    download = ::downloadAttachmentDisplayPhotoBytes,
                    decode = ::decodeStoredPhotoBitmap,
                ).also { AttachmentDisplayPhotoLoaderHolder.loader = it }
        }

private fun decodePhoto(source: ImageDecoder.Source): Bitmap =
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longestSide = max(info.size.width, info.size.height)
        if (longestSide > MAX_DECODED_PHOTO_SIDE) {
            val scale = MAX_DECODED_PHOTO_SIDE.toFloat() / longestSide
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt(),
                (info.size.height * scale).roundToInt(),
            )
        }
    }

private const val MAX_DECODED_PHOTO_SIDE = 2_048
private const val FULL_PHOTO_CROSSFADE_MILLIS = 200
private const val THUMBNAIL_CACHE_DIRECTORY = "item-thumbnails"
private const val ATTACHMENT_DISPLAY_CACHE_DIRECTORY = "item-attachment-displays"
private const val INITIAL_PHOTO_RETRY_DELAY_MILLIS = 2_000L
private const val MAX_PHOTO_RETRY_DELAY_MILLIS = 30_000L
