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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun showsPhotoPlaceholder(state: PhotoLoadState<*>): Boolean =
    state is PhotoLoadState.Unavailable

@Composable
internal fun rememberAttachmentDisplayPhoto(
    location: String,
    previewLocation: String? = null,
): State<PhotoLoadState<Bitmap>> {
    val context = LocalContext.current.applicationContext
    val loader = itemPhotoBitmapLoader(context)
    val request = remember(location, previewLocation) {
        ItemPhotoRequest.AttachmentDisplay(location, previewLocation)
    }
    val states = remember(loader, request) { loader.states(request) }
    return states.collectAsState(initial = loader.initialState(request))
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
    val loader = itemPhotoBitmapLoader(context)
    val request = remember(location, previewLocation, presentation) {
        ItemPhotoRequest.Stored(location, presentation, previewLocation)
    }
    val states = remember(loader, request) { loader.states(request) }
    return states.collectAsState(initial = loader.initialState(request))
}

private suspend fun loadLocalPhotoBitmap(context: Context, photo: ItemPhoto): Bitmap =
    withContext(Dispatchers.IO) {
        decodePhoto(ImageDecoder.createSource(context.contentResolver, photo.uri.toUri()))
    }

private const val FULL_PHOTO_CROSSFADE_MILLIS = 200
