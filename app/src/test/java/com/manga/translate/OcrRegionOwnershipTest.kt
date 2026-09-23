package com.manga.translate

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import com.manga.translate.model.OcrRecognitionResult
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.network.LlmGateway
import com.manga.translate.ocr.BubbleTextRecognizer
import com.manga.translate.ocr.OcrEngineRegistry
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OcrRegionOwnershipTest {
    @Test
    fun `full bounds recognition preserves immutable source and releases crop on success failure and cancellation`() = runBlocking {
        val registry = OcrEngineRegistry(RuntimeEnvironment.getApplication())
        for (width in listOf(40, 2200)) {
            for (outcome in listOf("success", "failure", "cancel")) {
                val mutable = Bitmap.createBitmap(width, 40, Bitmap.Config.ARGB_8888)
                val source = mutable.copy(Bitmap.Config.ARGB_8888, false)!!
                mutable.recycle()
                var captured: Bitmap? = null
                val gateway = Proxy.newProxyInstance(LlmGateway::class.java.classLoader,
                    arrayOf(LlmGateway::class.java)) { _, method, args ->
                    check(method.name == "recognizeImageText")
                    captured = args[0] as Bitmap
                    assertFalse(source.isRecycled)
                    when (outcome) {
                        "failure" -> throw IllegalStateException("API unavailable")
                        "cancel" -> throw CancellationException("cancelled")
                        else -> "text"
                    }
                } as LlmGateway
                try {
                    val recognizer = BubbleTextRecognizer(gateway, registry)
                    try {
                        val result = recognizer.recognizeRegion(source,
                            RectF(0f, 0f, width.toFloat(), 40f), TranslationLanguage.EN_TO_ZH, false, "test")
                        assertNotEquals("cancel", outcome)
                        if (outcome == "success") assertTrue(result is OcrRecognitionResult.Success)
                        else assertTrue(result is OcrRecognitionResult.Failure)
                    } catch (cancelled: CancellationException) {
                        assertEquals("cancel", outcome)
                    }
                    assertFalse(source.isRecycled)
                    assertNotSame(source, captured)
                    assertTrue(requireNotNull(captured).isRecycled)
                } finally { source.recycle() }
            }
        }
    }
}
