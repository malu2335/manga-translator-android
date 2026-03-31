package com.manga.translate

import android.graphics.Bitmap
import android.graphics.RectF

const val DEFAULT_EN_MIN_LINE_SCORE = 0.5f

data class EnglishLine(
    val rect: RectF,
    val text: String
)

fun normalizeOcrText(text: String, language: TranslationLanguage): String {
    if (language != TranslationLanguage.EN_TO_ZH && language != TranslationLanguage.KO_TO_ZH) return text
    return text.replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun extractTaggedSegments(
    text: String,
    fallback: List<String>,
    onMissingTags: ((expected: Int) -> Unit)? = null,
    onCountMismatch: ((expected: Int, actual: Int) -> Unit)? = null
): List<String> {
    val expected = fallback.size
    val regex = Regex("<b>(.*?)</b>", RegexOption.DOT_MATCHES_ALL)
    val matches = regex.findAll(text).map { it.groupValues[1].trim() }.toList()
    if (matches.isEmpty()) {
        if (expected == 1) return listOf(text.trim())
        onMissingTags?.invoke(expected)
        return List(expected) { "" }
    }
    if (matches.size != expected) {
        onCountMismatch?.invoke(expected, matches.size)
    }
    val result = MutableList(expected) { "" }
    val limit = minOf(expected, matches.size)
    for (i in 0 until limit) {
        result[i] = matches[i]
    }
    return result
}

fun cropBitmap(source: Bitmap, rect: RectF): Bitmap? {
    val left = rect.left.toInt().coerceIn(0, source.width - 1)
    val top = rect.top.toInt().coerceIn(0, source.height - 1)
    val right = rect.right.toInt().coerceIn(1, source.width)
    val bottom = rect.bottom.toInt().coerceIn(1, source.height)
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return null
    return Bitmap.createBitmap(source, left, top, width, height)
}

fun Bitmap?.recycleSafely() {
    if (this != null && !isRecycled) {
        recycle()
    }
}

inline fun <T> withBitmapCrop(
    source: Bitmap,
    rect: RectF,
    block: (Bitmap) -> T
): T? {
    val crop = cropBitmap(source, rect) ?: return null
    return try {
        block(crop)
    } finally {
        crop.recycleSafely()
    }
}

fun recognizeEnglishLines(
    source: Bitmap,
    lineRects: List<RectF>,
    ocrEngine: EnglishOcr,
    minLineScore: Float = DEFAULT_EN_MIN_LINE_SCORE
): List<EnglishLine> {
    if (lineRects.isEmpty()) return emptyList()
    val results = ArrayList<EnglishLine>(lineRects.size)
    for (rect in lineRects) {
        withBitmapCrop(source, rect) { crop ->
            val decoded = ocrEngine.recognizeWithScore(crop)
            val text = decoded.text.trim()
            if (decoded.score >= minLineScore && text.isNotBlank()) {
                results.add(EnglishLine(rect, text))
            }
        }
    }
    return results
}

fun recognizeKoreanLines(
    source: Bitmap,
    lineRects: List<RectF>,
    ocrEngine: KoreanOcr,
    minLineScore: Float = DEFAULT_EN_MIN_LINE_SCORE
): List<EnglishLine> {
    if (lineRects.isEmpty()) return emptyList()
    val results = ArrayList<EnglishLine>(lineRects.size)
    for (rect in lineRects) {
        withBitmapCrop(source, rect) { crop ->
            val decoded = ocrEngine.recognizeWithScore(crop)
            val text = decoded.text.trim()
            if (decoded.score >= minLineScore && text.isNotBlank()) {
                results.add(EnglishLine(rect, text))
            }
        }
    }
    return results
}
