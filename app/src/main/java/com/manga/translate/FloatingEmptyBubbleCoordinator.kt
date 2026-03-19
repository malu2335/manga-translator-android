package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class FloatingEmptyBubbleCoordinator(
    context: Context,
    private val llmClient: LlmClient,
    private val floatingTranslationCacheStore: FloatingTranslationCacheStore,
    private val settingsStore: SettingsStore
) {
    private val appContext = context.applicationContext
    private var mangaOcr: MangaOcr? = null

    suspend fun process(
        bitmap: Bitmap,
        baseTranslation: TranslationResult,
        timeoutMs: Int,
        retryCount: Int,
        floatPromptAsset: String,
        floatVlPromptAsset: String,
        maxVlConcurrency: Int
    ): FloatingEmptyBubbleOutcome = withContext(Dispatchers.Default) {
        val targets = baseTranslation.bubbles.filter { it.text.isBlank() }
        if (targets.isEmpty()) {
            return@withContext FloatingEmptyBubbleOutcome(baseTranslation)
        }

        val client = llmClient
        val floatingSettings = settingsStore.loadFloatingTranslateApiSettings()
        val floatingApiSettings = settingsStore.loadResolvedFloatingTranslateApiSettings()
        val useVlDirectTranslate =
            floatingSettings.useVlDirectTranslate && client.isConfigured(floatingApiSettings)

        val updatedBubbles = if (useVlDirectTranslate) {
            val outcome = translateBubbleImages(
                bitmap = bitmap,
                bubbles = targets,
                timeoutMs = timeoutMs,
                retryCount = retryCount,
                apiSettings = floatingApiSettings,
                promptAsset = floatVlPromptAsset,
                concurrency = floatingSettings.vlTranslateConcurrency,
                maxVlConcurrency = maxVlConcurrency
            )
            if (outcome.requiresVlModel || outcome.timedOut) {
                return@withContext FloatingEmptyBubbleOutcome(
                    translation = baseTranslation,
                    timedOut = outcome.timedOut,
                    requiresVlModel = outcome.requiresVlModel
                )
            }
            val translations = outcome.bubbles.associateBy({ it.id }, { it.text })
            baseTranslation.bubbles.map { bubble ->
                translations[bubble.id]?.let { bubble.copy(text = it) } ?: bubble
            }
        } else {
            val recognized = recognizeEmptyBubbles(bitmap, targets)
            val translated = translateRecognizedBubbles(
                bubbles = recognized,
                timeoutMs = timeoutMs,
                retryCount = retryCount,
                promptAsset = floatPromptAsset,
                apiSettings = floatingApiSettings
            ) ?: return@withContext FloatingEmptyBubbleOutcome(
                translation = baseTranslation,
                timedOut = true
            )
            val translations = translated.associateBy({ it.id }, { it.text })
            baseTranslation.bubbles.map { bubble ->
                translations[bubble.id]?.let { bubble.copy(text = it) } ?: bubble
            }
        }

        FloatingEmptyBubbleOutcome(baseTranslation.copy(bubbles = updatedBubbles))
    }

    private suspend fun recognizeEmptyBubbles(
        bitmap: Bitmap,
        bubbles: List<BubbleTranslation>
    ): List<BubbleTranslation> = withContext(Dispatchers.Default) {
        val ocr = getMangaOcr()
        bubbles.map { bubble ->
            val crop = cropBitmap(bitmap, bubble.rect)
            val text = try {
                if (crop == null) {
                    ""
                } else {
                    ocr?.recognize(crop)?.trim().orEmpty()
                }
            } catch (e: Exception) {
                AppLogger.log("FloatingOCR", "Recognize empty bubble failed id=${bubble.id}", e)
                ""
            } finally {
                crop?.recycle()
            }
            bubble.copy(text = text)
        }
    }

    private suspend fun translateRecognizedBubbles(
        bubbles: List<BubbleTranslation>,
        timeoutMs: Int,
        retryCount: Int,
        promptAsset: String,
        apiSettings: ApiSettings
    ): List<BubbleTranslation>? {
        if (bubbles.isEmpty()) return bubbles
        val translatable = bubbles.filter { it.text.isNotBlank() }
        if (translatable.isEmpty()) {
            return bubbles
        }

        val translatedMap = HashMap<Int, String>(translatable.size)
        val cacheMisses = ArrayList<BubbleTranslation>(translatable.size)
        for (bubble in translatable) {
            val cached = floatingTranslationCacheStore.findTextTranslation(bubble.text)
            if (cached == null) {
                cacheMisses.add(bubble)
            } else {
                translatedMap[bubble.id] = cached.translation
            }
        }

        fun merge(): List<BubbleTranslation> {
            return bubbles.map { bubble ->
                translatedMap[bubble.id]?.let { translated ->
                    bubble.copy(text = translated)
                } ?: bubble
            }
        }

        if (cacheMisses.isEmpty()) {
            return merge()
        }
        if (!llmClient.isConfigured(apiSettings)) {
            return merge()
        }

        return try {
            val text = cacheMisses.joinToString("\n") {
                "<b>${normalizeOcrText(it.text, TranslationLanguage.JA_TO_ZH)}</b>"
            }
            val translated = llmClient.translate(
                text = text,
                glossary = emptyMap(),
                promptAsset = promptAsset,
                requestTimeoutMs = timeoutMs,
                retryCount = retryCount,
                apiSettings = apiSettings
            ) ?: return merge()
            val segments = extractTaggedSegments(
                translated.translation,
                cacheMisses.map { it.text }
            )
            for (i in cacheMisses.indices) {
                val source = cacheMisses[i]
                val translatedText = segments.getOrElse(i) { source.text }
                translatedMap[source.id] = translatedText
                if (translatedText.isNotBlank()) {
                    floatingTranslationCacheStore.putTextTranslation(source.text, translatedText)
                }
            }
            merge()
        } catch (e: LlmRequestException) {
            if (e.errorCode == "TIMEOUT") {
                null
            } else {
                AppLogger.log("FloatingOCR", "Translate empty bubbles failed", e)
                merge()
            }
        } catch (e: Exception) {
            AppLogger.log("FloatingOCR", "Translate empty bubbles failed", e)
            merge()
        }
    }

    private suspend fun translateBubbleImages(
        bitmap: Bitmap,
        bubbles: List<BubbleTranslation>,
        timeoutMs: Int,
        retryCount: Int,
        apiSettings: ApiSettings,
        promptAsset: String,
        concurrency: Int,
        maxVlConcurrency: Int
    ): FloatingVlBubbleTranslateOutcome = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceIn(1, maxVlConcurrency))
        val tasks = bubbles.map { bubble ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val crop = cropBitmap(bitmap, bubble.rect)
                    if (crop == null) {
                        return@withPermit FloatingVlBubbleTranslateTaskResult(
                            bubble = bubble
                        )
                    }
                    val imageCacheKey = floatingTranslationCacheStore.createImageKey(crop)
                    val cachedTranslation = floatingTranslationCacheStore.findImageTranslation(imageCacheKey)
                    if (!cachedTranslation.isNullOrBlank()) {
                        crop.recycle()
                        return@withPermit FloatingVlBubbleTranslateTaskResult(
                            bubble = bubble.copy(text = cachedTranslation)
                        )
                    }
                    val translatedText = try {
                        llmClient.translateImageBubble(
                            image = crop,
                            promptAsset = promptAsset,
                            requestTimeoutMs = timeoutMs,
                            retryCount = retryCount,
                            apiSettings = apiSettings
                        ).orEmpty()
                    } catch (e: LlmRequestException) {
                        if (e.errorCode == "TIMEOUT") {
                            return@withPermit FloatingVlBubbleTranslateTaskResult(timedOut = true)
                        }
                        if (looksLikeVisionModelError(e)) {
                            return@withPermit FloatingVlBubbleTranslateTaskResult(requiresVlModel = true)
                        }
                        AppLogger.log("FloatingOCR", "Translate image bubble failed id=${bubble.id}", e)
                        ""
                    } catch (e: Exception) {
                        AppLogger.log("FloatingOCR", "Translate image bubble failed id=${bubble.id}", e)
                        ""
                    } finally {
                        crop.recycle()
                    }
                    if (translatedText.isNotBlank()) {
                        floatingTranslationCacheStore.putImageTranslation(imageCacheKey, translatedText)
                    }
                    FloatingVlBubbleTranslateTaskResult(
                        bubble = bubble.copy(text = translatedText)
                    )
                }
            }
        }
        val results = tasks.awaitAll()
        if (results.any { it.requiresVlModel }) {
            return@coroutineScope FloatingVlBubbleTranslateOutcome(requiresVlModel = true)
        }
        if (results.any { it.timedOut }) {
            return@coroutineScope FloatingVlBubbleTranslateOutcome(timedOut = true)
        }
        FloatingVlBubbleTranslateOutcome(bubbles = results.mapNotNull { it.bubble })
    }

    private fun looksLikeVisionModelError(error: LlmRequestException): Boolean {
        val body = error.responseBody.orEmpty().lowercase()
        val code = error.errorCode.lowercase()
        val hints = listOf(
            "image",
            "vision",
            "multimodal",
            "multi-modal",
            "image_url",
            "input_image",
            "does not support image",
            "unsupported content type"
        )
        return code.startsWith("http") && hints.any { it in body }
    }

    private fun getMangaOcr(): MangaOcr? {
        if (mangaOcr != null) return mangaOcr
        return try {
            MangaOcr(appContext).also { mangaOcr = it }
        } catch (e: Exception) {
            AppLogger.log("FloatingOCR", "Failed to init MangaOCR for floating edit", e)
            null
        }
    }
}

data class FloatingEmptyBubbleOutcome(
    val translation: TranslationResult,
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)

private data class FloatingVlBubbleTranslateOutcome(
    val bubbles: List<BubbleTranslation> = emptyList(),
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)

private data class FloatingVlBubbleTranslateTaskResult(
    val bubble: BubbleTranslation? = null,
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)
