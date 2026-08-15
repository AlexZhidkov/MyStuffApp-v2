@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.azhidkov.mystuff.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import com.azhidkov.mystuff.InventoryActions
import com.azhidkov.mystuff.ItemPhoto
import com.azhidkov.mystuff.R
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CropPhotoScreen(
    photo: ItemPhoto,
    actions: InventoryActions,
) {
    val context = LocalContext.current
    val bitmap by rememberLocalPhotoBitmap(photo)
    val scope = rememberCoroutineScope()
    var cropSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var cropping by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.crop_photo)) },
                navigationIcon = {
                    TextButton(onClick = actions::cancelAddItem, enabled = !cropping) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.crop_photo_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            bitmap?.let { loadedBitmap ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .onSizeChanged { cropSize = it }
                        .pointerInput(loadedBitmap, cropSize) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                val nextZoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                                val bounds = cropOffsetBounds(loadedBitmap, cropSize, nextZoom)
                                zoom = nextZoom
                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-bounds.x, bounds.x),
                                    y = (offset.y + pan.y).coerceIn(-bounds.y, bounds.y),
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = loadedBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.item_photo),
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = offset.x
                                translationY = offset.y
                                clip = true
                            },
                        contentScale = ContentScale.Crop,
                    )
                }
                Button(
                    onClick = {
                        cropping = true
                        scope.launch {
                            runCatching {
                                cropAndStorePhoto(context, loadedBitmap, cropSize, zoom, offset)
                            }.onSuccess { uri ->
                                actions.useCroppedPhoto(ItemPhoto(uri.toString()))
                            }.onFailure {
                                cropping = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !cropping && cropSize != IntSize.Zero,
                ) {
                    Text(
                        stringResource(
                            if (cropping) R.string.cropping_photo else R.string.use_photo,
                        ),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = actions::retakePhoto, enabled = !cropping) {
                        Text(stringResource(R.string.retake_photo))
                    }
                    TextButton(onClick = actions::continueWithoutPhoto, enabled = !cropping) {
                        Text(stringResource(R.string.continue_without_photo))
                    }
                }
            } ?: Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

private fun cropOffsetBounds(bitmap: Bitmap, size: IntSize, zoom: Float): Offset {
    if (size == IntSize.Zero) return Offset.Zero
    return cropGeometry(bitmap, size, zoom, Offset.Zero).offsetBounds
}

private fun cropGeometry(
    bitmap: Bitmap,
    size: IntSize,
    zoom: Float,
    offset: Offset,
): CropGeometry {
    val baseScale = max(
        size.width.toFloat() / bitmap.width,
        size.height.toFloat() / bitmap.height,
    )
    val displayedScale = baseScale * zoom
    val displayedWidth = bitmap.width * displayedScale
    val displayedHeight = bitmap.height * displayedScale
    return CropGeometry(
        displayedScale = displayedScale,
        left = (size.width - displayedWidth) / 2f + offset.x,
        top = (size.height - displayedHeight) / 2f + offset.y,
        offsetBounds = Offset(
            x = ((displayedWidth - size.width) / 2f).coerceAtLeast(0f),
            y = ((displayedHeight - size.height) / 2f).coerceAtLeast(0f),
        ),
    )
}

private suspend fun cropAndStorePhoto(
    context: Context,
    bitmap: Bitmap,
    size: IntSize,
    zoom: Float,
    offset: Offset,
): Uri = withContext(Dispatchers.IO) {
    require(size != IntSize.Zero)
    val geometry = cropGeometry(bitmap, size, zoom, offset)
    val sourceX = (-geometry.left / geometry.displayedScale)
        .roundToInt()
        .coerceIn(0, bitmap.width - 1)
    val sourceY = (-geometry.top / geometry.displayedScale)
        .roundToInt()
        .coerceIn(0, bitmap.height - 1)
    val sourceWidth = (size.width / geometry.displayedScale).roundToInt()
        .coerceIn(1, bitmap.width - sourceX)
    val sourceHeight = (size.height / geometry.displayedScale).roundToInt()
        .coerceIn(1, bitmap.height - sourceY)
    val cropped = Bitmap.createBitmap(bitmap, sourceX, sourceY, sourceWidth, sourceHeight)
    val output = if (max(cropped.width, cropped.height) > MAX_STORED_PHOTO_SIDE) {
        cropped.scale(MAX_STORED_PHOTO_SIDE, MAX_STORED_PHOTO_SIDE)
    } else {
        cropped
    }
    val directory = File(context.cacheDir, "item-photos").apply { mkdirs() }
    val file = File(directory, "cropped-${UUID.randomUUID()}.jpg")
    file.outputStream().use { stream ->
        check(output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream))
    }
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

private data class CropGeometry(
    val displayedScale: Float,
    val left: Float,
    val top: Float,
    val offsetBounds: Offset,
)

private const val MAX_STORED_PHOTO_SIDE = 1_600
private const val JPEG_QUALITY = 88
