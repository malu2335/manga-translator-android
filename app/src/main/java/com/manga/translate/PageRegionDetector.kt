package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

internal class PageRegionDetector(
    context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private var bubbleDetector: BubbleDetector? = null
    private var textDetector: TextDetector? = null

    fun detect(bitmap: Bitmap, logTag: String = "PageRegionDetector"): PageRegionDetectionResult? {
        val bubbleDetector = getBubbleDetector(logTag) ?: return null
        val detections = bubbleDetector.detect(bitmap)
        val bubbleRects = detections.map { it.rect }
        val textRects = detectSupplementTextRects(bitmap, detections)
        if (textRects.isNotEmpty()) {
            AppLogger.log(logTag, "Supplemented ${textRects.size} text boxes")
        }
        val regions = buildRegions(detections, bubbleRects, textRects)
        return PageRegionDetectionResult(
            width = bitmap.width,
            height = bitmap.height,
            bubbleDetections = detections,
            textRects = textRects,
            regions = regions
        )
    }

    private fun getBubbleDetector(logTag: String): BubbleDetector? {
        if (bubbleDetector != null) return bubbleDetector
        return try {
            bubbleDetector = BubbleDetector(appContext, settingsStore = settingsStore)
            bubbleDetector
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init bubble detector", e)
            null
        }
    }

    private fun getTextDetector(logTag: String): TextDetector? {
        if (textDetector != null) return textDetector
        return try {
            textDetector = TextDetector(appContext, settingsStore = settingsStore)
            textDetector
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init text detector", e)
            null
        }
    }

    private fun detectSupplementTextRects(
        bitmap: Bitmap,
        detections: List<BubbleDetection>
    ): List<RectF> {
        val textDetector = getTextDetector("PageRegionDetector") ?: return emptyList()
        val bubbleRects = detections.map { it.rect }
        val masked = maskDetections(bitmap, detections)
        return try {
            val rawTextRects = textDetector.detect(masked)
            val filtered = filterOverlapping(rawTextRects, bubbleRects, TEXT_IOU_THRESHOLD)
            RectGeometryDeduplicator.mergeSupplementRects(filtered, bitmap.width, bitmap.height)
        } finally {
            if (masked !== bitmap) {
                masked.recycleSafely()
            }
        }
    }

    private fun buildRegions(
        detections: List<BubbleDetection>,
        bubbleRects: List<RectF>,
        textRects: List<RectF>
    ): List<PageRegion> {
        val allRects = ArrayList<RectF>(bubbleRects.size + textRects.size)
        allRects.addAll(bubbleRects)
        allRects.addAll(textRects)
        val bubbleDetectorCount = bubbleRects.size
        return allRects.mapIndexed { index, rect ->
            PageRegion(
                id = index,
                rect = rect,
                source = if (index < bubbleDetectorCount) {
                    BubbleSource.BUBBLE_DETECTOR
                } else {
                    BubbleSource.TEXT_DETECTOR
                },
                maskContour = if (index < bubbleDetectorCount) {
                    detections.getOrNull(index)?.maskContour
                } else {
                    null
                }
            )
        }
    }

    private fun maskDetections(source: Bitmap, detections: List<BubbleDetection>): Bitmap {
        if (detections.isEmpty()) return source
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val paint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        for (det in detections) {
            if (det.maskContour != null && det.maskContour.size >= 6) {
                canvas.drawPath(
                    buildContourPath(det.maskContour, source.width.toFloat(), source.height.toFloat()),
                    paint
                )
            } else {
                canvas.drawRect(
                    padRect(det.rect, source.width, source.height, MASK_EXPAND_RATIO, MASK_EXPAND_MIN),
                    paint
                )
            }
        }
        return copy
    }

    private fun buildContourPath(contour: FloatArray, w: Float, h: Float): Path {
        val path = Path()
        path.moveTo(contour[0] * w, contour[1] * h)
        var i = 2
        while (i + 1 < contour.size) {
            path.lineTo(contour[i] * w, contour[i + 1] * h)
            i += 2
        }
        path.close()
        return path
    }

    private fun padRect(rect: RectF, width: Int, height: Int, ratio: Float, minPad: Float): RectF {
        val h = max(1f, rect.height())
        val pad = max(minPad, ratio * h)
        val left = (rect.left - pad).coerceIn(0f, width.toFloat())
        val top = (rect.top - pad).coerceIn(0f, height.toFloat())
        val right = (rect.right + pad).coerceIn(0f, width.toFloat())
        val bottom = (rect.bottom + pad).coerceIn(0f, height.toFloat())
        return RectF(left, top, right, bottom)
    }

    private fun filterOverlapping(
        textRects: List<RectF>,
        bubbleRects: List<RectF>,
        threshold: Float
    ): List<RectF> {
        if (bubbleRects.isEmpty()) return textRects
        val filtered = ArrayList<RectF>(textRects.size)
        for (rect in textRects) {
            var overlapped = false
            for (bubble in bubbleRects) {
                if (iou(rect, bubble) >= threshold || contains(bubble, rect)) {
                    overlapped = true
                    break
                }
            }
            if (!overlapped) {
                filtered.add(rect)
            }
        }
        return filtered
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val inter = max(0f, right - left) * max(0f, bottom - top)
        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun contains(outer: RectF, inner: RectF): Boolean {
        return outer.left <= inner.left &&
            outer.top <= inner.top &&
            outer.right >= inner.right &&
            outer.bottom >= inner.bottom
    }

    companion object {
        private const val TEXT_IOU_THRESHOLD = 0.2f
        private const val MASK_EXPAND_RATIO = 0.1f
        private const val MASK_EXPAND_MIN = 4f
    }
}

data class PageRegion(
    val id: Int,
    val rect: RectF,
    val source: BubbleSource,
    val maskContour: FloatArray? = null
)

data class PageRegionDetectionResult(
    val width: Int,
    val height: Int,
    val bubbleDetections: List<BubbleDetection>,
    val textRects: List<RectF>,
    val regions: List<PageRegion>
)
