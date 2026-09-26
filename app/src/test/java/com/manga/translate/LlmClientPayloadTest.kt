package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.model.ApiFormat
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.network.LlmClient
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.network.LlmRequestException
import com.manga.translate.network.LlmResponseException
import com.manga.translate.network.ResponseParser
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.CustomRequestParameter
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.FloatingTranslationCacheStore
import com.manga.translate.translation.FloatingBubbleTranslationCoordinator
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LlmClientPayloadTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private lateinit var settingsStore: SettingsStore

    @Before
    fun setUp() {
        context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        settingsStore = SettingsStore(context)
        settingsStore.saveLlmParameters(
            settingsStore.loadLlmParameters().copy(maxOutputTokens = 321)
        )
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `VL rejects missing invalid and malformed translation fields`() {
        val parser = ResponseParser()
        for (content in listOf("{}", """{"text":"source"}""", """{"translation":null}""", """{"translation":42}""", "{")) {
            try {
                parser.parseImageTranslationContent(content)
                throw AssertionError("Expected invalid VL response to be rejected: $content")
            } catch (e: LlmResponseException) {
                assertEquals(
                    if (content == "{") LlmErrorCode.InvalidFormat else LlmErrorCode.MissingTranslation,
                    e.errorCode
                )
            }
        }
        for (key in listOf("translation", "translated_text", "translatedText")) {
            assertEquals("", parser.parseImageTranslationContent(JSONObject().put(key, "").toString()))
        }
    }

    @Test
    fun `VL removes bubble for explicit empty translation`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"content":"{\"items\":[{\"id\":1,\"translation\":\"\"}]}"}}]}"""
        ))
        val coordinator = FloatingBubbleTranslationCoordinator(
            LlmClient(context, settingsStore), FloatingTranslationCacheStore(context), settingsStore
        )
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        try {
            val result = coordinator.translateImageBubbles(
                bitmap = bitmap,
                bubbles = listOf(BubbleTranslation.pending(1, RectF(0f, 0f, 20f, 20f), "")),
                timeoutMs = 10_000,
                retryCount = 0,
                promptAsset = "prompts/vl_page_prompts.json",
                apiSettings = apiSettings(ApiFormat.OPENAI_COMPATIBLE),
                concurrency = 1,
                maxConcurrency = 1,
                useCache = false
            )
            assertTrue(result.bubbles.isEmpty())
            assertFalse(result.timedOut)
            assertFalse(result.requiresVlModel)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `VL carries glossary and collects additions only when enabled across API formats`() = runBlocking {
        val client = LlmClient(context, settingsStore)
        val content = """{"translation":"Alice","glossary_used":{"new-name":"New Name"}}"""
        for (format in ApiFormat.entries) {
            for (enabled in listOf(false, true)) {
                val response = when (format) {
                    ApiFormat.OPENAI_COMPATIBLE -> JSONObject().put("choices", org.json.JSONArray().put(
                        JSONObject().put("message", JSONObject().put("content", content))
                    ))
                    ApiFormat.OPENAI_RESPONSES -> JSONObject().put("output_text", content)
                    ApiFormat.GEMINI -> JSONObject().put("candidates", org.json.JSONArray().put(
                        JSONObject().put("content", JSONObject().put("parts", org.json.JSONArray().put(
                            JSONObject().put("text", content)
                        )))
                    ))
                }
                server.enqueue(MockResponse().setBody(response.toString()))
                val result = client.translateImageBubble(
                    imageBase64 = "aW1hZ2U=",
                    promptAsset = "prompts/vl_bubble_prompts.json",
                    glossary = mapOf("existing-name" to "Alice"),
                    glossaryProcessingEnabled = enabled,
                    apiSettings = apiSettings(format)
                )
                val payload = JSONObject(server.takeRequest().body.readUtf8())
                val userText = when (format) {
                    ApiFormat.OPENAI_COMPATIBLE -> payload.getJSONArray("messages").let {
                        it.getJSONObject(it.length() - 1).getJSONArray("content").getJSONObject(0).getString("text")
                    }
                    ApiFormat.OPENAI_RESPONSES -> payload.getJSONArray("input").let {
                        it.getJSONObject(it.length() - 1).getJSONArray("content").getJSONObject(0).getString("text")
                    }
                    ApiFormat.GEMINI -> payload.getJSONArray("contents").let {
                        it.getJSONObject(it.length() - 1).getJSONArray("parts").getJSONObject(0).getString("text")
                    }
                }
                assertTrue(userText.contains("existing-name"))
                assertTrue(userText.contains("Alice"))
                assertEquals(enabled, userText.contains("glossary_used"))
                assertEquals("Alice", result?.translation)
                assertEquals(if (enabled) mapOf("new-name" to "New Name") else emptyMap(), result?.glossaryUsed)
            }
        }
    }

    @Test
    fun `VL bubble translation uses existing glossary but ignores returned additions`() = runBlocking {
        val content = JSONObject().put("items", org.json.JSONArray().apply {
            (1..2).forEach { put(JSONObject().put("id", it).put("translation", "translation-$it")) }
        }).put("glossary_used", JSONObject().put("source", "term"))
        server.enqueue(MockResponse().setBody(JSONObject().put("choices", org.json.JSONArray().put(
            JSONObject().put("message", JSONObject().put("content", content.toString()))
        )).toString()))
        val coordinator = FloatingBubbleTranslationCoordinator(
            LlmClient(context, settingsStore), FloatingTranslationCacheStore(context), settingsStore
        )
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        try {
            val result = coordinator.translateImageBubbles(
                bitmap = bitmap,
                bubbles = (1..2).map { BubbleTranslation.pending(it, RectF(0f, 0f, 20f, 20f), "") },
                timeoutMs = 10_000,
                retryCount = 0,
                promptAsset = "prompts/vl_page_prompts.json",
                glossary = mapOf("existing-name" to "Existing Name"),
                apiSettings = apiSettings(ApiFormat.OPENAI_COMPATIBLE),
                concurrency = 1,
                maxConcurrency = 1
            )
            assertEquals(2, result.bubbles.size)
            assertTrue(result.glossaryUsed.isEmpty())
            repeat(1) {
                val request = server.takeRequest().body.readUtf8()
                assertTrue(request.contains("existing-name"))
                assertFalse(request.contains("glossary_used"))
            }
            assertEquals(1, server.requestCount)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `VL page carries requested IDs and optional glossary across all image protocols`() = runBlocking {
        val client = LlmClient(context, settingsStore)
        for (format in ApiFormat.entries) {
            for (enabled in listOf(false, true)) {
                val content = """{"items":[{"id":9,"translation":"Alice"},{"id":4,"translation":""}],"glossary_used":{"new-name":"Alice"}}"""
                val response = when (format) {
                    ApiFormat.OPENAI_COMPATIBLE -> JSONObject().put("choices", org.json.JSONArray().put(
                        JSONObject().put("message", JSONObject().put("content", content))))
                    ApiFormat.OPENAI_RESPONSES -> JSONObject().put("output_text", content)
                    ApiFormat.GEMINI -> JSONObject().put("candidates", org.json.JSONArray().put(
                        JSONObject().put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", content))))))
                }
                server.enqueue(MockResponse().setBody(response.toString()))
                val result = client.translateImageItems("aW1hZ2U=", listOf(4, 9), "prompts/vl_page_prompts.json",
                    mapOf("existing-name" to "Alice"), enabled, 10000, 0, apiSettings(format).copy(translationStyle = "test-style"))
                assertEquals(listOf(9, 4), result?.items?.map { it.id })
                assertEquals(if (enabled) mapOf("new-name" to "Alice") else emptyMap<String, String>(), result?.glossaryUsed)
                val payload = server.takeRequest().body.readUtf8()
                assertTrue(payload.contains("[4,9]"))
                assertTrue(payload.contains("existing-name"))
                assertTrue(payload.contains("test-style"))
                assertFalse(payload.contains("Return only the translation field"))
                assertEquals(enabled, payload.contains("Also return glossary_used"))
            }
        }
    }

    @Test
    fun `VL preserves empty translation with or without formatting newlines`() = runBlocking {
        val client = LlmClient(context, settingsStore)
        for (content in listOf("""{"translation": ""}""", "{\n  \"translation\": \"\"\n}", """{"translation":"你好\\n世界"}""", "plain translation")) {
            server.enqueue(MockResponse().setBody(
                JSONObject().put("choices", org.json.JSONArray().put(
                    JSONObject().put("message", JSONObject().put("content", content))
                )).toString()
            ))
            val result = client.translateImageBubble(
                imageBase64 = "aW1hZ2U=",
                promptAsset = "prompts/vl_bubble_prompts.json",
                apiSettings = apiSettings(ApiFormat.OPENAI_COMPATIBLE)
            )
            val expected = if (content.startsWith("{")) JSONObject(content).getString("translation") else content
            assertEquals(expected, result?.translation)
        }
    }

    @Test
    fun `chat payload uses chat token parameter and not responses parameter`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"{\"translation\":\"ok\"}"}}]}"""
            )
        )

        val result = LlmClient(context, settingsStore).translate(
            text = "hello",
            glossary = emptyMap(),
            apiSettings = apiSettings(ApiFormat.OPENAI_COMPATIBLE)
        )
        val payload = JSONObject(server.takeRequest().body.readUtf8())

        assertEquals("ok", result?.translation)
        assertEquals(321, payload.getInt("max_tokens"))
        assertFalse(payload.has("max_output_tokens"))
        assertFalse(payload.has("max_completion_tokens"))
    }

    @Test
    fun `responses payload uses responses token parameter and not chat parameters`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"output_text":"{\"translation\":\"ok\"}"}"""
            )
        )

        val result = LlmClient(context, settingsStore).translate(
            text = "hello",
            glossary = emptyMap(),
            apiSettings = apiSettings(ApiFormat.OPENAI_RESPONSES)
        )
        val payload = JSONObject(server.takeRequest().body.readUtf8())

        assertEquals("ok", result?.translation)
        assertEquals(321, payload.getInt("max_output_tokens"))
        assertFalse(payload.has("max_tokens"))
        assertFalse(payload.has("max_completion_tokens"))
    }

    @Test
    fun `gemini payload uses custom parameters for the primary provider`() = runBlocking {
        settingsStore.saveCustomRequestParameters(
            listOf(
                CustomRequestParameter("first_parameter", "true"),
                CustomRequestParameter("second_parameter", "42")
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"{\"translation\":\"ok\"}"}]}}]}"""
            )
        )

        val result = LlmClient(context, settingsStore).translate(
            text = "hello",
            glossary = emptyMap(),
            apiSettings = apiSettings(ApiFormat.GEMINI)
        )
        val payload = JSONObject(server.takeRequest().body.readUtf8())

        assertEquals("ok", result?.translation)
        assertTrue(payload.getBoolean("first_parameter"))
        assertEquals(42, payload.getInt("second_parameter"))
    }

    @Test
    fun `gemini payload omits thinking config when thinking is disabled`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"{\"translation\":\"ok\"}"}]}}]}"""
            )
        )

        LlmClient(context, settingsStore).translate(
            text = "hello",
            glossary = emptyMap(),
            apiSettings = apiSettings(ApiFormat.GEMINI)
        )
        val payload = JSONObject(server.takeRequest().body.readUtf8())

        assertFalse(payload.getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test
    fun `truncated chat response raises response truncated error`() = runBlocking {
        // Content parses fine, but finish_reason=length means the translation was cut off;
        // it must surface as an error instead of being silently returned.
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"{\"translation\":\"ok\"}"},"finish_reason":"length"}]}"""
            )
        )

        val exception = org.junit.Assert.assertThrows(LlmResponseException::class.java) {
            runBlocking {
                LlmClient(context, settingsStore).translate(
                    text = "hello",
                    glossary = emptyMap(),
                    apiSettings = apiSettings(ApiFormat.OPENAI_COMPATIBLE)
                )
            }
        }

        assertEquals(LlmErrorCode.ResponseTruncated, exception.errorCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `incomplete responses api response raises response truncated error`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"output_text":"{\"translation\":\"ok\"}","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}"""
            )
        )

        val exception = org.junit.Assert.assertThrows(LlmResponseException::class.java) {
            runBlocking {
                LlmClient(context, settingsStore).translate(
                    text = "hello",
                    glossary = emptyMap(),
                    apiSettings = apiSettings(ApiFormat.OPENAI_RESPONSES)
                )
            }
        }

        assertEquals(LlmErrorCode.ResponseTruncated, exception.errorCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `truncated gemini response raises response truncated error`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"{\"translation\":\"ok\"}"}]},"finishReason":"MAX_TOKENS"}]}"""
            )
        )

        val exception = org.junit.Assert.assertThrows(LlmResponseException::class.java) {
            runBlocking {
                LlmClient(context, settingsStore).translate(
                    text = "hello",
                    glossary = emptyMap(),
                    apiSettings = apiSettings(ApiFormat.GEMINI)
                )
            }
        }

        assertEquals(LlmErrorCode.ResponseTruncated, exception.errorCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `chat response with stop finish reason is accepted`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"{\"translation\":\"ok\"}"},"finish_reason":"stop"}]}"""
            )
        )

        val result = LlmClient(context, settingsStore).translate(
            text = "hello",
            glossary = emptyMap(),
            apiSettings = apiSettings(ApiFormat.OPENAI_COMPATIBLE)
        )

        assertEquals("ok", result?.translation)
    }

    @Test
    fun `invalid response is not retried inside the client`() = runBlocking {
        // Retrying invalid content here as well as in the caller's silent-retry wrapper would
        // multiply into retries x silentRetries real requests, so the client must fail fast and
        // leave the second enqueued response untouched.
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("temporary failure"))

        org.junit.Assert.assertThrows(LlmResponseException::class.java) {
            runBlocking {
                LlmClient(context, settingsStore).translate(
                    text = "hello",
                    glossary = emptyMap(),
                    retryCount = 2,
                    apiSettings = apiSettings(ApiFormat.OPENAI_COMPATIBLE)
                )
            }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `transport failure is still retried inside the client`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("temporary failure"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("temporary failure"))

        org.junit.Assert.assertThrows(LlmRequestException::class.java) {
            runBlocking {
                LlmClient(context, settingsStore).translate(
                    text = "hello",
                    glossary = emptyMap(),
                    retryCount = 2,
                    apiSettings = apiSettings(ApiFormat.OPENAI_COMPATIBLE)
                )
            }
        }
        assertEquals(2, server.requestCount)
    }

    private fun apiSettings(apiFormat: ApiFormat): ApiSettings {
        return ApiSettings(
            apiUrl = server.url("/v1").toString(),
            apiKey = "test-key",
            modelName = "test-model",
            apiFormat = apiFormat
        )
    }
}
