package com.manga.translate

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class FloatingBubbleTranslationCoordinator(
    private val llmClient: LlmClient,
    private val floatingTranslationCacheStore: FloatingTranslationCacheStore,
    private val settingsStore: SettingsStore
) {

    suspend fun translateTextBubbles(
        bubbles: List<BubbleTranslation>,
        timeoutMs: Int,
        retryCount: Int,
        promptAsset: String,
        apiSettings: ApiSettings = settingsStore.loadResolvedFloatingTranslateApiSettings(),
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        logTag: String = "FloatingOCR"
    ): List<BubbleTranslation>? {
        if (bubbles.isEmpty()) return bubbles
        val translatable = bubbles.filter { it.text.isNotBlank() }
        if (translatable.isEmpty()) {
            AppLogger.log(logTag, "Skip translate: no translatable text")
            return bubbles
        }
        if (!llmClient.isConfigured(apiSettings)) {
            AppLogger.log(logTag, "Skip translate: LLM client not configured")
            return bubbles
        }

        val translatedMap = HashMap<Int, String>(translatable.size)
        val cacheMisses = ArrayList<BubbleTranslation>(translatable.size)
        var exactCacheHits = 0
        var similarityCacheHits = 0
        for (bubble in translatable) {
            val cached = floatingTranslationCacheStore.findTextTranslation(bubble.text)
            if (cached == null) {
                cacheMisses.add(bubble)
                continue
            }
            translatedMap[bubble.id] = cached.translation
            if (cached.matchedBySimilarity) {
                similarityCacheHits++
            } else {
                exactCacheHits++
            }
        }
        AppLogger.log(
            "FloatingCache",
            "Text cache exactHits=$exactCacheHits similarityHits=$similarityCacheHits misses=${cacheMisses.size}"
        )

        fun merge(): List<BubbleTranslation> {
            return bubbles.map { bubble ->
                translatedMap[bubble.id]?.takeIf { it.isNotBlank() }?.let { translated ->
                    bubble.copy(text = translated)
                } ?: bubble
            }
        }

        if (cacheMisses.isEmpty()) {
            return merge()
        }

        AppLogger.log(logTag, "Translate request segments=${cacheMisses.size}")
        return try {
            val text = cacheMisses.joinToString("\n") {
                "<b>${normalizeOcrText(it.text, language)}</b>"
            }
            var missingTags = false
            var countMismatch = false
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
                cacheMisses.map { it.text },
                onMissingTags = {
                    missingTags = true
                    AppLogger.log(logTag, "Missing <b> tags in floating translation")
                },
                onCountMismatch = { expected, actual ->
                    countMismatch = true
                    AppLogger.log(
                        logTag,
                        "Floating translation count mismatch: expected $expected, got $actual"
                    )
                }
            )
            if (missingTags || countMismatch || segments.any { it.isBlank() }) {
                throw LlmResponseException(
                    errorCode = "EMPTY_TRANSLATION_SEGMENT",
                    responseContent = buildBlankModelResponseMessage(
                        bubbleCount = cacheMisses.size,
                        mode = "text",
                        countMismatch = countMismatch,
                        missingTags = missingTags
                    )
                )
            }
            for (i in cacheMisses.indices) {
                val source = cacheMisses[i]
                val translatedText = segments.getOrElse(i) { source.text }
                translatedMap[source.id] = translatedText
                if (translatedText.isNotBlank()) {
                    floatingTranslationCacheStore.putTextTranslation(source.text, translatedText)
                }
            }
            val result = merge()
            AppLogger.log(logTag, "Translate success segments=${translatedMap.size}")
            result
        } catch (e: LlmRequestException) {
            if (e.errorCode == "TIMEOUT") {
                AppLogger.log(logTag, "LLM translate timeout")
                null
            } else {
                AppLogger.log(logTag, "LLM translate request failed, fallback to cached/OCR text", e)
                merge()
            }
        } catch (e: LlmResponseException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(logTag, "LLM translate failed, fallback to cached/OCR text", e)
            merge()
        }
    }

    suspend fun translateImageBubbles(
        bitmap: Bitmap,
        bubbles: List<BubbleTranslation>,
        timeoutMs: Int,
        retryCount: Int,
        promptAsset: String,
        apiSettings: ApiSettings = settingsStore.loadResolvedFloatingTranslateApiSettings(),
        concurrency: Int,
        maxConcurrency: Int,
        logTag: String = "FloatingOCR"
    ): FloatingBubbleImageTranslateOutcome = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceIn(1, maxConcurrency))
        val tasks = bubbles.map { bubble ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val crop = cropBitmap(bitmap, bubble.rect)
                    if (crop == null) {
                        return@withPermit FloatingBubbleImageTranslateTaskResult(bubble = bubble)
                    }
                    val imageCacheKey = floatingTranslationCacheStore.createImageKey(crop)
                    val cachedTranslation = floatingTranslationCacheStore.findImageTranslation(imageCacheKey)
                    if (!cachedTranslation.isNullOrBlank()) {
                        AppLogger.log("FloatingCache", "VL cache hit bubble=${bubble.id}")
                        crop.recycleSafely()
                        return@withPermit FloatingBubbleImageTranslateTaskResult(
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
                            AppLogger.log(logTag, "VL direct translate timeout")
                            return@withPermit FloatingBubbleImageTranslateTaskResult(timedOut = true)
                        }
                        AppLogger.log(logTag, "VL direct translate request failed", e)
                        if (looksLikeVisionModelError(e)) {
                            return@withPermit FloatingBubbleImageTranslateTaskResult(requiresVlModel = true)
                        }
                        ""
                    } catch (e: Exception) {
                        AppLogger.log(logTag, "VL direct translate failed", e)
                        ""
                    } finally {
                        crop.recycleSafely()
                    }
                    if (translatedText.isNotBlank()) {
                        floatingTranslationCacheStore.putImageTranslation(imageCacheKey, translatedText)
                    }
                    if (translatedText.isBlank()) {
                        return@withPermit FloatingBubbleImageTranslateTaskResult(
                            responseException = LlmResponseException(
                                errorCode = "EMPTY_TRANSLATION_SEGMENT",
                                responseContent = buildBlankModelResponseMessage(
                                    bubbleCount = 1,
                                    mode = "image"
                                )
                            )
                        )
                    }
                    FloatingBubbleImageTranslateTaskResult(
                        bubble = bubble.copy(text = translatedText)
                    )
                }
            }
        }
        val results = tasks.awaitAll()
        if (results.any { it.requiresVlModel }) {
            return@coroutineScope FloatingBubbleImageTranslateOutcome(requiresVlModel = true)
        }
        if (results.any { it.timedOut }) {
            return@coroutineScope FloatingBubbleImageTranslateOutcome(timedOut = true)
        }
        results.firstNotNullOfOrNull { it.responseException }?.let { throw it }
        val translated = results.mapNotNull { it.bubble }
        AppLogger.log(logTag, "VL direct translate success segments=${translated.size}")
        return@coroutineScope FloatingBubbleImageTranslateOutcome(bubbles = translated)
    }

    fun looksLikeVisionModelError(error: LlmRequestException): Boolean {
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
}

private fun buildBlankModelResponseMessage(
    bubbleCount: Int,
    mode: String,
    countMismatch: Boolean = false,
    missingTags: Boolean = false
): String {
    val reason = when {
        missingTags -> "模型返回内容缺少 <b> 标签"
        countMismatch -> "模型返回的气泡数量与请求数量不一致"
        else -> "模型返回空白结果"
    }
    return "$reason：$mode 模式下有 $bubbleCount 个气泡未返回有效翻译内容。"
}

internal data class FloatingBubbleImageTranslateOutcome(
    val bubbles: List<BubbleTranslation> = emptyList(),
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)

private data class FloatingBubbleImageTranslateTaskResult(
    val bubble: BubbleTranslation? = null,
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false,
    val responseException: LlmResponseException? = null
)
