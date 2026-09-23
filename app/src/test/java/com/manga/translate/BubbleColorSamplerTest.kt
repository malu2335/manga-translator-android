package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Color
import com.manga.translate.rendering.BubbleColorSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleColorSamplerTest {
    private val background = Color.rgb(128, 96, 64)

    private fun page(): Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.BLUE)
        for (y in 20 until 80) for (x in 20 until 80) {
            setPixel(x, y, when {
                x == 20 || x == 79 || y == 20 || y == 79 -> Color.BLACK
                x in 30..69 && y in 30..69 -> Color.WHITE
                else -> background
            })
        }
    }

    @Test fun `ordinary bubble samples inside border and ignores center lettering`() {
        assertEquals(background, BubbleColorSampler.sampleBackgroundColor(page(), 20f, 20f, 80f, 80f))
    }

    @Test fun `free bubble samples outside text box`() {
        assertEquals(Color.BLUE, BubbleColorSampler.sampleBackgroundColor(page(), 20f, 20f, 80f, 80f, outside = true))
    }

    @Test fun `dark ring is preserved without brightness filtering`() {
        val bitmap = page()
        for (y in 21..78) for (x in 21..78) bitmap.setPixel(x, y, Color.BLACK)
        assertEquals(Color.BLACK, BubbleColorSampler.sampleBackgroundColor(bitmap, 20f, 20f, 80f, 80f))
    }

    @Test fun `outer ring skips portions beyond page edge`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            for (y in 0..59) for (x in 0..59) setPixel(x, y, Color.WHITE)
        }
        assertEquals(Color.BLUE, BubbleColorSampler.sampleBackgroundColor(bitmap, 0f, 0f, 60f, 60f, outside = true))
        assertNull(BubbleColorSampler.sampleBackgroundColor(bitmap, 0f, 0f, 100f, 100f, outside = true))
    }

    @Test fun `contour follows sloping bubble instead of rectangular corners in either winding`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            for (y in 0 until 100) for (x in 0 until 100) {
                val distance = kotlin.math.abs(x - 50) + kotlin.math.abs(y - 50)
                if (distance < 30) setPixel(x, y, if (distance < 15) Color.BLACK else background)
            }
        }
        val contour = floatArrayOf(50f, 20f, 80f, 50f, 50f, 80f, 20f, 50f)
        assertEquals(background, BubbleColorSampler.sampleBackgroundColor(bitmap, 20f, 20f, 80f, 80f, contour = contour))
        val reversed = contour.toList().chunked(2).reversed().flatten().toFloatArray()
        assertEquals(background, BubbleColorSampler.sampleBackgroundColor(bitmap, 20f, 20f, 80f, 80f, contour = reversed))
    }

    @Test fun `scaled bitmap uses source coordinates`() {
        assertEquals(background, BubbleColorSampler.sampleBackgroundColor(page(), null, 200, 200, 40f, 40f, 160f, 160f))
    }

    @Test fun `invalid and tiny regions are safe`() {
        assertNull(BubbleColorSampler.sampleBackgroundColor(page(), 80f, 20f, 20f, 80f))
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(background) }
        assertEquals(background, BubbleColorSampler.sampleBackgroundColor(bitmap, 0f, 0f, 1f, 1f))
    }
}
