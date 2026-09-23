package com.manga.translate.rendering

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import com.manga.translate.platform.ImageFileSupport
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

internal object BubbleColorSampler {
    private const val FILE_SAMPLE_MAX_EDGE = 192

    fun sampleBackgroundColor(
        bitmap: Bitmap?, left: Float, top: Float, right: Float, bottom: Float,
        outside: Boolean = false, contour: FloatArray? = null
    ): Int? = sampleBackgroundColor(
        bitmap, null, bitmap?.width ?: 0, bitmap?.height ?: 0,
        left, top, right, bottom, outside, contour
    )

    fun sampleBackgroundColor(bitmap: Bitmap?, rect: RectF): Int? =
        sampleBackgroundColor(bitmap, rect.left, rect.top, rect.right, rect.bottom)

    /** Coordinates (including optional contour points) are in source-image pixels. */
    fun sampleBackgroundColor(
        bitmap: Bitmap?, imageFile: File?, sourceWidth: Int, sourceHeight: Int,
        left: Float, top: Float, right: Float, bottom: Float,
        outside: Boolean = false, contour: FloatArray? = null
    ): Int? {
        if (bitmap == null) return sampleBackgroundColorFromFile(
            imageFile, sourceWidth, sourceHeight, left, top, right, bottom, outside, contour
        )
        val width = sourceWidth.takeIf { it > 0 } ?: bitmap.width
        val height = sourceHeight.takeIf { it > 0 } ?: bitmap.height
        val points = ringPoints(left, top, right, bottom, outside, contour)
            .filter { (x, y) -> x >= 0 && y >= 0 && x < width && y < height }
        return average(bitmap, points, 0f, 0f, bitmap.width.toFloat() / width, bitmap.height.toFloat() / height)
    }

    fun sampleBackgroundColorFromFile(
        imageFile: File?, sourceWidth: Int, sourceHeight: Int,
        left: Float, top: Float, right: Float, bottom: Float,
        outside: Boolean = false, contour: FloatArray? = null
    ): Int? {
        val file = imageFile?.takeIf { it.isFile && !ImageFileSupport.isAvifFile(it.name) } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (sourceWidth <= 0 || sourceHeight <= 0) BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = sourceWidth.takeIf { it > 0 } ?: bounds.outWidth
        val height = sourceHeight.takeIf { it > 0 } ?: bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val points = ringPoints(left, top, right, bottom, outside, contour)
            .filter { (x, y) -> x >= 0 && y >= 0 && x < width && y < height }
        if (points.isEmpty()) return null
        // Include the outer ring in the decoded region; do not clamp it onto the text box.
        val region = Rect(
            floor(points.minOf { it.first }).toInt(), floor(points.minOf { it.second }).toInt(),
            (floor(points.maxOf { it.first }).toInt() + 1).coerceAtMost(width),
            (floor(points.maxOf { it.second }).toInt() + 1).coerceAtMost(height)
        )
        var sampleSize = 1
        while (maxOf(region.width(), region.height()) / (sampleSize * 2) >= FILE_SAMPLE_MAX_EDGE) sampleSize *= 2
        val decoder = runCatching { createBitmapRegionDecoder(file) }.getOrNull() ?: return null
        return try {
            val cropped = decoder.decodeRegion(region, BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return null
            try {
                average(cropped, points, region.left.toFloat(), region.top.toFloat(),
                    cropped.width.toFloat() / region.width(), cropped.height.toFloat() / region.height())
            } finally {
                cropped.recycle()
            }
        } finally {
            decoder.recycle()
        }
    }

    private fun ringPoints(
        left: Float, top: Float, right: Float, bottom: Float,
        outside: Boolean, contour: FloatArray?
    ): List<Pair<Float, Float>> {
        if (!listOf(left, top, right, bottom).all { it.isFinite() } || right <= left || bottom <= top) return emptyList()
        val validContour = contour?.takeIf { !outside && it.size >= 6 && it.size % 2 == 0 && it.all(Float::isFinite) }
        val distance = (minOf(right - left, bottom - top) * 0.06f).coerceAtLeast(0.5f)
        val points = ArrayList<Pair<Float, Float>>()
        if (validContour == null) {
            val inset = if (outside) -distance else distance.coerceAtMost(minOf(right - left, bottom - top) / 2f)
            val l = left + inset
            val t = top + inset
            val r = right - inset
            val b = bottom - inset
            val horizontalCount = ceil((r - l) / 4f).toInt().coerceIn(1, 512)
            val verticalCount = ceil((b - t) / 4f).toInt().coerceIn(1, 512)
            for (i in 0 until horizontalCount) {
                val x = l + (r - l) * (i + 0.5f) / horizontalCount
                points.add(x to t)
                points.add(x to b)
            }
            for (i in 0 until verticalCount) {
                val y = t + (b - t) * (i + 0.5f) / verticalCount
                points.add(l to y)
                points.add(r to y)
            }
            return points
        }
        val polygon = validContour
        for (i in polygon.indices step 2) {
            val j = (i + 2) % polygon.size
            val dx = polygon[j] - polygon[i]
            val dy = polygon[j + 1] - polygon[i + 1]
            val length = hypot(dx, dy)
            if (length <= 0f) continue
            val count = ceil(length / 4f).toInt().coerceIn(1, 512)
            for (s in 0 until count) {
                val t = (s + 0.5f) / count
                val x = polygon[i] + dx * t
                val y = polygon[i + 1] + dy * t
                // Test both normals so clockwise, reversed and concave contours work alike.
                for (sign in listOf(-1f, 1f)) {
                    val px = x - dy / length * distance * sign
                    val py = y + dx / length * distance * sign
                    if (contains(polygon, px, py) != outside) points.add(px to py)
                }
            }
        }
        return points
    }

    private fun contains(polygon: FloatArray, x: Float, y: Float): Boolean {
        var inside = false
        var j = polygon.size - 2
        for (i in polygon.indices step 2) {
            val xi = polygon[i]
            val yi = polygon[i + 1]
            val xj = polygon[j]
            val yj = polygon[j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun average(
        bitmap: Bitmap, points: List<Pair<Float, Float>>, originX: Float, originY: Float,
        scaleX: Float, scaleY: Float
    ): Int? {
        if (points.isEmpty()) return null
        val copy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE)
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null else null
        val src = copy ?: bitmap
        try {
            var red = 0L
            var green = 0L
            var blue = 0L
            for ((x, y) in points) {
                val pixel = src.getPixel(((x - originX) * scaleX).toInt().coerceIn(0, src.width - 1),
                    ((y - originY) * scaleY).toInt().coerceIn(0, src.height - 1))
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
            }
            return Color.rgb((red / points.size).toInt(), (green / points.size).toInt(), (blue / points.size).toInt())
        } finally {
            copy?.recycle()
        }
    }

    private fun createBitmapRegionDecoder(imageFile: File): BitmapRegionDecoder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(imageFile.absolutePath)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(imageFile.absolutePath, false)
        }
    }
}
