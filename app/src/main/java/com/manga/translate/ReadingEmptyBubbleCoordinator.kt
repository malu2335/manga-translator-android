package com.manga.translate

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class ReadingEmptyBubbleCoordinator(
    context: Context,
    private val translationStore: TranslationStore,
    private val glossaryStore: GlossaryStore,
    private val libraryPrefs: SharedPreferences,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext),
    private val bubbleTextRecognizer: BubbleTextRecognizer,
    private val textBubbleTranslationCoordinator: TextBubbleTranslationCoordinator,
    private val languageKeyPrefix: String = "translation_language_"
) {

    suspend fun process(
        imageFile: File,
        folder: File,
        baseTranslation: TranslationResult
    ): EmptyBubbleProcessOutcome? = withContext(Dispatchers.Default) {
        val targets = baseTranslation.bubbles.filter { it.text.isBlank() }
        if (targets.isEmpty()) return@withContext null

        val ocrSettings = settingsStore.loadOcrApiSettings()
        val useLocalOcr = ocrSettings.useLocalOcr
        val language = if (useLocalOcr) {
            getTranslationLanguage(folder)
        } else {
            TranslationLanguage.JA_TO_ZH
        }
        val glossary = glossaryStore.load(folder)
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return@withContext null

        try {
            val candidates = ArrayList<OcrBubble>(targets.size)
            val removedIds = HashSet<Int>()
            if (!ocrSettings.useLocalOcr && !ocrSettings.isValid()) {
                AppLogger.log("Reading", "Missing OCR API settings")
                return@withContext null
            }
            for (bubble in targets) {
                val text = ocrBubble(bitmap, bubble.rect, language, ocrSettings.useLocalOcr).trim()
                if (text.length <= 2) {
                    removedIds.add(bubble.id)
                } else {
                    candidates.add(OcrBubble(bubble.id, bubble.rect, text, bubble.source))
                }
            }

            val remainingBubbles = baseTranslation.bubbles.filterNot { removedIds.contains(it.id) }
            if (candidates.isEmpty()) {
                val updated = baseTranslation.copy(bubbles = remainingBubbles)
                withContext(Dispatchers.IO) {
                    translationStore.save(imageFile, updated)
                }
                return@withContext EmptyBubbleProcessOutcome(updated, translatedByLlm = false)
            }

            val translated = translateOcrBubbles(imageFile, candidates, glossary, language)
            if (translated != null) {
                if (translated.glossaryUsed.isNotEmpty()) {
                    glossary.putAll(translated.glossaryUsed)
                    withContext(Dispatchers.IO) {
                        glossaryStore.save(folder, glossary)
                    }
                }
                val translationMap = translated.bubbles.associateBy({ it.id }, { it.text })
                val merged = remainingBubbles.map { bubble ->
                    translationMap[bubble.id]?.let { bubble.copy(text = it) } ?: bubble
                }
                val updated = baseTranslation.copy(bubbles = merged)
                withContext(Dispatchers.IO) {
                    translationStore.save(imageFile, updated)
                }
                return@withContext EmptyBubbleProcessOutcome(updated, translatedByLlm = true)
            }

            val fallbackMap = candidates.associate { it.id to it.text }
            val merged = remainingBubbles.map { bubble ->
                fallbackMap[bubble.id]?.let { bubble.copy(text = it) } ?: bubble
            }
            val updated = baseTranslation.copy(bubbles = merged)
            withContext(Dispatchers.IO) {
                translationStore.save(imageFile, updated)
            }
            EmptyBubbleProcessOutcome(updated, translatedByLlm = false)
        } finally {
            bitmap.recycleSafely()
        }
    }

    private fun getTranslationLanguage(folder: File): TranslationLanguage {
        val value = libraryPrefs.getString(languageKeyPrefix + folder.absolutePath, null)
        return TranslationLanguage.fromString(value)
    }

    private suspend fun translateOcrBubbles(
        imageFile: File,
        bubbles: List<OcrBubble>,
        glossary: Map<String, String>,
        language: TranslationLanguage
    ): TextBubbleTranslationBatchResult? = withContext(Dispatchers.IO) {
        val promptAsset = "llm_prompts.json"
        textBubbleTranslationCoordinator.translateBubbles(
            bubbles = bubbles.map { bubble ->
                BubbleTranslation(bubble.id, bubble.rect, bubble.text, bubble.source)
            },
            glossary = glossary,
            promptAsset = promptAsset,
            language = language,
            logTag = "Reading",
            invalidResponseMode = "reading_empty_bubble"
        )
    }

    private suspend fun ocrBubble(
        bitmap: android.graphics.Bitmap,
        rect: RectF,
        language: TranslationLanguage,
        useLocalOcr: Boolean
    ): String {
        return bubbleTextRecognizer.recognizeRegion(
            source = bitmap,
            rect = rect,
            language = language,
            useLocalOcr = useLocalOcr,
            logTag = "Reading"
        )
    }
}

data class EmptyBubbleProcessOutcome(
    val updatedTranslation: TranslationResult,
    val translatedByLlm: Boolean
)
