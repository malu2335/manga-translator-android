package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Color
import com.manga.translate.floating.CaptureContentGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureContentGuardTest {
    private fun frame(color: Int) = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
    }

    @Test fun blackContentWithVisibleSystemBarsIsBlocked() {
        val bitmap = frame(Color.BLACK)
        for (y in 0 until 30) for (x in 0 until 200) bitmap.setPixel(x, y, Color.WHITE)
        for (y in 375 until 400) for (x in 0 until 200) bitmap.setPixel(x, y, Color.WHITE)
        assertTrue(CaptureContentGuard.isProbablyProtected(bitmap))
    }

    @Test fun transparentContentIsBlocked() {
        assertTrue(CaptureContentGuard.isProbablyProtected(frame(Color.TRANSPARENT)))
    }

    @Test fun normalLightAndDarkPagesAreAllowed() {
        assertFalse(CaptureContentGuard.isProbablyProtected(frame(Color.WHITE)))
        assertFalse(CaptureContentGuard.isProbablyProtected(frame(Color.rgb(24, 24, 24))))
    }

    @Test fun textOnBlackBackgroundIsAllowed() {
        val bitmap = frame(Color.BLACK)
        for (y in 180 until 190) for (x in 60 until 140) bitmap.setPixel(x, y, Color.WHITE)
        assertFalse(CaptureContentGuard.isProbablyProtected(bitmap))
    }
}
