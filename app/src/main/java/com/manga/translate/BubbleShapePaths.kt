package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import kotlin.math.max
import kotlin.math.min

internal object BubbleShapePaths {
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    fun buildPath(
        outPath: Path,
        bubble: BubbleTranslation,
        sourceWidth: Int,
        sourceHeight: Int,
        originX: Float,
        originY: Float,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        shrinkPercent: Int = 0,
        fallbackCornerRadius: Float = 6f
    ) {
        outPath.reset()
        val contour = bubble.maskContour
        if (sourceWidth > 0 && sourceHeight > 0 && contour != null && contour.size >= 6) {
            outPath.moveTo(
                originX + (contour[0] * sourceWidth + offsetX) * scaleX,
                originY + (contour[1] * sourceHeight + offsetY) * scaleY
            )
            var index = 2
            while (index + 1 < contour.size) {
                outPath.lineTo(
                    originX + (contour[index] * sourceWidth + offsetX) * scaleX,
                    originY + (contour[index + 1] * sourceHeight + offsetY) * scaleY
                )
                index += 2
            }
            outPath.close()
        } else {
            outPath.addRoundRect(
                originX + (bubble.rect.left + offsetX) * scaleX,
                originY + (bubble.rect.top + offsetY) * scaleY,
                originX + (bubble.rect.right + offsetX) * scaleX,
                originY + (bubble.rect.bottom + offsetY) * scaleY,
                fallbackCornerRadius,
                fallbackCornerRadius,
                Path.Direction.CW
            )
        }
        applyShrink(outPath, shrinkPercent)
    }

    fun insetTextBounds(path: Path, outRect: RectF) {
        val pathBounds = RectF()
        path.computeBounds(pathBounds, true)
        outRect.set(pathBounds)
        if (pathBounds.width() <= 0f || pathBounds.height() <= 0f) return

        val pad = (minOf(pathBounds.width(), pathBounds.height()) * 0.08f).coerceAtLeast(6f)
        outRect.inset(pad, pad)
        if (outRect.width() <= 0f || outRect.height() <= 0f) {
            outRect.set(pathBounds)
            return
        }

        val safeRect = estimateSafeTextRect(path, pathBounds, pad)
        if (safeRect != null && safeRect.width() > 0f && safeRect.height() > 0f) {
            outRect.set(safeRect)
        }
    }

    private fun applyShrink(path: Path, shrinkPercent: Int) {
        val normalizedPercent = shrinkPercent.coerceIn(0, 95)
        if (normalizedPercent <= 0) return
        val tempBounds = RectF()
        path.computeBounds(tempBounds, true)
        if (tempBounds.width() <= 0f || tempBounds.height() <= 0f) return
        val scale = (100f - normalizedPercent) / 100f
        val tempMatrix = Matrix()
        tempMatrix.setScale(scale, scale, tempBounds.centerX(), tempBounds.centerY())
        path.transform(tempMatrix)
    }

    private fun estimateSafeTextRect(path: Path, pathBounds: RectF, fallbackPad: Float): RectF? {
        val maxMaskSize = 96
        val maskWidth = pathBounds.width().toInt().coerceIn(16, maxMaskSize)
        val maskHeight = pathBounds.height().toInt().coerceIn(16, maxMaskSize)
        if (maskWidth <= 1 || maskHeight <= 1) return null

        val maskBitmap = createBitmap(maskWidth, maskHeight)
        val maskCanvas = Canvas(maskBitmap)
        val maskPath = Path(path)
        val matrix = Matrix().apply {
            postTranslate(-pathBounds.left, -pathBounds.top)
            postScale(
                (maskWidth - 1).toFloat() / pathBounds.width().coerceAtLeast(1f),
                (maskHeight - 1).toFloat() / pathBounds.height().coerceAtLeast(1f)
            )
        }
        maskPath.transform(matrix)
        maskCanvas.drawColor(Color.TRANSPARENT)
        maskCanvas.drawPath(maskPath, maskPaint)

        val rowFill = FloatArray(maskHeight)
        val colFill = FloatArray(maskWidth)
        val pixels = IntArray(maskWidth * maskHeight)
        maskBitmap.getPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
        maskBitmap.recycle()

        for (y in 0 until maskHeight) {
            var filled = 0
            val rowOffset = y * maskWidth
            for (x in 0 until maskWidth) {
                if ((pixels[rowOffset + x] ushr 24) != 0) {
                    filled += 1
                    colFill[x] += 1f
                }
            }
            rowFill[y] = filled / maskWidth.toFloat()
        }
        for (x in 0 until maskWidth) {
            colFill[x] /= maskHeight.toFloat()
        }

        val top = findEdge(rowFill, fromStart = true)
        val bottom = findEdge(rowFill, fromStart = false)
        val left = findEdge(colFill, fromStart = true)
        val right = findEdge(colFill, fromStart = false)
        if (left >= right || top >= bottom) return null

        val widthScale = pathBounds.width() / maskWidth.toFloat()
        val heightScale = pathBounds.height() / maskHeight.toFloat()
        val extraPadX = min(fallbackPad, pathBounds.width() * 0.12f)
        val extraPadY = min(fallbackPad, pathBounds.height() * 0.12f)
        val safeRect = RectF(
            pathBounds.left + left * widthScale + extraPadX * 0.35f,
            pathBounds.top + top * heightScale + extraPadY * 0.35f,
            pathBounds.left + (right + 1) * widthScale - extraPadX * 0.35f,
            pathBounds.top + (bottom + 1) * heightScale - extraPadY * 0.35f
        )
        return if (safeRect.width() > pathBounds.width() * 0.18f &&
            safeRect.height() > pathBounds.height() * 0.18f
        ) {
            safeRect
        } else {
            null
        }
    }

    private fun findEdge(fillRatios: FloatArray, fromStart: Boolean): Int {
        val preferredThreshold = 0.82f
        val fallbackThreshold = 0.68f
        val preferred = findEdge(fillRatios, fromStart, preferredThreshold)
        if (preferred != -1) return preferred
        val fallback = findEdge(fillRatios, fromStart, fallbackThreshold)
        if (fallback != -1) return fallback
        return if (fromStart) 0 else max(fillRatios.lastIndex, 0)
    }

    private fun findEdge(fillRatios: FloatArray, fromStart: Boolean, threshold: Float): Int {
        val range = if (fromStart) {
            0..fillRatios.lastIndex
        } else {
            fillRatios.lastIndex downTo 0
        }
        for (index in range) {
            if (fillRatios[index] >= threshold) {
                return index
            }
        }
        return -1
    }
}
