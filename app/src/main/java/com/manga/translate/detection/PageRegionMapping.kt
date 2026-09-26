package com.manga.translate.detection

import android.graphics.RectF

internal fun PageRegionDetectionResult.remapToSource(
    sourceWidth: Int,
    sourceHeight: Int
): PageRegionDetectionResult {
    if (width <= 0 || height <= 0) return this
    if (width == sourceWidth && height == sourceHeight) return this
    val scaleX = sourceWidth / width.toFloat()
    val scaleY = sourceHeight / height.toFloat()
    return PageRegionDetectionResult(
        width = sourceWidth,
        height = sourceHeight,
        bubbleDetections = bubbleDetections.map { detection ->
            detection.copy(rect = detection.rect.scaleBy(scaleX, scaleY))
        },
        allTextDetections = allTextDetections.map { it.copy(rect = it.rect.scaleBy(scaleX, scaleY)) },
        tiles = tiles.map { DetectionTile((it.left * scaleX).toInt(), (it.top * scaleY).toInt(), (it.right * scaleX).toInt(), (it.bottom * scaleY).toInt()) },
        textRects = textRects.map { rect -> rect.scaleBy(scaleX, scaleY) },
        regions = regions.map { region ->
            region.copy(
                rect = region.rect.scaleBy(scaleX, scaleY),
                textLineRects = region.textLineRects?.map { rect ->
                    rect.scaleBy(scaleX, scaleY)
                }
            )
        },
        detectionComplete = detectionComplete,
        detectionMode = detectionMode
    )
}

internal fun mapPageLineRectsToCrop(
    lineRects: List<RectF>?,
    cropRect: RectF,
    cropWidth: Int,
    cropHeight: Int
): List<RectF>? {
    if (lineRects == null) return null
    if (cropRect.width() <= 0f || cropRect.height() <= 0f || cropWidth <= 0 || cropHeight <= 0) {
        return emptyList()
    }
    val scaleX = cropWidth / cropRect.width()
    val scaleY = cropHeight / cropRect.height()
    return lineRects.mapNotNull { line ->
        val mapped = RectF(
            (line.left - cropRect.left) * scaleX,
            (line.top - cropRect.top) * scaleY,
            (line.right - cropRect.left) * scaleX,
            (line.bottom - cropRect.top) * scaleY
        )
        val clamped = RectF(
            mapped.left.coerceIn(0f, cropWidth.toFloat()),
            mapped.top.coerceIn(0f, cropHeight.toFloat()),
            mapped.right.coerceIn(0f, cropWidth.toFloat()),
            mapped.bottom.coerceIn(0f, cropHeight.toFloat())
        )
        clamped.takeIf { it.width() >= 2f && it.height() >= 2f }
    }
}

internal fun RectF.scaleBy(scaleX: Float, scaleY: Float): RectF {
    return RectF(
        left * scaleX,
        top * scaleY,
        right * scaleX,
        bottom * scaleY
    )
}
