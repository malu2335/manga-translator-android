package com.manga.translate

import com.manga.translate.ocr.PaddleOcrInputSize
import com.manga.translate.ocr.resolvePaddleOcrInputSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PaddleOcrInputSizeTest {
    private val dynamicWidthShape = longArrayOf(-1, 3, 48, -1)

    @Test
    fun `short line keeps the existing padded input width`() {
        assertEquals(
            PaddleOcrInputSize(height = 48, width = 320, contentWidth = 192),
            resolvePaddleOcrInputSize(
                sourceWidth = 200f,
                sourceHeight = 50f,
                modelInputShape = dynamicWidthShape
            )
        )
    }

    @Test
    fun `long line expands dynamic model input without horizontal compression`() {
        assertEquals(
            PaddleOcrInputSize(height = 48, width = 928, contentWidth = 903),
            resolvePaddleOcrInputSize(
                sourceWidth = 940f,
                sourceHeight = 50f,
                modelInputShape = dynamicWidthShape
            )
        )
    }

    @Test
    fun `dynamic input width is bounded for pathological aspect ratios`() {
        assertEquals(
            PaddleOcrInputSize(height = 48, width = 2048, contentWidth = 2048),
            resolvePaddleOcrInputSize(
                sourceWidth = 10_000f,
                sourceHeight = 10f,
                modelInputShape = dynamicWidthShape
            )
        )
    }

    @Test
    fun `fixed width model retains its declared dimensions`() {
        assertEquals(
            PaddleOcrInputSize(height = 32, width = 256, contentWidth = 256),
            resolvePaddleOcrInputSize(
                sourceWidth = 940f,
                sourceHeight = 50f,
                modelInputShape = longArrayOf(1, 3, 32, 256)
            )
        )
    }
}
