package com.azhidkov.mystuff.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.withContext

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
    location: String,
    modifier: Modifier = Modifier,
) {
    val bitmap by rememberStoredPhotoBitmap(location)
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
        } ?: CircularProgressIndicator(Modifier.size(28.dp))
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
private fun rememberStoredPhotoBitmap(location: String): State<Bitmap?> = produceState(
    initialValue = null,
    key1 = location,
) {
    value = runCatching { loadStoredPhotoBitmap(location) }.getOrNull()
}

private suspend fun loadLocalPhotoBitmap(context: Context, photo: ItemPhoto): Bitmap =
    withContext(Dispatchers.IO) {
        decodePhoto(ImageDecoder.createSource(context.contentResolver, photo.uri.toUri()))
    }

private suspend fun loadStoredPhotoBitmap(location: String): Bitmap {
    val bytes = suspendCoroutine<ByteArray> { continuation ->
        FirebaseStorage.getInstance()
            .getReferenceFromUrl(location)
            .getBytes(MAX_PHOTO_DOWNLOAD_BYTES)
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
private const val MAX_PHOTO_DOWNLOAD_BYTES = 10L * 1024 * 1024
