package com.azhidkov.mystuff.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun ZoomablePhoto(
    bitmap: Bitmap,
    contentDescription: String,
    active: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(bitmap) { mutableFloatStateOf(MIN_PHOTO_ZOOM) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(bitmap, active) {
        zoom = MIN_PHOTO_ZOOM
        offset = Offset.Zero
        onZoomChanged(false)
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                viewportSize = size
                offset = offset.coerceIn(
                    photoZoomOffsetBounds(
                        imageSize = IntSize(bitmap.width, bitmap.height),
                        viewportSize = size,
                        zoom = zoom,
                    ),
                )
            }
            .pointerInput(bitmap) {
                var previousTapUptimeMillis: Long? = null
                var previousTapPosition = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var movedBeyondTouchSlop = false
                    var usedMultiplePointers = false
                    // At 1x, leave one-finger drags to the carousel. A pinch claims the gesture;
                    // once zoomed, subsequent one-finger gestures pan the photo instead.
                    var isTransformGesture = zoom > MIN_PHOTO_ZOOM
                    lateinit var event: PointerEvent
                    do {
                        event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) {
                            usedMultiplePointers = true
                            isTransformGesture = true
                        }
                        event.changes
                            .firstOrNull { it.id == down.id }
                            ?.let { change ->
                                if (
                                    (change.position - down.position).getDistance() >
                                    viewConfiguration.touchSlop
                                ) {
                                    movedBeyondTouchSlop = true
                                }
                            }
                        if (isTransformGesture) {
                            val previousZoom = zoom
                            val nextZoom = (previousZoom * event.calculateZoom())
                                .coerceIn(
                                    MIN_PHOTO_ZOOM,
                                    photoMaximumZoom(
                                        imageSize = IntSize(bitmap.width, bitmap.height),
                                        viewportSize = viewportSize,
                                    ),
                                )
                            val zoomFactor = nextZoom / previousZoom
                            val viewportCenter = Offset(
                                viewportSize.width / 2f,
                                viewportSize.height / 2f,
                            )
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val focalAdjustment = if (
                                centroid.x.isFinite() && centroid.y.isFinite()
                            ) {
                                (centroid - viewportCenter - offset) * (1f - zoomFactor)
                            } else {
                                Offset.Zero
                            }
                            val nextOffset = offset + event.calculatePan() + focalAdjustment
                            val wasZoomed = previousZoom > MIN_PHOTO_ZOOM

                            zoom = nextZoom
                            offset = nextOffset.coerceIn(
                                photoZoomOffsetBounds(
                                    imageSize = IntSize(bitmap.width, bitmap.height),
                                    viewportSize = viewportSize,
                                    zoom = nextZoom,
                                ),
                            )
                            val isZoomed = nextZoom > MIN_PHOTO_ZOOM
                            if (isZoomed != wasZoomed) onZoomChanged(isZoomed)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    val up = event.changes.firstOrNull { it.id == down.id }
                    val isTap = up != null &&
                        !usedMultiplePointers &&
                        !movedBeyondTouchSlop &&
                        up.uptimeMillis - down.uptimeMillis <
                        viewConfiguration.longPressTimeoutMillis
                    if (isTap) {
                        val earlierTapUptimeMillis = previousTapUptimeMillis
                        val timeSinceEarlierTap = earlierTapUptimeMillis?.let {
                            up.uptimeMillis - it
                        }
                        val isDoubleTap = timeSinceEarlierTap != null &&
                            timeSinceEarlierTap >= viewConfiguration.doubleTapMinTimeMillis &&
                            timeSinceEarlierTap <= viewConfiguration.doubleTapTimeoutMillis &&
                            (up.position - previousTapPosition).getDistance() <=
                            viewConfiguration.touchSlop * DOUBLE_TAP_SLOP_MULTIPLIER
                        if (isDoubleTap) {
                            val previousZoom = zoom
                            val nextZoom = photoZoomAfterDoubleTap(
                                currentZoom = previousZoom,
                                imageSize = IntSize(bitmap.width, bitmap.height),
                                viewportSize = viewportSize,
                            )
                            val nextOffset = if (nextZoom == MIN_PHOTO_ZOOM) {
                                Offset.Zero
                            } else {
                                val viewportCenter = Offset(
                                    viewportSize.width / 2f,
                                    viewportSize.height / 2f,
                                )
                                offset +
                                    (up.position - viewportCenter - offset) *
                                    (1f - nextZoom / previousZoom)
                            }

                            zoom = nextZoom
                            offset = nextOffset.coerceIn(
                                photoZoomOffsetBounds(
                                    imageSize = IntSize(bitmap.width, bitmap.height),
                                    viewportSize = viewportSize,
                                    zoom = nextZoom,
                                ),
                            )
                            onZoomChanged(nextZoom > MIN_PHOTO_ZOOM)
                            previousTapUptimeMillis = null
                        } else {
                            previousTapUptimeMillis = up.uptimeMillis
                            previousTapPosition = up.position
                        }
                    } else {
                        previousTapUptimeMillis = null
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
        )
    }
}

internal fun photoZoomOffsetBounds(
    imageSize: IntSize,
    viewportSize: IntSize,
    zoom: Float,
): Offset {
    if (viewportSize == IntSize.Zero || imageSize.width <= 0 || imageSize.height <= 0) {
        return Offset.Zero
    }
    val fittedScale = min(
        viewportSize.width.toFloat() / imageSize.width,
        viewportSize.height.toFloat() / imageSize.height,
    )
    return Offset(
        x = ((imageSize.width * fittedScale * zoom - viewportSize.width) / 2f)
            .coerceAtLeast(0f),
        y = ((imageSize.height * fittedScale * zoom - viewportSize.height) / 2f)
            .coerceAtLeast(0f),
    )
}

internal fun photoFullSizeZoom(imageSize: IntSize, viewportSize: IntSize): Float {
    if (viewportSize == IntSize.Zero || imageSize.width <= 0 || imageSize.height <= 0) {
        return MIN_PHOTO_ZOOM
    }
    val fittedScale = min(
        viewportSize.width.toFloat() / imageSize.width,
        viewportSize.height.toFloat() / imageSize.height,
    )
    return (1f / fittedScale).coerceAtLeast(MIN_PHOTO_ZOOM)
}

internal fun photoZoomAfterDoubleTap(
    currentZoom: Float,
    imageSize: IntSize,
    viewportSize: IntSize,
): Float = if (currentZoom > MIN_PHOTO_ZOOM) {
    MIN_PHOTO_ZOOM
} else {
    photoFullSizeZoom(imageSize, viewportSize)
}

private fun photoMaximumZoom(imageSize: IntSize, viewportSize: IntSize): Float = max(
    DEFAULT_MAX_PHOTO_ZOOM,
    photoFullSizeZoom(imageSize, viewportSize),
)

private fun Offset.coerceIn(bounds: Offset): Offset = Offset(
    x = x.coerceIn(-bounds.x, bounds.x),
    y = y.coerceIn(-bounds.y, bounds.y),
)

private const val MIN_PHOTO_ZOOM = 1f
private const val DEFAULT_MAX_PHOTO_ZOOM = 5f
private const val DOUBLE_TAP_SLOP_MULTIPLIER = 4f
