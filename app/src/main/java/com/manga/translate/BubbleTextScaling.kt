package com.manga.translate

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max

internal object BubbleTextScaling {
    data class HorizontalScalePlan(
        val defaultLayout: StaticLayout,
        val drawRect: RectF,
        val scaleX: Float,
        val scaleY: Float
    )

    fun layoutFits(layout: StaticLayout, maxWidth: Int, maxHeight: Int): Boolean {
        if (layout.height > maxHeight) return false
        for (line in 0 until layout.lineCount) {
            if (layout.getLineWidth(line) > maxWidth + 0.5f) {
                return false
            }
        }
        return true
    }

    fun scalePathAroundCenter(path: Path, scaleX: Float, scaleY: Float) {
        if (scaleX == 1f && scaleY == 1f) return
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, bounds.centerX(), bounds.centerY())
        path.transform(matrix)
    }

    fun buildHorizontalScalePlan(
        text: String,
        rect: RectF,
        minTextSizePx: Float,
        expandBubbleWhenMinFontSize: Boolean,
        buildLayout: (String, Int, Float) -> StaticLayout,
        layoutFits: (StaticLayout, Int, Int) -> Boolean
    ): HorizontalScalePlan {
        val baseWidth = rect.width().toInt().coerceAtLeast(1)
        val baseHeight = rect.height().toInt().coerceAtLeast(1)
        val defaultTextSize = findDefaultHorizontalTextSize(
            text = text,
            maxWidth = baseWidth,
            maxHeight = baseHeight,
            minTextSizePx = minTextSizePx,
            buildLayout = buildLayout,
            layoutFits = layoutFits
        )
        val defaultLayout = buildLayout(text, baseWidth, defaultTextSize)
        if (minTextSizePx <= defaultTextSize) {
            return HorizontalScalePlan(
                defaultLayout = defaultLayout,
                drawRect = RectF(rect),
                scaleX = 1f,
                scaleY = 1f
            )
        }

        if (!expandBubbleWhenMinFontSize) {
            val scale = (minTextSizePx / defaultTextSize).coerceAtLeast(1f)
            return HorizontalScalePlan(
                defaultLayout = defaultLayout,
                drawRect = RectF(rect),
                scaleX = scale,
                scaleY = scale
            )
        }

        val expandedWidth = max(baseWidth, ((defaultLayout.width * minTextSizePx) / defaultTextSize).toInt())
            .coerceAtLeast(baseWidth)
        val expandedLayout = buildLayout(text, expandedWidth, minTextSizePx)
        val expandedRect = RectF(rect)
        val targetWidth = max(rect.width(), expandedLayout.width.toFloat())
        val targetHeight = max(rect.height(), expandedLayout.height.toFloat())
        expandedRect.set(
            rect.centerX() - targetWidth / 2f,
            rect.centerY() - targetHeight / 2f,
            rect.centerX() + targetWidth / 2f,
            rect.centerY() + targetHeight / 2f
        )
        return HorizontalScalePlan(
            defaultLayout = expandedLayout,
            drawRect = expandedRect,
            scaleX = 1f,
            scaleY = 1f
        )
    }

    fun findDefaultHorizontalTextSize(
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        minTextSizePx: Float,
        buildLayout: (String, Int, Float) -> StaticLayout,
        layoutFits: (StaticLayout, Int, Int) -> Boolean
    ): Float {
        val minLayout = buildLayout(text, maxWidth, minTextSizePx)
        if (!layoutFits(minLayout, maxWidth, maxHeight)) {
            return minTextSizePx
        }
        var bestSize = minTextSizePx
        var low = minTextSizePx
        var high = maxOf(low, maxWidth.toFloat(), maxHeight.toFloat())
        val stepPx = 0.5f
        while (high - low > stepPx) {
            val mid = (low + high) / 2f
            val candidate = buildLayout(text, maxWidth, mid)
            if (layoutFits(candidate, maxWidth, maxHeight)) {
                bestSize = mid
                low = mid
            } else {
                high = mid
            }
        }
        return bestSize
    }

    fun buildCenteredHorizontalLayout(
        text: String,
        width: Int,
        textSize: Float,
        paint: TextPaint
    ): StaticLayout {
        paint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }
}
