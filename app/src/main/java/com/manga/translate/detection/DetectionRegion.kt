package com.manga.translate.detection

import android.graphics.Bitmap
import android.graphics.RectF
import com.manga.translate.translation.vlLayout

/** Detect only the selected rectangle, returning full-screen coordinates. */
internal suspend fun detectWithinInsets(
    bitmap: Bitmap,
    topInsetPercent: Int,
    bottomInsetPercent: Int,
    leftInsetPercent: Int = 0,
    rightInsetPercent: Int = 0,
    detect: suspend (Bitmap) -> List<PageRegion>?
): List<PageRegion>? {
    val topPercent = topInsetPercent.coerceIn(0, 90)
    val bottomPercent = bottomInsetPercent.coerceIn(0, 90 - topPercent)
    val top = (bitmap.height * topPercent / 100f).toInt()
    val bottom = (bitmap.height * (100 - bottomPercent) / 100f).toInt()
        .coerceAtLeast(top + 1).coerceAtMost(bitmap.height)
    val leftPercent = leftInsetPercent.coerceIn(0, 90)
    val rightPercent = rightInsetPercent.coerceIn(0, 90 - leftPercent)
    val left = (bitmap.width * leftPercent / 100f).toInt()
    val right = (bitmap.width * (100 - rightPercent) / 100f).toInt()
        .coerceAtLeast(left + 1).coerceAtMost(bitmap.width)
    if (left == 0 && right == bitmap.width && top == 0 && bottom == bitmap.height) return detect(bitmap)
    val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    try {
        return detect(crop)?.map { region ->
            region.copy(
                rect = RectF(region.rect).apply { offset(left.toFloat(), top.toFloat()) },
                textLineRects = region.textLineRects?.map { rect ->
                    RectF(rect).apply { offset(left.toFloat(), top.toFloat()) }
                },
                // Mask vertices are normalized against the detector input image.
                maskContour = region.maskContour?.mapIndexed { index, value ->
                    if (index % 2 == 0) (value * crop.width + left) / bitmap.width else (value * crop.height + top) / bitmap.height
                }?.toFloatArray()
            )
        }
    } finally {
        if (crop !== bitmap) crop.recycle()
    }
}

/** Uses the same inset crop and contour mapping as ordinary detection. */
internal suspend fun detectVlWithinInsets(
    bitmap: Bitmap, top: Int, bottom: Int, left: Int, right: Int,
    detector: PageRegionDetector
): com.manga.translate.translation.VlPageLayout? {
    var targetCount = 0
    var backgroundCount = 0
    var targetParents: Map<Int, Int> = emptyMap()
    val mapped = detectWithinInsets(bitmap, top, bottom, left, right) { input ->
        val detection = detector.detect(input, logTag = "FloatingVL") ?: return@detectWithinInsets null
        if (!detection.detectionComplete) return@detectWithinInsets null
        val layout = detection.vlLayout()
        targetParents = layout.targetParents
        targetCount = layout.targets.size
        backgroundCount = layout.backgrounds.size
        layout.targets.map { PageRegion(it.id, it.rect, it.source, it.maskContour) } +
            layout.backgrounds.map { PageRegion(-1, it.rect, com.manga.translate.model.BubbleSource.BUBBLE_DETECTOR, it.maskContour) } +
            layout.tiles.map { PageRegion(-1, it.toRectF(), com.manga.translate.model.BubbleSource.BUBBLE_DETECTOR) }
    } ?: return null
    return com.manga.translate.translation.VlPageLayout(bitmap.width, bitmap.height,
        mapped.take(targetCount).map { com.manga.translate.model.BubbleTranslation.pending(it.id, it.rect, "", it.source, it.maskContour) },
        mapped.drop(targetCount).take(backgroundCount).map { BubbleDetection(it.rect, 1f, BubbleDetector.CLASS_BALLOON, it.maskContour) },
        mapped.drop(targetCount + backgroundCount).map { DetectionTile(it.rect.left.toInt(), it.rect.top.toInt(), it.rect.right.toInt(), it.rect.bottom.toInt()) },
        targetParents)
}
