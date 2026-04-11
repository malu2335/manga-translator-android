package com.manga.translate

import android.graphics.RectF

enum class BubbleSource(val jsonValue: String) {
    BUBBLE_DETECTOR("bubble_detector"),
    TEXT_DETECTOR("text_detector"),
    MANUAL("manual"),
    UNKNOWN("unknown");

    companion object {
        fun fromJson(value: String?): BubbleSource {
            return entries.firstOrNull { it.jsonValue.equals(value, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

data class TranslationMetadata(
    val sourceLastModified: Long = 0L,
    val sourceFileSize: Long = 0L,
    val mode: String = "",
    val language: String = "",
    val promptAsset: String = "",
    val modelName: String = "",
    val apiFormat: String = "",
    val ocrCacheMode: String = "",
    val version: Int = CURRENT_VERSION
) {
    fun isEmpty(): Boolean {
        return sourceLastModified <= 0L &&
            sourceFileSize <= 0L &&
            mode.isBlank() &&
            language.isBlank() &&
            promptAsset.isBlank() &&
            modelName.isBlank() &&
            apiFormat.isBlank() &&
            ocrCacheMode.isBlank()
    }

    fun isManual(): Boolean {
        return mode == MODE_MANUAL
    }

    fun matchesSource(imageFile: java.io.File): Boolean {
        return sourceLastModified == imageFile.lastModified() &&
            sourceFileSize == imageFile.length()
    }

    fun withSourceFingerprint(imageFile: java.io.File): TranslationMetadata {
        return copy(
            sourceLastModified = imageFile.lastModified(),
            sourceFileSize = imageFile.length()
        )
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val MODE_STANDARD = "standard"
        const val MODE_FULL_PAGE = "full_page"
        const val MODE_VL_DIRECT = "vl_direct"
        const val MODE_MANUAL = "manual"
    }
}

data class OcrMetadata(
    val sourceLastModified: Long = 0L,
    val sourceFileSize: Long = 0L,
    val cacheMode: String = "",
    val language: String = "",
    val engineModel: String = "",
    val version: Int = CURRENT_VERSION
) {
    fun matchesSource(imageFile: java.io.File): Boolean {
        return sourceLastModified == imageFile.lastModified() &&
            sourceFileSize == imageFile.length()
    }

    fun matches(expected: OcrMetadata): Boolean {
        return version == expected.version &&
            cacheMode == expected.cacheMode &&
            language == expected.language &&
            engineModel == expected.engineModel
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class BubbleTranslation(
    val id: Int,
    val rect: RectF,
    val text: String,
    val source: BubbleSource = BubbleSource.UNKNOWN
)

data class TranslationResult(
    val imageName: String,
    val width: Int,
    val height: Int,
    val bubbles: List<BubbleTranslation>,
    val metadata: TranslationMetadata = TranslationMetadata()
)
