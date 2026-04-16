package com.manga.translate

internal class TextBubbleTranslationCoordinator(
    private val llmClient: LlmClient,
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
        translationMode: String
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
                if (translatedMap.containsKey(bubble.id)) {
                    bubble.copy(text = translatedMap[bubble.id].orEmpty())
                } else {
                    bubble
                }
            }
        }

        if (cacheMisses.isEmpty()) {
            return TextBubbleTranslationBatchResult(
                bubbles = merge(),
                glossaryUsed = emptyMap()
            )
        }

        AppLogger.log(logTag, "Translate request segments=${cacheMisses.size}")
        val requestItems = cacheMisses.map {
            LlmBubbleTranslationRequestItem(
                id = it.id,
                text = normalizeOcrText(it.text, language)
            )
        }
        val translated = llmClient.translateBubbleItems(
            items = requestItems,
            glossary = glossary,
            promptAsset = promptAsset,
            requestTimeoutMs = requestTimeoutMs,
            retryCount = retryCount,
            apiSettings = resolvedApiSettings
        ) ?: return null

        val translationById = LinkedHashMap<Int, String>(translated.items.size)
        val duplicateIds = LinkedHashSet<Int>()
        for (item in translated.items) {
            val normalizedTranslation = item.translation.trim()
            if (translationById.putIfAbsent(item.id, normalizedTranslation) != null) {
                duplicateIds.add(item.id)
            }
        }
        val requestedIds = requestItems.map { it.id }
        val requestedIdSet = requestedIds.toSet()
        val unexpectedIds = translationById.keys.filter { it !in requestedIdSet }
        val missingIds = requestedIds.filter { translationById[it].isNullOrBlank() }
        if (duplicateIds.isNotEmpty() || unexpectedIds.isNotEmpty() || missingIds.isNotEmpty()) {
            AppLogger.log(
                logTag,
                buildStructuredTranslationErrorLog(
                    mode = translationMode,
                    requestedIds = requestedIds,
                    duplicateIds = duplicateIds.toList(),
                    unexpectedIds = unexpectedIds,
                    missingIds = missingIds
                )
            )
        }

        for (source in cacheMisses) {
            val translatedText = translationById[source.id].orEmpty()
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

private fun buildStructuredTranslationErrorLog(
    mode: String,
    requestedIds: List<Int>,
    duplicateIds: List<Int>,
    unexpectedIds: List<Int>,
    missingIds: List<Int>
): String {
    return buildString {
        append("Structured translation partial in ")
        append(mode)
        append(": requested=")
        append(summarizeIdsForLog(requestedIds))
        if (duplicateIds.isNotEmpty()) {
            append(", duplicate=")
            append(summarizeIdsForLog(duplicateIds))
        }
        if (unexpectedIds.isNotEmpty()) {
            append(", unexpected=")
            append(summarizeIdsForLog(unexpectedIds))
        }
        if (missingIds.isNotEmpty()) {
            append(", missing=")
            append(summarizeIdsForLog(missingIds))
        }
    }
}

private fun summarizeIdsForLog(ids: List<Int>, limit: Int = 12): String {
    if (ids.isEmpty()) return "[]"
    val normalized = ids.distinct()
    val shown = normalized.take(limit).joinToString(prefix = "[", postfix = "]")
    return if (normalized.size <= limit) shown else "$shown...(${normalized.size} total)"
}
