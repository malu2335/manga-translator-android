package com.manga.translate

import android.graphics.Path
import android.graphics.RectF

internal object BubbleShapePaths {
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

    fun insetTextBounds(pathBounds: RectF, outRect: RectF) {
        outRect.set(pathBounds)
        val pad = (minOf(pathBounds.width(), pathBounds.height()) * 0.08f).coerceAtLeast(6f)
        outRect.inset(pad, pad)
    }

    private fun applyShrink(path: Path, shrinkPercent: Int) {
        val normalizedPercent = shrinkPercent.coerceIn(0, 95)
        if (normalizedPercent <= 0) return
        val tempBounds = RectF()
        path.computeBounds(tempBounds, true)
        if (tempBounds.width() <= 0f || tempBounds.height() <= 0f) return
        val scale = (100f - normalizedPercent) / 100f
        val tempMatrix = android.graphics.Matrix()
        tempMatrix.setScale(scale, scale, tempBounds.centerX(), tempBounds.centerY())
        path.transform(tempMatrix)
    }
}
