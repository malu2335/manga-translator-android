package com.manga.translate.rendering

import android.graphics.Paint
import android.text.TextPaint

internal data class VerticalTextLayout(
    val columnWidth: Float,
    val lineHeight: Float,
    val maxRows: Int,
    val columns: Int,
    val totalWidth: Float,
    val totalHeight: Float,
    val fontMetrics: Paint.FontMetrics,
    val fits: Boolean
)

internal object VerticalTextLayoutCalculator {
    fun build(
        textPaint: TextPaint,
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        textSize: Float
    ): VerticalTextLayout {
        textPaint.textSize = textSize
        val fontMetrics = textPaint.fontMetrics
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(1f)
        val maxRows = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
        var maxCharWidth = 0f
        for (ch in text) {
            if (ch == '\n') continue
            val width = textPaint.measureText(ch.toString())
            if (width > maxCharWidth) {
                maxCharWidth = width
            }
        }
        if (maxCharWidth <= 0f) {
            maxCharWidth = textPaint.measureText("国")
        }
        maxCharWidth = maxCharWidth.coerceAtLeast(1f)
        // Match the renderer: explicit breaks start a new column, automatic
        // wrapping only starts another column when the next glyph arrives.
        var columns = 1
        var row = 0
        var usedRows = 0
        for (ch in text) {
            if (ch == '\n') {
                columns += 1
                row = 0
            } else {
                if (row >= maxRows) {
                    columns += 1
                    row = 0
                }
                row += 1
                usedRows = maxOf(usedRows, row)
            }
        }
        val totalWidth = columns * maxCharWidth
        val totalHeight = usedRows * lineHeight
        val fits = totalWidth <= maxWidth && totalHeight <= maxHeight
        return VerticalTextLayout(
            columnWidth = maxCharWidth,
            lineHeight = lineHeight,
            maxRows = maxRows,
            columns = columns,
            totalWidth = totalWidth,
            totalHeight = totalHeight,
            fontMetrics = fontMetrics,
            fits = fits
        )
    }
}
