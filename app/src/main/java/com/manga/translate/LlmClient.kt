package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

class LlmClient(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private val promptCache = mutableMapOf<String, LlmPromptConfig>()

    fun isConfigured(apiSettings: ApiSettings? = null): Boolean {
        return (apiSettings ?: settingsStore.load()).isValid()
    }

    fun isOcrConfigured(): Boolean {
        return settingsStore.loadOcrApiSettings().isValid()
    }

    suspend fun translate(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String = PROMPT_CONFIG_ASSET,
        requestTimeoutMs: Int? = null,
        retryCount: Int = RETRY_COUNT,
        apiSettings: ApiSettings? = null
    ): LlmTranslationResult? =
        withContext(Dispatchers.IO) {
            val content = requestContent(
                text = text,
                glossary = glossary,
                promptAsset = promptAsset,
                useJsonPayload = true,
                requestTimeoutMs = requestTimeoutMs,
                retryCount = retryCount,
                apiSettings = apiSettings
            )
                ?: return@withContext null
            parseTranslationContent(content)
    }

    suspend fun extractGlossary(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        requestContent(text, glossary, promptAsset, useJsonPayload = true)
            ?.let { parseGlossaryContent(it) }
    }

    suspend fun fetchModelList(
        apiUrl: String,
        apiKey: String,
        apiFormat: ApiFormat
    ): List<String> = withContext(Dispatchers.IO) {
        requestModelList(apiUrl, apiKey, apiFormat)
    }

    suspend fun recognizeImageText(image: Bitmap): String? = withContext(Dispatchers.IO) {
        val ocrSettings = settingsStore.loadOcrApiSettings()
        if (!ocrSettings.isValid() || ocrSettings.useLocalOcr) {
            return@withContext null
        }
        val endpoint = buildOpenAiEndpoint(ocrSettings.apiUrl)
        val payload = buildImageOcrPayload(ocrSettings.modelName, image)
        val timeoutMs = ocrSettings.timeoutSeconds * 1000
        var lastErrorCode: String? = null
        var lastErrorBody: String? = null
        for (attempt in 1..RETRY_COUNT) {
            currentCoroutineContext().ensureActive()
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${ocrSettings.apiKey}")
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
            }
            val result = try {
                connection.outputStream.use { output ->
                    output.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    AppLogger.log("LlmClient", "OCR HTTP $code on $endpoint: ${summarizeBody(body)}")
                    lastErrorCode = "HTTP $code"
                    lastErrorBody = body
                    null
                } else {
                    parseResponseContent(body, ApiFormat.OPENAI_COMPATIBLE)?.trim()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("LlmClient", "OCR request failed on $endpoint (attempt $attempt)", e)
                lastErrorCode = "NETWORK_ERROR"
                null
            } finally {
                connection.disconnect()
            }
            if (result != null || attempt == RETRY_COUNT) {
                if (result != null) return@withContext result
                if (lastErrorCode != null) {
                    AppLogger.log(
                        "LlmClient",
                        "OCR request failed on $endpoint: $lastErrorCode, body=${summarizeBody(lastErrorBody)}"
                    )
                }
                return@withContext null
            }
            maybeBackoffBeforeRetry(attempt, RETRY_COUNT, lastErrorCode)
        }
        null
    }

    suspend fun translateImageBubble(
        image: Bitmap,
        promptAsset: String,
        requestTimeoutMs: Int? = null,
        retryCount: Int = RETRY_COUNT,
        apiSettings: ApiSettings? = null
    ): String? = withContext(Dispatchers.IO) {
        requestImageContent(
            image = image,
            promptAsset = promptAsset,
            requestTimeoutMs = requestTimeoutMs,
            retryCount = retryCount,
            apiSettings = apiSettings
        )?.let { parseImageTranslationContent(it) }
    }

    private suspend fun requestContent(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String,
        useJsonPayload: Boolean,
        requestTimeoutMs: Int? = null,
        retryCount: Int = RETRY_COUNT,
        apiSettings: ApiSettings? = null
    ): String? {
        val settings = apiSettings ?: settingsStore.load()
        if (!settings.isValid()) return null
        val selectedModel = selectModelForRequest(settings.modelName)
        val endpoint = buildEndpoint(settings, selectedModel)
        val payload = buildPayload(
            text = text,
            glossary = glossary,
            settings = settings,
            modelName = selectedModel,
            promptAsset = promptAsset,
            useJsonPayload = useJsonPayload,
            apiFormat = settings.apiFormat
        )
        val logModelIo = settingsStore.loadModelIoLogging()
        if (logModelIo) {
            AppLogger.log("LlmClient", "Model input ($promptAsset): $payload")
            AppLogger.log("LlmClient", "Selected model: $selectedModel")
        }
        val timeoutMs = requestTimeoutMs?.coerceAtLeast(1_000) ?: settingsStore.loadApiTimeoutMs()
        val retries = retryCount.coerceAtLeast(1)
        var lastErrorCode: String? = null
        var lastErrorBody: String? = null
        for (attempt in 1..retries) {
            currentCoroutineContext().ensureActive()
            val connection = openTextRequestConnection(endpoint, settings, timeoutMs)
            val result = try {
                connection.outputStream.use { output ->
                    output.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    AppLogger.log(
                        "LlmClient",
                        "HTTP $code on $endpoint: ${summarizeBody(body)}"
                    )
                    lastErrorCode = "HTTP $code"
                    lastErrorBody = body
                    null
                } else {
                    val content = parseResponseContent(body, settings.apiFormat)
                    if (content == null) {
                        AppLogger.log(
                            "LlmClient",
                            "Empty or invalid response content from $endpoint"
                        )
                        lastErrorCode = "INVALID_RESPONSE"
                        lastErrorBody = body
                    } else if (logModelIo) {
                        AppLogger.log("LlmClient", "Model output: $content")
                    }
                    content
                }
            } catch (e: SocketTimeoutException) {
                AppLogger.log("LlmClient", "Request timeout on $endpoint (attempt $attempt)", e)
                lastErrorCode = "TIMEOUT"
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("LlmClient", "Request failed on $endpoint (attempt $attempt)", e)
                lastErrorCode = "NETWORK_ERROR"
                null
            } finally {
                connection.disconnect()
            }
            if (result != null || attempt == retries) {
                if (result != null) {
                    return result
                }
                if (lastErrorCode != null) {
                    AppLogger.log(
                        "LlmClient",
                        "Request failed on $endpoint: $lastErrorCode, body=${summarizeBody(lastErrorBody)}"
                    )
                    throw LlmRequestException(lastErrorCode, lastErrorBody)
                }
                return null
            }
            maybeBackoffBeforeRetry(attempt, retries, lastErrorCode)
        }
        return null
    }

    private suspend fun requestImageContent(
        image: Bitmap,
        promptAsset: String,
        requestTimeoutMs: Int? = null,
        retryCount: Int = RETRY_COUNT,
        apiSettings: ApiSettings? = null
    ): String? {
        val settings = apiSettings ?: settingsStore.load()
        if (!settings.isValid()) return null
        val selectedModel = selectModelForRequest(settings.modelName)
        val endpoint = buildEndpoint(settings, selectedModel)
        val payload = buildImageTranslationPayload(
            settings = settings,
            modelName = selectedModel,
            image = image,
            promptAsset = promptAsset,
            apiFormat = settings.apiFormat
        )
        val logModelIo = settingsStore.loadModelIoLogging()
        if (logModelIo) {
            AppLogger.log("LlmClient", "Model input ($promptAsset): $payload")
            AppLogger.log("LlmClient", "Selected model: $selectedModel")
        }
        val timeoutMs = requestTimeoutMs?.coerceAtLeast(1_000) ?: settingsStore.loadApiTimeoutMs()
        val retries = retryCount.coerceAtLeast(1)
        var lastErrorCode: String? = null
        var lastErrorBody: String? = null
        for (attempt in 1..retries) {
            currentCoroutineContext().ensureActive()
            val connection = openTextRequestConnection(endpoint, settings, timeoutMs)
            val result = try {
                connection.outputStream.use { output ->
                    output.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    AppLogger.log("LlmClient", "HTTP $code on $endpoint: ${summarizeBody(body)}")
                    lastErrorCode = "HTTP $code"
                    lastErrorBody = body
                    null
                } else {
                    val content = parseResponseContent(body, settings.apiFormat)
                    if (content == null) {
                        AppLogger.log(
                            "LlmClient",
                            "Empty or invalid image response content from $endpoint"
                        )
                        lastErrorCode = "INVALID_RESPONSE"
                        lastErrorBody = body
                    } else if (logModelIo) {
                        AppLogger.log("LlmClient", "Model output: $content")
                    }
                    content
                }
            } catch (e: SocketTimeoutException) {
                AppLogger.log("LlmClient", "Request timeout on $endpoint (attempt $attempt)", e)
                lastErrorCode = "TIMEOUT"
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("LlmClient", "Request failed on $endpoint (attempt $attempt)", e)
                lastErrorCode = "NETWORK_ERROR"
                null
            } finally {
                connection.disconnect()
            }
            if (result != null || attempt == retries) {
                if (result != null) {
                    return result
                }
                if (lastErrorCode != null) {
                    AppLogger.log(
                        "LlmClient",
                        "Request failed on $endpoint: $lastErrorCode, body=${summarizeBody(lastErrorBody)}"
                    )
                    throw LlmRequestException(lastErrorCode, lastErrorBody)
                }
                return null
            }
            maybeBackoffBeforeRetry(attempt, retries, lastErrorCode)
        }
        return null
    }

    private fun buildOpenAiEndpoint(baseUrl: String): String {
        val trimmed = normalizeOpenAiBaseUrl(baseUrl)
        return when {
            trimmed.endsWith("/v1/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    private fun buildOpenAiModelsEndpoint(baseUrl: String): String {
        val trimmed = normalizeOpenAiBaseUrl(baseUrl)
        return when {
            trimmed.endsWith("/v1/chat/completions") -> trimmed.removeSuffix("/chat/completions") + "/models"
            trimmed.endsWith("/v1/models") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/models"
            else -> "$trimmed/v1/models"
        }
    }

    private fun normalizeOpenAiBaseUrl(baseUrl: String): String {
        return baseUrl.trim().trimEnd('/').removeSuffix("/models")
    }

    private fun buildEndpoint(settings: ApiSettings, modelName: String): String {
        return when (settings.apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE -> buildOpenAiEndpoint(settings.apiUrl)
            ApiFormat.GEMINI -> buildGeminiGenerateEndpoint(settings.apiUrl, modelName, settings.apiKey)
        }
    }

    private fun buildGeminiGenerateEndpoint(baseUrl: String, modelName: String, apiKey: String): String {
        val trimmed = baseUrl.trimEnd('/')
        val normalizedModel = normalizeGeminiModelName(modelName)
        val baseEndpoint = when {
            trimmed.contains(":generateContent") -> trimmed
            trimmed.endsWith("/v1beta") || trimmed.endsWith("/v1") -> {
                "$trimmed/$normalizedModel:generateContent"
            }
            else -> "$trimmed/v1beta/$normalizedModel:generateContent"
        }
        return appendApiKeyQuery(baseEndpoint, apiKey)
    }

    private fun buildGeminiModelsEndpoint(baseUrl: String, apiKey: String): String {
        val trimmed = baseUrl.trimEnd('/')
        val baseEndpoint = when {
            trimmed.endsWith("/models") -> trimmed
            trimmed.endsWith("/v1beta") || trimmed.endsWith("/v1") -> "$trimmed/models"
            else -> "$trimmed/v1beta/models"
        }
        return appendApiKeyQuery(baseEndpoint, apiKey)
    }

    private fun normalizeGeminiModelName(modelName: String): String {
        val trimmed = modelName.trim().removePrefix("/")
        return if (trimmed.startsWith("models/")) trimmed else "models/$trimmed"
    }

    private fun appendApiKeyQuery(endpoint: String, apiKey: String): String {
        if (apiKey.isBlank()) return endpoint
        val separator = if (endpoint.contains("?")) "&" else "?"
        return endpoint + separator + "key=" + URLEncoder.encode(apiKey, Charsets.UTF_8.name())
    }

    private fun buildPayload(
        text: String,
        glossary: Map<String, String>,
        settings: ApiSettings,
        modelName: String,
        promptAsset: String,
        useJsonPayload: Boolean,
        apiFormat: ApiFormat
    ): JSONObject {
        val config = getPromptConfig(promptAsset)
        return when (apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE -> buildOpenAiPayload(
                text = text,
                glossary = glossary,
                settings = settings,
                modelName = modelName,
                config = config,
                useJsonPayload = useJsonPayload
            )
            ApiFormat.GEMINI -> buildGeminiTextPayload(
                text = text,
                glossary = glossary,
                config = config,
                useJsonPayload = useJsonPayload
            )
        }
    }

    private fun buildOpenAiPayload(
        text: String,
        glossary: Map<String, String>,
        settings: ApiSettings,
        modelName: String,
        config: LlmPromptConfig,
        useJsonPayload: Boolean
    ): JSONObject {
        val llmParams = settingsStore.loadLlmParameters()
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", config.systemPrompt)
        )
        for (message in config.exampleMessages) {
            messages.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.content)
            )
        }
        messages.put(
            JSONObject()
                .put("role", "user")
                .put(
                    "content",
                    config.userPromptPrefix + if (useJsonPayload) {
                        buildUserPayload(text, glossary)
                    } else {
                        text
                    }
                )
        )
        val payload = JSONObject()
            .put("model", modelName)
            .put("messages", messages)
        applyOpenAiSamplingParams(payload, llmParams, settings)
        applyOpenAiExtraBody(payload, llmParams, settings)
        applyCustomRequestParameters(payload, settings.apiFormat)
        return payload
    }

    private fun buildGeminiTextPayload(
        text: String,
        glossary: Map<String, String>,
        config: LlmPromptConfig,
        useJsonPayload: Boolean
    ): JSONObject {
        val userText = config.userPromptPrefix + if (useJsonPayload) {
            buildUserPayload(text, glossary)
        } else {
            text
        }
        val payload = JSONObject()
            .put("contents", buildGeminiContents(config, buildGeminiUserParts(buildGeminiTextPart(userText))))
        if (config.systemPrompt.isNotBlank()) {
            payload.put("systemInstruction", buildGeminiSystemInstruction(config.systemPrompt))
        }
        buildGeminiGenerationConfig(useJsonPayload)?.let { payload.put("generationConfig", it) }
        applyCustomRequestParameters(payload, ApiFormat.GEMINI)
        return payload
    }

    private fun selectModelForRequest(modelConfig: String): String {
        val models = parseModelCandidates(modelConfig)
        if (models.isEmpty()) return modelConfig.trim()
        val index = requestCounter.getAndIncrement().mod(models.size.toLong()).toInt()
        return models[index]
    }

    private fun parseModelCandidates(modelConfig: String): List<String> {
        return modelConfig.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun parseResponseContent(body: String, apiFormat: ApiFormat): String? {
        return when (apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE -> parseOpenAiResponseContent(body)
            ApiFormat.GEMINI -> parseGeminiResponseContent(body)
        }
    }

    private fun parseOpenAiResponseContent(body: String): String? {
        return try {
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices") ?: return null
            val first = choices.optJSONObject(0) ?: return null
            val message = first.optJSONObject("message") ?: return null
            val rawContent = message.opt("content")
            when (rawContent) {
                is String -> rawContent.trim().ifBlank { null }
                is JSONArray -> {
                    val parts = ArrayList<String>(rawContent.length())
                    for (i in 0 until rawContent.length()) {
                        val item = rawContent.opt(i)
                        when (item) {
                            is String -> if (item.isNotBlank()) parts.add(item.trim())
                            is JSONObject -> {
                                val text = item.optString("text").trim()
                                if (text.isNotBlank()) parts.add(text)
                            }
                        }
                    }
                    parts.joinToString("\n").trim().ifBlank { null }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseGeminiResponseContent(body: String): String? {
        return try {
            val json = JSONObject(body)
            val candidates = json.optJSONArray("candidates") ?: return null
            val first = candidates.optJSONObject(0) ?: return null
            val content = first.optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            val texts = ArrayList<String>(parts.length())
            for (i in 0 until parts.length()) {
                val text = parts.optJSONObject(i)?.optString("text")?.trim().orEmpty()
                if (text.isNotBlank()) {
                    texts.add(text)
                }
            }
            texts.joinToString("\n").trim().ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildImageOcrPayload(modelName: String, image: Bitmap): JSONObject {
        val config = getPromptConfig(OCR_PROMPT_CONFIG_ASSET)
        val imageBase64 = encodeBitmapToBase64(image)
        val userInstruction = config.userPromptPrefix.ifBlank { DEFAULT_OCR_USER_PROMPT }
        val userContent = JSONArray()
            .put(
                JSONObject()
                    .put("type", "text")
                    .put("text", userInstruction)
            )
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                    )
            )
        val messages = JSONArray()
        if (config.systemPrompt.isNotBlank()) {
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", config.systemPrompt)
            )
        }
        for (message in config.exampleMessages) {
            messages.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.content)
            )
        }
        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", userContent)
        )
        return JSONObject()
            .put("model", modelName)
            .put("messages", messages)
    }

    private fun buildImageTranslationPayload(
        settings: ApiSettings,
        modelName: String,
        image: Bitmap,
        promptAsset: String,
        apiFormat: ApiFormat
    ): JSONObject {
        return when (apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE -> buildOpenAiImageTranslationPayload(
                settings = settings,
                modelName = modelName,
                image = image,
                promptAsset = promptAsset
            )
            ApiFormat.GEMINI -> buildGeminiImageTranslationPayload(image, promptAsset)
        }
    }

    private fun buildOpenAiImageTranslationPayload(
        settings: ApiSettings,
        modelName: String,
        image: Bitmap,
        promptAsset: String
    ): JSONObject {
        val llmParams = settingsStore.loadLlmParameters()
        val config = getPromptConfig(promptAsset)
        val imageBase64 = encodeBitmapToBase64(image)
        val messages = JSONArray()
        if (config.systemPrompt.isNotBlank()) {
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", config.systemPrompt)
            )
        }
        for (message in config.exampleMessages) {
            messages.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.content)
            )
        }
        messages.put(
            JSONObject()
                .put("role", "user")
                .put(
                    "content",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "text")
                                .put("text", config.userPromptPrefix.ifBlank {
                                    DEFAULT_IMAGE_TRANSLATION_USER_PROMPT
                                })
                        )
                        .put(
                            JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                                )
                        )
                )
        )
        val payload = JSONObject()
            .put("model", modelName)
            .put("messages", messages)
        applyOpenAiSamplingParams(payload, llmParams, settings)
        applyOpenAiExtraBody(payload, llmParams, settings)
        applyCustomRequestParameters(payload, settings.apiFormat)
        return payload
    }

    private fun applyOpenAiSamplingParams(
        payload: JSONObject,
        llmParams: LlmParameterSettings,
        settings: ApiSettings
    ) {
        llmParams.temperature?.let { payload.put("temperature", it) }
        llmParams.topP?.let { payload.put("top_p", it) }
        llmParams.topK?.let { payload.put("top_k", it) }
        llmParams.maxOutputTokens?.let { payload.put("max_output_tokens", it) }
        llmParams.frequencyPenalty?.let { payload.put("frequency_penalty", it) }
        llmParams.presencePenalty?.let { payload.put("presence_penalty", it) }
    }

    private fun buildGeminiImageTranslationPayload(
        image: Bitmap,
        promptAsset: String
    ): JSONObject {
        val config = getPromptConfig(promptAsset)
        val userText = config.userPromptPrefix.ifBlank {
            DEFAULT_IMAGE_TRANSLATION_USER_PROMPT
        }
        val payload = JSONObject().put(
            "contents",
            buildGeminiContents(
                config,
                buildGeminiUserParts(
                    buildGeminiTextPart(userText),
                    buildGeminiInlineImagePart(encodeBitmapToBase64(image))
                )
            )
        )
        if (config.systemPrompt.isNotBlank()) {
            payload.put("systemInstruction", buildGeminiSystemInstruction(config.systemPrompt))
        }
        buildGeminiGenerationConfig(useJsonPayload = false)?.let {
            payload.put("generationConfig", it)
        }
        applyCustomRequestParameters(payload, ApiFormat.GEMINI)
        return payload
    }

    private fun applyCustomRequestParameters(payload: JSONObject, apiFormat: ApiFormat) {
        val parameters = settingsStore.loadCustomRequestParameters()
        if (parameters.isEmpty()) return
        val reservedKeys = reservedRequestKeys(apiFormat)
        val seenKeys = LinkedHashSet<String>()
        parameters.forEach { parameter ->
            if (!parameter.enabled) return@forEach
            val key = parameter.key.trim()
            val value = parameter.value.trim()
            if (key.isBlank() && value.isBlank()) return@forEach
            if (key.isBlank()) {
                throw LlmRequestException("CUSTOM_PARAM_CONFLICT", "blank key")
            }
            if (!seenKeys.add(key)) {
                throw LlmRequestException("CUSTOM_PARAM_CONFLICT", key)
            }
            if (key in reservedKeys || payload.has(key)) {
                throw LlmRequestException("CUSTOM_PARAM_CONFLICT", key)
            }
            payload.put(key, parseCustomRequestParameterValue(key, parameter.value))
        }
    }

    private fun parseCustomRequestParameterValue(key: String, rawValue: String): Any {
        val trimmed = rawValue.trim()
        if (trimmed.equals("true", ignoreCase = true)) return true
        if (trimmed.equals("false", ignoreCase = true)) return false
        if (trimmed.equals("null", ignoreCase = true)) return JSONObject.NULL
        trimmed.toLongOrNull()?.let { return it }
        trimmed.toDoubleOrNull()?.let { return it }
        if (trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed) }
                .getOrElse { throw LlmRequestException("CUSTOM_PARAM_INVALID_VALUE", key) }
        }
        if (trimmed.startsWith("[")) {
            return runCatching { JSONArray(trimmed) }
                .getOrElse { throw LlmRequestException("CUSTOM_PARAM_INVALID_VALUE", key) }
        }
        return rawValue
    }

    private fun applyOpenAiExtraBody(
        payload: JSONObject,
        llmParams: LlmParameterSettings,
        settings: ApiSettings
    ) {
        if (!supportsSiliconFlowThinkingParams(settings)) return
        val extraBody = JSONObject()
            .put("enable_thinking", llmParams.enableThinking)
        llmParams.thinkingBudget?.let { extraBody.put("thinking_budget", it) }
        payload.put("extra_body", extraBody)
    }

    private fun supportsSiliconFlowThinkingParams(settings: ApiSettings): Boolean {
        if (settings.apiFormat != ApiFormat.OPENAI_COMPATIBLE) return false
        val normalized = settings.apiUrl.trim().lowercase()
        return normalized.startsWith("https://api.siliconflow.cn") ||
            normalized.startsWith("http://api.siliconflow.cn")
    }

    private fun encodeBitmapToBase64(image: Bitmap): String {
        val buffer = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, 90, buffer)
        return Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
    }

    private fun parseTranslationContent(content: String): LlmTranslationResult {
        val cleaned = stripCodeFence(content)
        val directFallback = parseTranslationFallback(cleaned)
        if (directFallback != null) {
            return directFallback
        }
        return try {
            val json = JSONObject(cleaned)
            val translation = extractTranslationText(json)
            if (translation.isBlank()) {
                AppLogger.log("LlmClient", "Missing translation field in response")
                throw LlmResponseException("MISSING_TRANSLATION", content)
            }
            val glossary = mutableMapOf<String, String>()
            val glossaryJson = json.optJSONObject("glossary_used")
            if (glossaryJson != null) {
                for (key in glossaryJson.keys()) {
                    val value = glossaryJson.optString(key).trim()
                    if (key.isNotBlank() && value.isNotBlank()) {
                        glossary[key] = value
                    }
                }
            }
            LlmTranslationResult(translation, glossary)
        } catch (e: LlmResponseException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(
                "LlmClient",
                "Invalid translation response format: ${summarizeBody(content)}",
                e
            )
            throw LlmResponseException("INVALID_FORMAT", content, e)
        }
    }

    private fun parseTranslationFallback(content: String): LlmTranslationResult? {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return null
        // Some OpenAI-compatible providers still return the translation as plain text.
        return LlmTranslationResult(trimmed, emptyMap())
    }

    private fun extractTranslationText(json: JSONObject): String {
        val directKeys = listOf("translation", "translated_text", "translatedText", "text", "content")
        for (key in directKeys) {
            val value = json.opt(key)
            when (value) {
                is String -> value.trim().takeIf { it.isNotBlank() }?.let { return it }
                is JSONObject -> extractTranslationText(value).takeIf { it.isNotBlank() }?.let { return it }
                is JSONArray -> joinJsonText(value).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        val nestedKeys = listOf("data", "result", "output", "response", "message")
        for (key in nestedKeys) {
            val nested = json.optJSONObject(key) ?: continue
            extractTranslationText(nested).takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun joinJsonText(array: JSONArray): String {
        val parts = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            when (val item = array.opt(i)) {
                is String -> item.trim().takeIf { it.isNotBlank() }?.let(parts::add)
                is JSONObject -> extractTranslationText(item).takeIf { it.isNotBlank() }?.let(parts::add)
            }
        }
        return parts.joinToString("\n").trim()
    }

    private fun parseImageTranslationContent(content: String): String? {
        val cleaned = stripCodeFence(content).trim()
        if (cleaned.isBlank()) return null
        return try {
            parseTranslationContent(cleaned).translation.trim().ifBlank { null }
        } catch (_: Exception) {
            // Some compatible providers may still return plain text for image translation.
            cleaned.ifBlank { null }
        }
    }

    private fun parseGlossaryContent(content: String): Map<String, String> {
        return try {
            val cleaned = stripCodeFence(content)
            val json = JSONObject(cleaned)
            val glossaryJson = json.optJSONObject("glossary_used") ?: return emptyMap()
            val glossary = mutableMapOf<String, String>()
            for (key in glossaryJson.keys()) {
                val value = glossaryJson.optString(key).trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    glossary[key] = value
                }
            }
            glossary
        } catch (e: Exception) {
            AppLogger.log("LlmClient", "Glossary parse failed", e)
            emptyMap()
        }
    }

    private suspend fun requestModelList(
        apiUrl: String,
        apiKey: String,
        apiFormat: ApiFormat
    ): List<String> {
        if (apiUrl.isBlank()) {
            throw LlmRequestException("MISSING_URL")
        }
        val endpoint = when (apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE -> buildOpenAiModelsEndpoint(apiUrl)
            ApiFormat.GEMINI -> buildGeminiModelsEndpoint(apiUrl, apiKey)
        }
        val timeoutMs = settingsStore.loadApiTimeoutMs()
        var lastErrorCode: String? = null
        var lastErrorBody: String? = null
        for (attempt in 1..RETRY_COUNT) {
            currentCoroutineContext().ensureActive()
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Content-Type", "application/json")
                if (apiFormat == ApiFormat.OPENAI_COMPATIBLE && apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            val result = try {
                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    AppLogger.log(
                        "LlmClient",
                        "Model list HTTP $code on $endpoint: ${summarizeBody(body)}"
                    )
                    lastErrorCode = "HTTP $code"
                    lastErrorBody = body
                    null
                } else {
                    val models = parseModelList(body, apiFormat)
                    if (models.isEmpty()) {
                        lastErrorCode = "EMPTY_RESPONSE"
                        lastErrorBody = body
                    }
                    models
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log(
                    "LlmClient",
                    "Model list request failed on $endpoint (attempt $attempt)",
                    e
                )
                lastErrorCode = "NETWORK_ERROR"
                null
            } finally {
                connection.disconnect()
            }
            if (!result.isNullOrEmpty() || attempt == RETRY_COUNT) {
                if (!result.isNullOrEmpty()) {
                    return result
                }
                if (lastErrorCode != null) {
                    AppLogger.log(
                        "LlmClient",
                        "Model list failed on $endpoint: $lastErrorCode, body=${summarizeBody(lastErrorBody)}"
                    )
                    throw LlmRequestException(lastErrorCode, lastErrorBody)
                }
                return emptyList()
            }
            maybeBackoffBeforeRetry(attempt, RETRY_COUNT, lastErrorCode)
        }
        return emptyList()
    }

    private suspend fun maybeBackoffBeforeRetry(
        attempt: Int,
        maxAttempts: Int,
        errorCode: String?
    ) {
        if (attempt >= maxAttempts || !shouldRetryWithBackoff(errorCode)) {
            return
        }
        val delayMs = (RETRY_BASE_DELAY_MS shl (attempt - 1)).coerceAtMost(RETRY_MAX_DELAY_MS)
        AppLogger.log(
            "LlmClient",
            "Retrying request after ${delayMs}ms backoff (attempt ${attempt + 1}/$maxAttempts, error=$errorCode)"
        )
        delay(delayMs.toLong())
    }

    private fun shouldRetryWithBackoff(errorCode: String?): Boolean {
        if (errorCode == null) return false
        if (errorCode == "TIMEOUT" || errorCode == "NETWORK_ERROR" || errorCode == "HTTP 408" || errorCode == "HTTP 429") {
            return true
        }
        if (!errorCode.startsWith("HTTP ")) {
            return false
        }
        val status = errorCode.removePrefix("HTTP ").toIntOrNull() ?: return false
        return status >= 500
    }

    private fun parseModelList(body: String, apiFormat: ApiFormat): List<String> {
        return when (apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE -> parseOpenAiModelList(body)
            ApiFormat.GEMINI -> parseGeminiModelList(body)
        }
    }

    private fun parseOpenAiModelList(body: String): List<String> {
        return try {
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return emptyList()
            val models = ArrayList<String>(data.length())
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id")?.trim().orEmpty()
                if (id.isNotBlank()) {
                    models.add(id)
                }
            }
            models
        } catch (e: Exception) {
            AppLogger.log("LlmClient", "Model list parse failed", e)
            emptyList()
        }
    }

    private fun parseGeminiModelList(body: String): List<String> {
        return try {
            val json = JSONObject(body)
            val modelsJson = json.optJSONArray("models") ?: return emptyList()
            val models = ArrayList<String>(modelsJson.length())
            for (i in 0 until modelsJson.length()) {
                val item = modelsJson.optJSONObject(i) ?: continue
                val id = item.optString("baseModelId").trim().ifBlank {
                    item.optString("name").trim().removePrefix("models/")
                }
                if (id.isNotBlank()) {
                    models.add(id)
                }
            }
            models
        } catch (e: Exception) {
            AppLogger.log("LlmClient", "Gemini model list parse failed", e)
            emptyList()
        }
    }

    private fun buildGeminiContents(config: LlmPromptConfig, userParts: JSONArray): JSONArray {
        val contents = JSONArray()
        for (message in config.exampleMessages) {
            val role = when (message.role.lowercase()) {
                "assistant", "model" -> "model"
                else -> "user"
            }
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", buildGeminiUserParts(buildGeminiTextPart(message.content)))
            )
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", userParts)
        )
        return contents
    }

    private fun buildGeminiSystemInstruction(systemPrompt: String): JSONObject {
        return JSONObject().put("parts", buildGeminiUserParts(buildGeminiTextPart(systemPrompt)))
    }

    private fun buildGeminiUserParts(vararg parts: JSONObject): JSONArray {
        val array = JSONArray()
        parts.forEach { array.put(it) }
        return array
    }

    private fun buildGeminiTextPart(text: String): JSONObject {
        return JSONObject().put("text", text)
    }

    private fun buildGeminiInlineImagePart(imageBase64: String): JSONObject {
        return JSONObject().put(
            "inline_data",
            JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", imageBase64)
        )
    }

    private fun buildGeminiGenerationConfig(useJsonPayload: Boolean): JSONObject? {
        val llmParams = settingsStore.loadLlmParameters()
        val config = JSONObject()
        if (useJsonPayload) {
            config.put("responseMimeType", "application/json")
        }
        llmParams.temperature?.let { config.put("temperature", it) }
        llmParams.topP?.let { config.put("topP", it) }
        llmParams.topK?.let { config.put("topK", it) }
        llmParams.maxOutputTokens?.let { config.put("maxOutputTokens", it) }
        llmParams.frequencyPenalty?.let { config.put("frequencyPenalty", it) }
        llmParams.presencePenalty?.let { config.put("presencePenalty", it) }
        return config.takeIf { it.length() > 0 }
    }

    private fun openTextRequestConnection(
        endpoint: String,
        settings: ApiSettings,
        timeoutMs: Int
    ): HttpURLConnection {
        return (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            if (settings.apiFormat == ApiFormat.OPENAI_COMPATIBLE) {
                setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            }
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
        }
    }

    private fun getPromptConfig(name: String): LlmPromptConfig {
        val resolvedName = PromptAssetResolver.resolve(appContext, name)
        return promptCache.getOrPut(resolvedName) { loadPromptConfig(resolvedName) }
    }

    private fun loadPromptConfig(name: String): LlmPromptConfig {
        val json = JSONObject(readAsset(name))
        val systemPrompt = json.optString("system_prompt")
        val userPromptPrefix = json.optString("user_prompt_prefix")
        val examplesJson = json.optJSONArray("example_messages") ?: JSONArray()
        val examples = ArrayList<PromptMessage>(examplesJson.length())
        for (i in 0 until examplesJson.length()) {
            val messageObj = examplesJson.optJSONObject(i) ?: continue
            val role = messageObj.optString("role")
            val content = messageObj.optString("content")
            if (role.isNotBlank() && content.isNotBlank()) {
                examples.add(PromptMessage(role, content))
            }
        }
        return LlmPromptConfig(systemPrompt, userPromptPrefix, examples)
    }

    private fun readAsset(name: String): String {
        return appContext.assets.open(name).bufferedReader().use { it.readText() }
    }

    private fun buildUserPayload(text: String, glossary: Map<String, String>): String {
        val glossaryJson = JSONObject()
        for ((key, value) in glossary) {
            glossaryJson.put(key, value)
        }
        return JSONObject()
            .put("text", text)
            .put("glossary", glossaryJson)
            .toString()
    }

    private fun stripCodeFence(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) {
            return trimmed
        }
        var inner = trimmed.removePrefix("```").removeSuffix("```").trim()
        if (inner.startsWith("json", ignoreCase = true)) {
            inner = inner.removePrefix("json").trim()
        }
        return inner
    }

    private fun summarizeBody(body: String?, limit: Int = 600): String {
        if (body.isNullOrBlank()) return "(empty)"
        val normalized = body.replace("\n", " ").replace("\r", " ").trim()
        return if (normalized.length <= limit) normalized else normalized.take(limit) + "...(truncated)"
    }

    companion object {
        private const val PROMPT_CONFIG_ASSET = "llm_prompts.json"
        private const val OCR_PROMPT_CONFIG_ASSET = "ocr_prompts.json"
        private const val DEFAULT_OCR_USER_PROMPT =
            "<image>\nExtract only visible text from this image. Do not describe objects, people, or scene. If no text is visible, return None."
        private const val DEFAULT_IMAGE_TRANSLATION_USER_PROMPT =
            "Translate only the text visible in this manga bubble into Simplified Chinese. Output only the translated text."
        private const val RETRY_COUNT = 3
        private const val RETRY_BASE_DELAY_MS = 750
        private const val RETRY_MAX_DELAY_MS = 4_000
        private val requestCounter = AtomicLong(0)

        fun reservedRequestKeys(apiFormat: ApiFormat): Set<String> {
            return when (apiFormat) {
                ApiFormat.OPENAI_COMPATIBLE -> setOf(
                    "model",
                    "messages",
                    "temperature",
                    "top_p",
                    "top_k",
                    "max_output_tokens",
                    "frequency_penalty",
                    "presence_penalty",
                    "extra_body"
                )
                ApiFormat.GEMINI -> setOf(
                    "contents",
                    "systemInstruction",
                    "generationConfig"
                )
            }
        }
    }
}

class LlmRequestException(
    val errorCode: String,
    val responseBody: String? = null
) : Exception("LLM request failed: $errorCode")

class LlmResponseException(
    val errorCode: String,
    val responseContent: String,
    cause: Throwable? = null
) : Exception("LLM response invalid: $errorCode", cause)

data class LlmTranslationResult(
    val translation: String,
    val glossaryUsed: Map<String, String>
)

private data class LlmPromptConfig(
    val systemPrompt: String,
    val userPromptPrefix: String,
    val exampleMessages: List<PromptMessage>
)

private data class PromptMessage(
    val role: String,
    val content: String
)
