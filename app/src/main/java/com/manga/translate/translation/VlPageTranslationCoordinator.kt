package com.manga.translate.translation

import com.manga.translate.model.TranslationLanguage
import com.manga.translate.network.*
import com.manga.translate.platform.BitmapCropSource
import com.manga.translate.platform.ImageEncodingUtils
import com.manga.translate.platform.recycleSafely
import com.manga.translate.settings.ApiSettings
import com.manga.translate.storage.FloatingCacheScope
import com.manga.translate.storage.FloatingTranslationCacheStore
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject

internal class VlPageTranslationCoordinator(
    private val client: LlmGateway,
    private val cache: FloatingTranslationCacheStore
) {
    suspend fun translate(
        source: BitmapCropSource,
        layout: VlPageLayout,
        apiSettings: ApiSettings,
        language: TranslationLanguage,
        timeoutMs: Int,
        retryCount: Int,
        glossary: Map<String, String> = emptyMap(),
        glossaryProcessingEnabled: Boolean = false,
        useCache: Boolean = false
    ): FloatingBubbleImageTranslateOutcome {
        val translated = linkedMapOf<Int, String>()
        val names = linkedMapOf<String, String>()
        val scope = FloatingCacheScope(language, apiSettings.providerId, apiSettings.modelName, PROMPT_ASSET)
        for (chunk in layout.chunks()) {
            coroutineContext.ensureActive()
            val crop = source.decodeRegion(chunk.rect) ?: throw LlmResponseException(LlmErrorCode.ImageEncodeFailed, "Cannot decode VL page")
            val annotated = try { VlPageAnnotationRenderer.render(crop, layout, chunk) } finally { crop.recycleSafely() }
            val encoded = try { ImageEncodingUtils.encodeBitmapToBase64(annotated) } finally { annotated.recycleSafely() }
                ?: throw LlmResponseException(LlmErrorCode.ImageEncodeFailed, "Cannot encode VL page")
            val ids = chunk.targets.map { it.id }
            val key = if (useCache && glossary.isEmpty() && !glossaryProcessingEnabled) {
                cache.createImageKey((encoded + "|" + ids.joinToString(",")).toByteArray(Charsets.UTF_8))
            } else null
            val cached = key?.let { cache.findImageTranslation(it, scope) }
            val result = if (cached != null) ResponseParser().parseImageItemsContent(cached, ids) else {
                try {
                    client.translateImageItems(encoded, ids, PROMPT_ASSET, glossary, glossaryProcessingEnabled,
                        timeoutMs, retryCount, apiSettings)
                        ?: throw LlmResponseException(LlmErrorCode.EmptyResponse, "Empty VL page response")
                } catch (error: LlmRequestException) {
                    if (error.errorCode == LlmErrorCode.Timeout) return FloatingBubbleImageTranslateOutcome(timedOut = true)
                    val body = error.responseBody.orEmpty().lowercase()
                    if (error.errorCode is LlmErrorCode.Http && listOf("image", "vision", "multimodal", "multi-modal").any { it in body }) {
                        return FloatingBubbleImageTranslateOutcome(requiresVlModel = true)
                    }
                    throw error
                }
            }
            val returnedIds = result.items.map { it.id }
            if (returnedIds.size != returnedIds.toSet().size || returnedIds.toSet() != ids.toSet()) {
                throw LlmResponseException(LlmErrorCode.MissingTranslationItems, "VL page ID mismatch")
            }
            result.items.forEach { translated[it.id] = it.translation.trim() }
            if (glossaryProcessingEnabled) names.putAll(result.glossaryUsed)
            if (cached == null && key != null) {
                val items = JSONArray()
                result.items.forEach { items.put(JSONObject().put("id", it.id).put("translation", it.translation)) }
                cache.putImageTranslation(key, JSONObject().put("items", items).toString(), scope)
            }
        }
        return FloatingBubbleImageTranslateOutcome(
            bubbles = layout.renderTranslations(translated), glossaryUsed = names
        )
    }

    companion object { const val PROMPT_ASSET = "prompts/vl_page_prompts.json" }
}
