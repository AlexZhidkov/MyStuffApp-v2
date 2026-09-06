package com.azhidkov.mystuff.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun itemPhotoBitmapLoader(context: Context): ItemPhotoLoader<Bitmap> =
    ItemPhotoBitmapLoaderHolder.loader ?: synchronized(ItemPhotoBitmapLoaderHolder) {
        ItemPhotoBitmapLoaderHolder.loader ?: createItemPhotoBitmapLoader(
            context.applicationContext,
        ).also { ItemPhotoBitmapLoaderHolder.loader = it }
    }

internal fun prepareStoredPhotoThumbnail(context: Context, location: String, sourceUri: String) {
    itemPhotoBitmapLoader(context).prepareThumbnail(location) {
        requireNotNull(context.contentResolver.openInputStream(sourceUri.toUri())).use {
            it.readBytes()
        }
    }
}

private fun createItemPhotoBitmapLoader(context: Context): ItemPhotoLoader<Bitmap> =
    ItemPhotoLoader(
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
        attachmentDisplays = AttachmentDisplayPhotoCache(
            directory = File(context.cacheDir, ATTACHMENT_DISPLAY_CACHE_DIRECTORY),
            download = ::downloadAttachmentDisplayPhotoBytes,
            decode = ::decodeStoredPhotoBitmap,
        ),
        downloadStoredPhoto = ::downloadStoredPhotoBytes,
        decode = ::decodeStoredPhotoBitmap,
        sessionIdentityFile = File(context.cacheDir, ITEM_PHOTO_SESSION_IDENTITY_FILE),
    )

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

internal fun decodePhoto(source: ImageDecoder.Source): Bitmap =
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

private object ItemPhotoBitmapLoaderHolder {
    @Volatile
    var loader: ItemPhotoLoader<Bitmap>? = null
}

private const val MAX_DECODED_PHOTO_SIDE = 2_048
private const val THUMBNAIL_CACHE_DIRECTORY = "item-thumbnails"
private const val ATTACHMENT_DISPLAY_CACHE_DIRECTORY = "item-attachment-displays"
private const val ITEM_PHOTO_SESSION_IDENTITY_FILE = "item-photo-session-identity"
