package com.manga.translate

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class ApiSettings(
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val apiFormat: ApiFormat = ApiFormat.OPENAI_COMPATIBLE
) {
    fun isValid(): Boolean {
        return apiUrl.isNotBlank() && apiKey.isNotBlank() && modelName.isNotBlank()
    }
}

data class OcrApiSettings(
    val useLocalOcr: Boolean,
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val timeoutSeconds: Int
) {
    fun isValid(): Boolean {
        return useLocalOcr || (apiUrl.isNotBlank() && apiKey.isNotBlank() && modelName.isNotBlank())
    }
}

data class FloatingTranslateApiSettings(
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val useVlDirectTranslate: Boolean,
    val vlTranslateConcurrency: Int,
    val proofreadingModeEnabled: Boolean
)

data class CustomRequestParameter(
    val key: String,
    val value: String,
    val enabled: Boolean = true
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ApiSettings {
        val url = prefs.getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
        val key = prefs.getString(KEY_API_KEY, "") ?: ""
        val model = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL
        val apiFormat = ApiFormat.fromPref(prefs.getString(KEY_API_FORMAT, null))
        return ApiSettings(url, key, model, apiFormat)
    }

    fun save(settings: ApiSettings) {
        prefs.edit() {
                putString(KEY_API_URL, settings.apiUrl)
                .putString(KEY_API_KEY, settings.apiKey)
                .putString(KEY_MODEL_NAME, settings.modelName)
                .putString(KEY_API_FORMAT, settings.apiFormat.prefValue)
            }
    }

    fun loadFloatingTranslateApiSettings(): FloatingTranslateApiSettings {
        return FloatingTranslateApiSettings(
            apiUrl = prefs.getString(KEY_FLOATING_API_URL, "") ?: "",
            apiKey = prefs.getString(KEY_FLOATING_API_KEY, "") ?: "",
            modelName = prefs.getString(KEY_FLOATING_MODEL_NAME, "") ?: "",
            useVlDirectTranslate = prefs.getBoolean(KEY_FLOATING_USE_VL_DIRECT_TRANSLATE, false),
            vlTranslateConcurrency = prefs.getInt(
                KEY_FLOATING_VL_TRANSLATE_CONCURRENCY,
                DEFAULT_FLOATING_VL_TRANSLATE_CONCURRENCY
            ).coerceIn(
                MIN_FLOATING_VL_TRANSLATE_CONCURRENCY,
                MAX_FLOATING_VL_TRANSLATE_CONCURRENCY
            ),
            proofreadingModeEnabled = prefs.getBoolean(KEY_FLOATING_PROOFREADING_MODE_ENABLED, false)
        )
    }

    fun loadResolvedFloatingTranslateApiSettings(): ApiSettings {
        val floating = loadFloatingTranslateApiSettings()
        val main = load()
        return ApiSettings(
            apiUrl = floating.apiUrl.ifBlank { main.apiUrl },
            apiKey = floating.apiKey.ifBlank { main.apiKey },
            modelName = floating.modelName.ifBlank { main.modelName },
            apiFormat = main.apiFormat
        )
    }

    fun saveFloatingTranslateApiSettings(settings: FloatingTranslateApiSettings) {
        val normalizedConcurrency = settings.vlTranslateConcurrency.coerceIn(
            MIN_FLOATING_VL_TRANSLATE_CONCURRENCY,
            MAX_FLOATING_VL_TRANSLATE_CONCURRENCY
        )
        prefs.edit() {
                putString(KEY_FLOATING_API_URL, settings.apiUrl)
                .putString(KEY_FLOATING_API_KEY, settings.apiKey)
                .putString(KEY_FLOATING_MODEL_NAME, settings.modelName)
                .putBoolean(KEY_FLOATING_USE_VL_DIRECT_TRANSLATE, settings.useVlDirectTranslate)
                .putInt(KEY_FLOATING_VL_TRANSLATE_CONCURRENCY, normalizedConcurrency)
                .putBoolean(
                    KEY_FLOATING_PROOFREADING_MODE_ENABLED,
                    settings.proofreadingModeEnabled
                )
            }
    }

    fun loadOcrApiSettings(): OcrApiSettings {
        val useLocal = prefs.getBoolean(KEY_OCR_USE_LOCAL, true)
        val url = prefs.getString(KEY_OCR_API_URL, DEFAULT_OCR_API_URL) ?: DEFAULT_OCR_API_URL
        val key = prefs.getString(KEY_OCR_API_KEY, "") ?: ""
        val model = prefs.getString(KEY_OCR_MODEL_NAME, DEFAULT_OCR_MODEL_NAME) ?: DEFAULT_OCR_MODEL_NAME
        val timeoutSeconds = prefs.getInt(KEY_OCR_API_TIMEOUT_SECONDS, DEFAULT_OCR_API_TIMEOUT_SECONDS)
            .coerceIn(MIN_OCR_API_TIMEOUT_SECONDS, MAX_OCR_API_TIMEOUT_SECONDS)
        return OcrApiSettings(
            useLocalOcr = useLocal,
            apiUrl = url,
            apiKey = key,
            modelName = model,
            timeoutSeconds = timeoutSeconds
        )
    }

    fun saveOcrApiSettings(settings: OcrApiSettings) {
        val normalizedTimeout = settings.timeoutSeconds
            .coerceIn(MIN_OCR_API_TIMEOUT_SECONDS, MAX_OCR_API_TIMEOUT_SECONDS)
        prefs.edit() {
                putBoolean(KEY_OCR_USE_LOCAL, settings.useLocalOcr)
                .putString(KEY_OCR_API_URL, settings.apiUrl)
                .putString(KEY_OCR_API_KEY, settings.apiKey)
                .putString(KEY_OCR_MODEL_NAME, settings.modelName)
                .putInt(KEY_OCR_API_TIMEOUT_SECONDS, normalizedTimeout)
            }
    }

    fun loadUseHorizontalText(): Boolean {
        return prefs.getBoolean(KEY_HORIZONTAL_TEXT, false)
    }

    fun saveUseHorizontalText(enabled: Boolean) {
        prefs.edit() {
                putBoolean(KEY_HORIZONTAL_TEXT, enabled)
            }
    }

    fun loadModelIoLogging(): Boolean {
        return prefs.getBoolean(KEY_MODEL_IO_LOGGING, false)
    }

    fun saveModelIoLogging(enabled: Boolean) {
        prefs.edit() {
                putBoolean(KEY_MODEL_IO_LOGGING, enabled)
            }
    }

    fun loadMaxConcurrency(): Int {
        val saved = prefs.getInt(KEY_MAX_CONCURRENCY, DEFAULT_MAX_CONCURRENCY)
        return saved.coerceIn(MIN_MAX_CONCURRENCY, MAX_MAX_CONCURRENCY)
    }

    fun saveMaxConcurrency(value: Int) {
        val normalized = value.coerceIn(MIN_MAX_CONCURRENCY, MAX_MAX_CONCURRENCY)
        prefs.edit() {
                putInt(KEY_MAX_CONCURRENCY, normalized)
            }
    }

    fun loadApiTimeoutSeconds(): Int {
        val saved = prefs.getInt(KEY_API_TIMEOUT_SECONDS, DEFAULT_API_TIMEOUT_SECONDS)
        return saved.coerceIn(MIN_API_TIMEOUT_SECONDS, MAX_API_TIMEOUT_SECONDS)
    }

    fun loadApiTimeoutMs(): Int {
        return loadApiTimeoutSeconds() * 1000
    }

    fun saveApiTimeoutSeconds(value: Int) {
        val normalized = value.coerceIn(MIN_API_TIMEOUT_SECONDS, MAX_API_TIMEOUT_SECONDS)
        prefs.edit() {
                putInt(KEY_API_TIMEOUT_SECONDS, normalized)
            }
    }

    fun loadThemeMode(): ThemeMode {
        val saved = prefs.getString(KEY_THEME_MODE, ThemeMode.FOLLOW_SYSTEM.prefValue)
        return ThemeMode.fromPref(saved)
    }

    fun loadAppLanguage(): AppLanguage {
        val saved = prefs.getString(KEY_APP_LANGUAGE, AppLanguage.FOLLOW_SYSTEM.prefValue)
        return AppLanguage.fromPref(saved)
    }

    fun saveAppLanguage(language: AppLanguage) {
        prefs.edit() {
                putString(KEY_APP_LANGUAGE, language.prefValue)
            }
    }

    fun saveThemeMode(mode: ThemeMode) {
        prefs.edit() {
                putString(KEY_THEME_MODE, mode.prefValue)
            }
    }

    fun loadReadingDisplayMode(): ReadingDisplayMode {
        val saved = prefs.getString(KEY_READING_DISPLAY_MODE, ReadingDisplayMode.FIT_WIDTH.prefValue)
        return ReadingDisplayMode.fromPref(saved)
    }

    fun saveReadingDisplayMode(mode: ReadingDisplayMode) {
        prefs.edit() {
                putString(KEY_READING_DISPLAY_MODE, mode.prefValue)
            }
    }

    fun loadReadingPageAnimationMode(): ReadingPageAnimationMode {
        val saved = prefs.getString(
            KEY_READING_PAGE_ANIMATION_MODE,
            ReadingPageAnimationMode.HORIZONTAL_SLIDE.prefValue
        )
        return ReadingPageAnimationMode.fromPref(saved)
    }

    fun saveReadingPageAnimationMode(mode: ReadingPageAnimationMode) {
        prefs.edit() {
                putString(KEY_READING_PAGE_ANIMATION_MODE, mode.prefValue)
            }
    }

    fun loadTranslationBubbleOpacityPercent(): Int {
        val saved = prefs.getInt(
            KEY_TRANSLATION_BUBBLE_OPACITY_PERCENT,
            DEFAULT_TRANSLATION_BUBBLE_OPACITY_PERCENT
        )
        return saved.coerceIn(
            MIN_TRANSLATION_BUBBLE_OPACITY_PERCENT,
            MAX_TRANSLATION_BUBBLE_OPACITY_PERCENT
        )
    }

    fun loadTranslationBubbleOpacity(): Float {
        return loadTranslationBubbleOpacityPercent() / 100f
    }

    fun saveTranslationBubbleOpacityPercent(value: Int) {
        val normalized = value.coerceIn(
            MIN_TRANSLATION_BUBBLE_OPACITY_PERCENT,
            MAX_TRANSLATION_BUBBLE_OPACITY_PERCENT
        )
        prefs.edit() {
                putInt(KEY_TRANSLATION_BUBBLE_OPACITY_PERCENT, normalized)
            }
    }

    fun loadLinkSource(): LinkSource {
        val saved = prefs.getString(KEY_LINK_SOURCE, LinkSource.GITHUB.prefValue)
        return LinkSource.fromPref(saved)
    }

    fun saveLinkSource(source: LinkSource) {
        prefs.edit() {
                putString(KEY_LINK_SOURCE, source.prefValue)
            }
    }

    fun loadLlmParameters(): LlmParameterSettings {
        return LlmParameterSettings(
            temperature = readDoubleWithDefault(KEY_LLM_TEMPERATURE, DEFAULT_LLM_TEMPERATURE),
            topP = readDoubleWithDefault(KEY_LLM_TOP_P, DEFAULT_LLM_TOP_P),
            topK = readIntOptional(KEY_LLM_TOP_K),
            maxOutputTokens = readIntOptional(KEY_LLM_MAX_OUTPUT_TOKENS),
            enableThinking = prefs.getBoolean(KEY_LLM_ENABLE_THINKING, DEFAULT_LLM_ENABLE_THINKING),
            thinkingBudget = readIntOptional(KEY_LLM_THINKING_BUDGET),
            frequencyPenalty = readDoubleOptional(KEY_LLM_FREQUENCY_PENALTY),
            presencePenalty = readDoubleOptional(KEY_LLM_PRESENCE_PENALTY)
        )
    }

    fun saveLlmParameters(settings: LlmParameterSettings) {
        prefs.edit() {
                putOptionalString(KEY_LLM_TEMPERATURE, settings.temperature)
                .putOptionalString(KEY_LLM_TOP_P, settings.topP)
                .putOptionalString(KEY_LLM_TOP_K, settings.topK)
                .putOptionalString(KEY_LLM_MAX_OUTPUT_TOKENS, settings.maxOutputTokens)
                .putBoolean(KEY_LLM_ENABLE_THINKING, settings.enableThinking)
                .putOptionalString(KEY_LLM_THINKING_BUDGET, settings.thinkingBudget)
                .putOptionalString(KEY_LLM_FREQUENCY_PENALTY, settings.frequencyPenalty)
                .putOptionalString(KEY_LLM_PRESENCE_PENALTY, settings.presencePenalty)
            }
    }

    fun loadCustomRequestParameters(): List<CustomRequestParameter> {
        val raw = prefs.getString(KEY_CUSTOM_REQUEST_PARAMETERS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val key = item.optString("key").trim()
                    val value = item.optString("value")
                    val enabled = item.optBoolean("enabled", true)
                    if (key.isBlank() && value.isBlank()) continue
                    add(CustomRequestParameter(key = key, value = value, enabled = enabled))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveCustomRequestParameters(parameters: List<CustomRequestParameter>) {
        val array = JSONArray()
        parameters.forEach { parameter ->
            val key = parameter.key.trim()
            val value = parameter.value
            if (key.isBlank() && value.isBlank()) return@forEach
            array.put(
                JSONObject()
                    .put("key", key)
                    .put("value", value)
                    .put("enabled", parameter.enabled)
            )
        }
        prefs.edit() {
            putString(KEY_CUSTOM_REQUEST_PARAMETERS, array.toString())
        }
    }

    private fun readDoubleWithDefault(key: String, defaultValue: Double): Double? {
        if (!prefs.contains(key)) return defaultValue
        val value = prefs.getString(key, null)
        if (value.isNullOrBlank()) return null
        return value.toDoubleOrNull()
    }

    private fun readDoubleOptional(key: String): Double? {
        if (!prefs.contains(key)) return null
        val value = prefs.getString(key, null)
        if (value.isNullOrBlank()) return null
        return value.toDoubleOrNull()
    }

    private fun readIntOptional(key: String): Int? {
        if (!prefs.contains(key)) return null
        val value = prefs.getString(key, null)
        if (value.isNullOrBlank()) return null
        return value.toIntOrNull()
    }

    companion object {
        private const val PREFS_NAME = "manga_translate_settings"
        private const val KEY_API_URL = "api_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_API_FORMAT = "api_format"
        private const val KEY_OCR_USE_LOCAL = "ocr_use_local"
        private const val KEY_OCR_API_URL = "ocr_api_url"
        private const val KEY_OCR_API_KEY = "ocr_api_key"
        private const val KEY_OCR_MODEL_NAME = "ocr_model_name"
        private const val KEY_FLOATING_API_URL = "floating_api_url"
        private const val KEY_FLOATING_API_KEY = "floating_api_key"
        private const val KEY_FLOATING_MODEL_NAME = "floating_model_name"
        private const val KEY_FLOATING_USE_VL_DIRECT_TRANSLATE = "floating_use_vl_direct_translate"
        private const val KEY_FLOATING_VL_TRANSLATE_CONCURRENCY = "floating_vl_translate_concurrency"
        private const val KEY_FLOATING_PROOFREADING_MODE_ENABLED =
            "floating_proofreading_mode_enabled"
        private const val KEY_OCR_API_TIMEOUT_SECONDS = "ocr_api_timeout_seconds"
        private const val KEY_HORIZONTAL_TEXT = "horizontal_text_layout"
        private const val KEY_MODEL_IO_LOGGING = "model_io_logging"
        private const val KEY_MAX_CONCURRENCY = "max_concurrency"
        private const val KEY_API_TIMEOUT_SECONDS = "api_timeout_seconds"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_READING_DISPLAY_MODE = "reading_display_mode"
        private const val KEY_READING_PAGE_ANIMATION_MODE = "reading_page_animation_mode"
        private const val KEY_TRANSLATION_BUBBLE_OPACITY_PERCENT = "translation_bubble_opacity_percent"
        private const val KEY_LINK_SOURCE = "link_source"
        private const val KEY_LLM_TEMPERATURE = "llm_temperature"
        private const val KEY_LLM_TOP_P = "llm_top_p"
        private const val KEY_LLM_TOP_K = "llm_top_k"
        private const val KEY_LLM_MAX_OUTPUT_TOKENS = "llm_max_output_tokens"
        private const val KEY_LLM_ENABLE_THINKING = "llm_enable_thinking"
        private const val KEY_LLM_THINKING_BUDGET = "llm_thinking_budget"
        private const val KEY_LLM_FREQUENCY_PENALTY = "llm_frequency_penalty"
        private const val KEY_LLM_PRESENCE_PENALTY = "llm_presence_penalty"
        private const val KEY_CUSTOM_REQUEST_PARAMETERS = "custom_request_parameters"
        private const val DEFAULT_LLM_TEMPERATURE = 0.8
        private const val DEFAULT_LLM_TOP_P = 1.0
        private const val DEFAULT_LLM_ENABLE_THINKING = false
        private const val DEFAULT_API_URL = "https://api.siliconflow.cn/v1"
        private const val DEFAULT_MODEL = "Qwen/Qwen3.5-35B-A3B"
        private const val DEFAULT_OCR_API_URL = "https://api.siliconflow.cn/v1"
        private const val DEFAULT_OCR_MODEL_NAME = "Qwen/Qwen3-VL-8B-Instruct"
        private const val DEFAULT_OCR_API_TIMEOUT_SECONDS = 300
        private const val MIN_OCR_API_TIMEOUT_SECONDS = 30
        private const val MAX_OCR_API_TIMEOUT_SECONDS = 1200
        private const val DEFAULT_FLOATING_VL_TRANSLATE_CONCURRENCY = 1
        private const val MIN_FLOATING_VL_TRANSLATE_CONCURRENCY = 1
        private const val MAX_FLOATING_VL_TRANSLATE_CONCURRENCY = 16
        private const val DEFAULT_MAX_CONCURRENCY = 3
        private const val MIN_MAX_CONCURRENCY = 1
        private const val MAX_MAX_CONCURRENCY = 50
        private const val DEFAULT_API_TIMEOUT_SECONDS = 300
        private const val MIN_API_TIMEOUT_SECONDS = 30
        private const val MAX_API_TIMEOUT_SECONDS = 1200
        private const val DEFAULT_TRANSLATION_BUBBLE_OPACITY_PERCENT = 80
        private const val MIN_TRANSLATION_BUBBLE_OPACITY_PERCENT = 0
        private const val MAX_TRANSLATION_BUBBLE_OPACITY_PERCENT = 100
    }
}

data class LlmParameterSettings(
    val temperature: Double?,
    val topP: Double?,
    val topK: Int?,
    val maxOutputTokens: Int?,
    val enableThinking: Boolean,
    val thinkingBudget: Int?,
    val frequencyPenalty: Double?,
    val presencePenalty: Double?
)

private fun android.content.SharedPreferences.Editor.putOptionalString(
    key: String,
    value: Number?
): android.content.SharedPreferences.Editor {
    putString(key, value?.toString().orEmpty())
    return this
}
