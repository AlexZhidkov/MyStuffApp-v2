package com.azhidkov.mystuff.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.azhidkov.mystuff.Item
import com.azhidkov.mystuff.ItemPhoto
import com.azhidkov.mystuff.R
import com.google.firebase.storage.FirebaseStorage
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal enum class ItemPhotoPresentation {
    Detail,
    Compact,
}

internal fun storedPhotoLocation(item: Item, presentation: ItemPhotoPresentation): String? =
    when (presentation) {
        ItemPhotoPresentation.Detail -> item.photoUrl
        ItemPhotoPresentation.Compact -> item.photoThumbnailUrl
    }

@Composable
internal fun LocalItemPhoto(
    photo: ItemPhoto,
    modifier: Modifier = Modifier,
) {
    val bitmap by rememberLocalPhotoBitmap(photo)
    PhotoBitmap(bitmap, modifier)
}

@Composable
internal fun StoredItemPhoto(
    item: Item,
    presentation: ItemPhotoPresentation,
    modifier: Modifier = Modifier,
) {
    val location = requireNotNull(storedPhotoLocation(item, presentation))
    val bitmap by rememberStoredPhotoBitmap(location, presentation)
    PhotoBitmap(bitmap, modifier)
}

@Composable
private fun PhotoBitmap(
    bitmap: Bitmap?,
    modifier: Modifier,
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
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = stringResource(R.string.item_photo),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } ?: Text(
            text = stringResource(R.string.item_photo_unavailable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    presentation: ItemPhotoPresentation,
): State<Bitmap?> = produceState(
    initialValue = null,
    key1 = location,
    key2 = presentation,
) {
    var retryDelayMillis = INITIAL_PHOTO_RETRY_DELAY_MILLIS
    while (value == null) {
        value = runCatching {
            loadStoredPhotoBitmap(
                location = location,
                maxBytes = when (presentation) {
                    ItemPhotoPresentation.Detail -> MAX_FULL_PHOTO_DOWNLOAD_BYTES
                    ItemPhotoPresentation.Compact -> MAX_THUMBNAIL_DOWNLOAD_BYTES
                },
            )
        }.getOrNull()
        if (value == null) {
            delay(retryDelayMillis)
            retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_PHOTO_RETRY_DELAY_MILLIS)
        }
    }
}

private suspend fun loadLocalPhotoBitmap(context: Context, photo: ItemPhoto): Bitmap =
    withContext(Dispatchers.IO) {
        decodePhoto(ImageDecoder.createSource(context.contentResolver, photo.uri.toUri()))
    }

private suspend fun loadStoredPhotoBitmap(location: String, maxBytes: Long): Bitmap {
    val bytes = suspendCoroutine<ByteArray> { continuation ->
        FirebaseStorage.getInstance()
            .getReferenceFromUrl(location)
            .getBytes(maxBytes)
            .addOnSuccessListener(continuation::resume)
            .addOnFailureListener(continuation::resumeWithException)
    }
    return withContext(Dispatchers.IO) {
        decodePhoto(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
    }
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
private const val MAX_FULL_PHOTO_DOWNLOAD_BYTES = 2L * 1024 * 1024
private const val MAX_THUMBNAIL_DOWNLOAD_BYTES = 256L * 1024
private const val INITIAL_PHOTO_RETRY_DELAY_MILLIS = 2_000L
private const val MAX_PHOTO_RETRY_DELAY_MILLIS = 30_000L
