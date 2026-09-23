package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.manga.translate.detection.PageRegion
import com.manga.translate.detection.detectWithinInsets
import com.manga.translate.model.BubbleSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetectionRegionTest {
    @Test fun croppedDetectionExcludesStatusBarAndRestoresCoordinates() = runBlocking {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        bitmap.setPixel(0, 0, Color.BLACK)
        var input: Bitmap? = null
        val result = detectWithinInsets(bitmap, 10, 20) { crop ->
            input = crop
            assertEquals(140, crop.height)
            assertEquals(Color.WHITE, crop.getPixel(0, 0))
            listOf(PageRegion(1, RectF(10f, 0f, 80f, 70f), BubbleSource.BUBBLE_DETECTOR,
                floatArrayOf(0.1f, 0f, 0.8f, 0.5f), listOf(RectF(10f, 5f, 80f, 20f))))
        }!!
        assertEquals(RectF(10f, 20f, 80f, 90f), result.single().rect)
        assertEquals(RectF(10f, 25f, 80f, 40f), result.single().textLineRects!!.single())
        assertArrayEquals(floatArrayOf(0.1f, 0.1f, 0.8f, 0.45f), result.single().maskContour, 0.0001f)
        assertTrue(input!!.isRecycled)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test fun rectangularCropRestoresBothAxesAndContours() = runBlocking {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val result = detectWithinInsets(bitmap, 10, 20, 20, 30) { crop ->
            assertEquals(100, crop.width)
            assertEquals(70, crop.height)
            listOf(PageRegion(1, RectF(0f, 0f, 100f, 70f), BubbleSource.BUBBLE_DETECTOR,
                floatArrayOf(0f, 0f, 1f, 1f), listOf(RectF(5f, 5f, 50f, 20f))))
        }!!.single()
        assertEquals(RectF(40f, 10f, 140f, 80f), result.rect)
        assertEquals(RectF(45f, 15f, 90f, 30f), result.textLineRects!!.single())
        assertArrayEquals(floatArrayOf(0.2f, 0.1f, 0.7f, 0.8f), result.maskContour, 0.0001f)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test fun fullScreenReusesSourceAndPreservesFailure() = runBlocking {
        val bitmap = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888)
        assertNull(detectWithinInsets(bitmap, 0, 0) { input ->
            assertSame(bitmap, input)
            null
        })
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test fun invalidInsetsKeepNonemptyCropAndReleaseItOnFailure() = runBlocking {
        val bitmap = Bitmap.createBitmap(10, 20, Bitmap.Config.ARGB_8888)
        var input: Bitmap? = null
        try {
            detectWithinInsets(bitmap, 100, 100) { crop ->
                input = crop
                assertEquals(2, crop.height)
                throw IllegalStateException("detector failure")
            }
            fail("Expected failure")
        } catch (_: IllegalStateException) {
            assertTrue(input!!.isRecycled)
            assertFalse(bitmap.isRecycled)
        } finally {
            bitmap.recycle()
        }
    }
}
