package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class FloatingEmptyBubbleCoordinator(
    context: Context,
    private val llmClient: LlmClient,
    private val floatingTranslationCacheStore: FloatingTranslationCacheStore,
    private val settingsStore: SettingsStore
) {
    private val appContext = context.applicationContext
    private val floatingBubbleTranslationCoordinator = FloatingBubbleTranslationCoordinator(
        llmClient = llmClient,
        floatingTranslationCacheStore = floatingTranslationCacheStore,
        settingsStore = settingsStore
    )
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
        return floatingBubbleTranslationCoordinator.translateTextBubbles(
            bubbles = bubbles,
            timeoutMs = timeoutMs,
            retryCount = retryCount,
            promptAsset = promptAsset,
            apiSettings = apiSettings,
            language = TranslationLanguage.JA_TO_ZH,
            logTag = "FloatingOCR"
        )
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
        val outcome = floatingBubbleTranslationCoordinator.translateImageBubbles(
            bitmap = bitmap,
            bubbles = bubbles,
            timeoutMs = timeoutMs,
            retryCount = retryCount,
            promptAsset = promptAsset,
            apiSettings = apiSettings,
            concurrency = concurrency,
            maxConcurrency = maxVlConcurrency,
            logTag = "FloatingOCR"
        )
        return@coroutineScope FloatingVlBubbleTranslateOutcome(
            bubbles = outcome.bubbles,
            timedOut = outcome.timedOut,
            requiresVlModel = outcome.requiresVlModel
        )
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
