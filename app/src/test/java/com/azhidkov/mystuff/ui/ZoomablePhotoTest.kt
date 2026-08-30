package com.azhidkov.mystuff.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomablePhotoTest {

    @Test
    fun `fitted image cannot be panned at normal scale`() {
        assertEquals(
            Offset.Zero,
            photoZoomOffsetBounds(
                imageSize = IntSize(2_000, 1_000),
                viewportSize = IntSize(1_000, 1_000),
                zoom = 1f,
            ),
        )
    }

    @Test
    fun `zoomed image can only pan across pixels beyond the viewport`() {
        assertEquals(
            Offset(500f, 0f),
            photoZoomOffsetBounds(
                imageSize = IntSize(2_000, 1_000),
                viewportSize = IntSize(1_000, 1_000),
                zoom = 2f,
            ),
        )
    }

    @Test
    fun `portrait image has vertical pan bounds`() {
        assertEquals(
            Offset(0f, 500f),
            photoZoomOffsetBounds(
                imageSize = IntSize(1_000, 2_000),
                viewportSize = IntSize(1_000, 1_000),
                zoom = 2f,
            ),
        )
    }

    @Test
    fun `full size zoom displays one image pixel per viewport pixel`() {
        assertEquals(
            2f,
            photoFullSizeZoom(
                imageSize = IntSize(2_000, 1_000),
                viewportSize = IntSize(1_000, 1_000),
            ),
        )
    }

    @Test
    fun `full size zoom does not shrink an image already enlarged to fit`() {
        assertEquals(
            1f,
            photoFullSizeZoom(
                imageSize = IntSize(500, 500),
                viewportSize = IntSize(1_000, 1_000),
            ),
        )
    }

    @Test
    fun `double tap at normal scale zooms to full size`() {
        assertEquals(
            2f,
            photoZoomAfterDoubleTap(
                currentZoom = 1f,
                imageSize = IntSize(2_000, 1_000),
                viewportSize = IntSize(1_000, 1_000),
            ),
        )
    }

    @Test
    fun `double tap while zoomed returns to normal scale`() {
        assertEquals(
            1f,
            photoZoomAfterDoubleTap(
                currentZoom = 3f,
                imageSize = IntSize(2_000, 1_000),
                viewportSize = IntSize(1_000, 1_000),
            ),
        )
    }
}
