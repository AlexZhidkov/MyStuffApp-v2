@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.azhidkov.mystuff.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.net.toUri
import com.azhidkov.mystuff.InventoryActions
import com.azhidkov.mystuff.ItemPhoto
import com.azhidkov.mystuff.R
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CropPhotoScreen(
    photo: ItemPhoto,
    processingPurpose: PhotoProcessingPurpose = PhotoProcessingPurpose.ItemPhoto,
    actions: InventoryActions,
) {
    val context = LocalContext.current
    val bitmap by rememberLocalPhotoBitmap(photo)
    val scope = rememberCoroutineScope()
    var cropSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var cropAspectRatio by remember(bitmap) {
        mutableFloatStateOf(
            if (processingPurpose == PhotoProcessingPurpose.ItemAttachment) {
                bitmap?.let { it.width.toFloat() / it.height }
                    ?.coerceIn(MIN_CROP_ASPECT_RATIO, MAX_CROP_ASPECT_RATIO)
                    ?: 1f
            } else {
                1f
            },
        )
    }
    var cropping by remember { mutableStateOf(false) }
    LaunchedEffect(photo) { cropping = false }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.crop_photo)) },
                navigationIcon = {
                    TextButton(onClick = actions::closeItemForm, enabled = !cropping) {
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
                        .aspectRatio(
                            if (processingPurpose == PhotoProcessingPurpose.ItemAttachment) {
                                cropAspectRatio
                            } else {
                                1f
                            },
                        )
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
                if (processingPurpose == PhotoProcessingPurpose.ItemAttachment) {
                    Text(
                        text = stringResource(R.string.crop_aspect_ratio),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = cropAspectRatio,
                        onValueChange = {
                            cropAspectRatio = it
                            offset = Offset.Zero
                        },
                        valueRange = MIN_CROP_ASPECT_RATIO..MAX_CROP_ASPECT_RATIO,
                    )
                }
                Button(
                    onClick = {
                        cropping = true
                        scope.launch {
                            runCatching {
                                cropAndStorePhoto(
                                    context = context,
                                    bitmap = loadedBitmap,
                                    size = cropSize,
                                    zoom = zoom,
                                    offset = offset,
                                    purpose = processingPurpose,
                                )
                            }.onSuccess { photo ->
                                actions.useCroppedPhoto(photo)
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
                    if (processingPurpose == PhotoProcessingPurpose.ItemAttachment) TextButton(
                        onClick = {
                            cropping = true
                            scope.launch {
                                runCatching {
                                    processPhotoWithoutCropping(
                                        context = context,
                                        source = photo,
                                        purpose = processingPurpose,
                                    )
                                }.onSuccess { processed ->
                                    actions.usePhotoWithoutCropping(processed)
                                }.onFailure {
                                    cropping = false
                                }
                            }
                        },
                        enabled = !cropping,
                    ) {
                        Text(stringResource(R.string.use_original_photo))
                    }
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
    purpose: PhotoProcessingPurpose,
): ItemPhoto = withContext(Dispatchers.IO) {
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
    val files = try {
        ItemPhotoProcessor.writeVariants(
            crop = cropped,
            directory = File(context.filesDir, "item-photos"),
            purpose = purpose,
        )
    } finally {
        cropped.recycle()
    }
    itemPhotoFromFiles(context, files)
}

private fun itemPhotoFromFiles(context: Context, files: ItemPhotoFiles): ItemPhoto = ItemPhoto(
    uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        files.full,
    ).toString(),
    thumbnailUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.files",
        files.thumbnail,
    ).toString(),
)

private suspend fun processPhotoWithoutCropping(
    context: Context,
    source: ItemPhoto,
    purpose: PhotoProcessingPurpose,
): ItemPhoto = withContext(Dispatchers.IO) {
    val bitmap = ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(context.contentResolver, source.uri.toUri()),
    ) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
    val files = try {
        ItemPhotoProcessor.writeVariants(
            crop = bitmap,
            directory = File(context.filesDir, "item-photos"),
            purpose = purpose,
        )
    } finally {
        bitmap.recycle()
    }
    itemPhotoFromFiles(context, files)
}

private data class CropGeometry(
    val displayedScale: Float,
    val left: Float,
    val top: Float,
    val offsetBounds: Offset,
)

private const val MIN_CROP_ASPECT_RATIO = 0.1f
private const val MAX_CROP_ASPECT_RATIO = 10f
