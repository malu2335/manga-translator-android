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

    private fun contaminatedBubble(fill: Int, ink: Int): Bitmap =
        Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(fill)
            // A thick left border and lettering touching the top sampling ring.
            for (y in 20 until 80) for (x in 20..25) setPixel(x, y, ink)
            for (y in 21..26) for (x in 40..55) setPixel(x, y, Color.rgb(128, 128, 128))
        }

    @Test fun `minority border and antialiased lettering do not gray a white bubble`() {
        val bitmap = contaminatedBubble(Color.WHITE, Color.BLACK)
        val contour = floatArrayOf(20f, 20f, 80f, 20f, 80f, 80f, 20f, 80f)
        assertEquals(Color.WHITE, BubbleColorSampler.sampleBackgroundColor(bitmap, 20f, 20f, 80f, 80f))
        assertEquals(Color.WHITE, BubbleColorSampler.sampleBackgroundColor(bitmap, 20f, 20f, 80f, 80f, contour = contour))
    }

    @Test fun `isolated black pixels do not tint white sampling ring`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setPixel(25, 23, Color.BLACK)
            setPixel(76, 40, Color.BLACK)
            setPixel(55, 76, Color.BLACK)
        }
        assertEquals(Color.WHITE, BubbleColorSampler.sampleBackgroundColor(bitmap, 20f, 20f, 80f, 80f))
    }

    @Test fun `rejecting minority ink preserves gray off white colored and black fills`() {
        for (fill in listOf(Color.rgb(180, 180, 180), Color.rgb(248, 246, 240), background, Color.BLACK)) {
            val bitmap = contaminatedBubble(fill, if (fill == Color.BLACK) Color.WHITE else Color.BLACK)
            assertEquals(fill, BubbleColorSampler.sampleBackgroundColor(bitmap, 20f, 20f, 80f, 80f))
        }
    }

    @Test fun `scaled bitmap rejects border contamination in source coordinates`() {
        val bitmap = contaminatedBubble(Color.WHITE, Color.BLACK)
        assertEquals(Color.WHITE, BubbleColorSampler.sampleBackgroundColor(
            bitmap, null, 200, 200, 40f, 40f, 160f, 160f
        ))
    }

    @Test fun `free text keeps averaging multicolor surroundings`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 0 until 100) for (x in 0 until 18) setPixel(x, y, Color.BLACK)
        }
        assertEquals(Color.rgb(191, 191, 191), BubbleColorSampler.sampleBackgroundColor(
            bitmap, 20f, 20f, 80f, 80f, outside = true
        ))
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
