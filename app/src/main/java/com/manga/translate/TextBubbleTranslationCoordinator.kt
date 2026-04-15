package com.manga.translate

internal class TextBubbleTranslationCoordinator(
    private val llmClient: LlmClient,
    private val settingsStore: SettingsStore,
    private val floatingTranslationCacheStore: FloatingTranslationCacheStore? = null
) {

    suspend fun translateBubbles(
        bubbles: List<BubbleTranslation>,
        glossary: Map<String, String>,
        promptAsset: String,
        requestTimeoutMs: Int? = null,
        retryCount: Int = 3,
        apiSettings: ApiSettings? = null,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        logTag: String,
        useFloatingTextCache: Boolean = false,
        invalidResponseMode: String
    ): TextBubbleTranslationBatchResult? {
        if (bubbles.isEmpty()) {
            return TextBubbleTranslationBatchResult(bubbles = bubbles, glossaryUsed = emptyMap())
        }
        val translatable = bubbles.filter { it.text.isNotBlank() }
        if (translatable.isEmpty()) {
            AppLogger.log(logTag, "Skip translate: no translatable text")
            return TextBubbleTranslationBatchResult(bubbles = bubbles, glossaryUsed = emptyMap())
        }

        val resolvedApiSettings = apiSettings
        if (!llmClient.isConfigured(resolvedApiSettings)) {
            AppLogger.log(logTag, "Skip translate: LLM client not configured")
            return null
        }

        val translatedMap = HashMap<Int, String>(translatable.size)
        val cacheMisses = ArrayList<BubbleTranslation>(translatable.size)
        if (useFloatingTextCache) {
            val cacheStore = floatingTranslationCacheStore
            requireNotNull(cacheStore) { "Floating text cache requested but cache store is unavailable" }
            var exactCacheHits = 0
            var similarityCacheHits = 0
            for (bubble in translatable) {
                val cached = cacheStore.findTextTranslation(bubble.text)
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
        } else {
            cacheMisses.addAll(translatable)
        }

        fun merge(): List<BubbleTranslation> {
            return bubbles.map { bubble ->
                translatedMap[bubble.id]?.takeIf { it.isNotBlank() }?.let { translated ->
                    bubble.copy(text = translated)
                } ?: bubble
            }
        }

        if (cacheMisses.isEmpty()) {
            return TextBubbleTranslationBatchResult(
                bubbles = merge(),
                glossaryUsed = emptyMap()
            )
        }

        AppLogger.log(logTag, "Translate request segments=${cacheMisses.size}")
        val taggedText = cacheMisses.joinToString("\n") {
            "<b>${normalizeOcrText(it.text, language)}</b>"
        }
        val translated = llmClient.translate(
            text = taggedText,
            glossary = glossary,
            promptAsset = promptAsset,
            requestTimeoutMs = requestTimeoutMs,
            retryCount = retryCount,
            apiSettings = resolvedApiSettings
        ) ?: return null

        var missingTags = false
        var countMismatch = false
        val segments = extractTaggedSegments(
            translated.translation,
            cacheMisses.map { it.text },
            onMissingTags = {
                missingTags = true
                AppLogger.log(logTag, "Missing <b> tags in $invalidResponseMode translation")
            },
            onCountMismatch = { expected, actual ->
                countMismatch = true
                AppLogger.log(
                    logTag,
                    "Translation count mismatch in $invalidResponseMode: expected $expected, got $actual"
                )
            }
        )
        if (missingTags || countMismatch || segments.any { it.isBlank() }) {
            val reason = when {
                missingTags -> "模型返回内容缺少 <b> 标签"
                countMismatch -> "模型返回的气泡数量与请求数量不一致"
                else -> "模型返回空白结果"
            }
            throw LlmResponseException(
                errorCode = "EMPTY_TRANSLATION_SEGMENT",
                responseContent = "$reason：$invalidResponseMode 模式下未返回有效翻译内容。"
            )
        }

        for (i in cacheMisses.indices) {
            val source = cacheMisses[i]
            val translatedText = segments.getOrElse(i) { source.text }
            translatedMap[source.id] = translatedText
            if (useFloatingTextCache && translatedText.isNotBlank()) {
                floatingTranslationCacheStore?.putTextTranslation(source.text, translatedText)
            }
        }

        return TextBubbleTranslationBatchResult(
            bubbles = merge(),
            glossaryUsed = translated.glossaryUsed
        )
    }
}

internal data class TextBubbleTranslationBatchResult(
    val bubbles: List<BubbleTranslation>,
    val glossaryUsed: Map<String, String>
)
