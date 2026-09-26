package com.manga.translate.floating

import android.graphics.Bitmap
import android.graphics.Color

/** A heuristic only: MediaProjection does not expose another app's FLAG_SECURE. */
internal object CaptureContentGuard {
    fun isProbablyProtected(bitmap: Bitmap): Boolean {
        // Ignore system bars, which can remain visible above a protected black window.
        val left = bitmap.width / 20
        val top = bitmap.height / 10
        val right = (bitmap.width - left).coerceAtLeast(left + 1)
        val bottom = (bitmap.height - top).coerceAtLeast(top + 1)
        var visible = 0
        var total = 0
        for (y in top until bottom step ((bottom - top) / 160).coerceAtLeast(1)) {
            for (x in left until right step ((right - left) / 100).coerceAtLeast(1)) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 8 &&
                    maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 8
                ) visible++
                total++
            }
        }
        return visible * 1000L <= total // At least 99.9% black/transparent.
    }
}
