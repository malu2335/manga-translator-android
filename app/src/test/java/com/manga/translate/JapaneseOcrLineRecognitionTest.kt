package com.manga.translate

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.manga.translate.ocr.detectJapaneseTextLines
import com.manga.translate.ocr.OcrEngine
import com.manga.translate.ocr.recognizeJapaneseLines
import com.manga.translate.ocr.withBitmapCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JapaneseOcrLineRecognitionTest {

    @Test
    fun `rotated detection recovers columns and maps right to left reading order`() {
        val source = Bitmap.createBitmap(150, 250, Bitmap.Config.ARGB_8888)
        var rotatedInput: Bitmap? = null
        try {
            val boxes = detectJapaneseTextLines(source) { bitmap ->
                if (bitmap === source) {
                    // A paragraph incorrectly detected as one block and two short fragments.
                    listOf(RectF(0f, 55f, 149f, 235f), RectF(36f, 55f, 127f, 97f),
                        RectF(26f, 63f, 48f, 86f))
                } else {
                    rotatedInput = bitmap
                    assertEquals(250, bitmap.width)
                    assertEquals(150, bitmap.height)
                    listOf(RectF(59f, 20f, 161f, 51f), RectF(59f, 45f, 162f, 78f),
                        RectF(57f, 67f, 137f, 104f), RectF(61f, 93f, 210f, 126f))
                }
            }!!
            assertEquals(RectF(99f, 59f, 130f, 161f), boxes.first())
            assertEquals(RectF(24f, 61f, 57f, 210f), boxes.last())
            val engine = RecordingOcrEngine(ArrayDeque(listOf("レア様も", "お帰りを", "待って", "いらしたから")
                .map { OcrEngine.OcrEngineResult(it, .9f) }))
            assertEquals(listOf("レア様も", "お帰りを", "待って", "いらしたから"),
                recognizeJapaneseLines(source, boxes.reversed(), engine).map { it.text })
            assertTrue(rotatedInput!!.isRecycled)
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test
    fun `already detected columns preserve their crop bounds without a second pass`() {
        val source = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val columns = listOf(RectF(60f, 10f, 90f, 180f), RectF(10f, 10f, 40f, 180f))
        var calls = 0
        try {
            assertEquals(columns, detectJapaneseTextLines(source) {
                calls++
                assertTrue(it === source)
                columns
            })
            assertEquals(1, calls)
        } finally { source.recycle() }
    }

    @Test
    fun `horizontal Japanese keeps original lines and unavailable detection fails open`() {
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val horizontal = listOf(RectF(10f, 10f, 190f, 35f), RectF(10f, 50f, 190f, 75f))
        try {
            assertEquals(horizontal, detectJapaneseTextLines(source) {
                if (it === source) horizontal else listOf(RectF(10f, 10f, 35f, 190f))
            })
            assertEquals(horizontal, detectJapaneseTextLines(source) { if (it === source) horizontal else null })
            assertNull(detectJapaneseTextLines(source) { null })
            assertEquals(emptyList<RectF>(), detectJapaneseTextLines(source) { emptyList() })
        } finally { source.recycle() }
    }

    @Test
    fun `rotated detection bitmap is released when detector throws`() {
        val source = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
        var rotated: Bitmap? = null
        try {
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                detectJapaneseTextLines(source) {
                    if (it === source) emptyList() else {
                        rotated = it
                        error("detector failed")
                    }
                }
            }
            assertTrue(rotated!!.isRecycled)
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test
    fun `full image vertical line preserves immutable source for subsequent recognition`() {
        val mutable = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888)
        val source = mutable.copy(Bitmap.Config.ARGB_8888, false)!!
        mutable.recycle()
        val engine = RecordingOcrEngine(
            ArrayDeque(listOf(OcrEngine.OcrEngineResult("縦", 0.9f)))
        )
        try {
            val lines = recognizeJapaneseLines(source, listOf(RectF(0f, 0f, 4f, 8f)), engine)
            assertEquals(listOf("縦"), lines.map { it.text })
            assertFalse(source.isRecycled)
            assertEquals(0, source.getPixel(0, 0))
        } finally {
            source.recycle()
        }
    }

    @Test
    fun `crop cleanup preserves borrowed source and releases owned crop on failure`() {
        val mutable = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888)
        val source = mutable.copy(Bitmap.Config.ARGB_8888, false)!!
        mutable.recycle()
        try {
            for (rect in listOf(RectF(0f, 0f, 4f, 8f), RectF(1f, 1f, 3f, 7f))) {
                var captured: Bitmap? = null
                org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    withBitmapCrop(source, rect) { crop ->
                        captured = crop
                        throw IllegalStateException("recognition failed")
                    }
                }
                assertFalse(source.isRecycled)
                assertEquals(captured !== source, captured!!.isRecycled)
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun `vertical line is rotated counterclockwise before recognition`() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setPixel(5, 1, Color.RED)
        }
        val rect = RectF(2f, 1f, 6f, 7f)
        val engine = RecordingOcrEngine(
            ArrayDeque(listOf(OcrEngine.OcrEngineResult("縦", 0.9f)))
        )

        try {
            val lines = recognizeJapaneseLines(source, listOf(rect), engine)

            assertEquals(listOf("縦"), lines.map { it.text })
            assertEquals(1, engine.calls.size)
            assertEquals(6, engine.calls.single().width)
            assertEquals(4, engine.calls.single().height)
            assertEquals(Color.RED, engine.calls.single().topLeftPixel)
            assertNull(engine.calls.single().rect)
            assertFalse(source.isRecycled)
        } finally {
            source.recycle()
        }
    }

    @Test
    fun `vertical Japanese columns are recognized from right to left`() {
        val source = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        val left = RectF(2f, 1f, 6f, 7f)
        val right = RectF(12f, 1f, 16f, 7f)
        val engine = RecordingOcrEngine(
            ArrayDeque(
                listOf(
                    OcrEngine.OcrEngineResult("右", 0.9f),
                    OcrEngine.OcrEngineResult("左", 0.9f)
                )
            )
        )

        try {
            val lines = recognizeJapaneseLines(source, listOf(left, right), engine)

            assertEquals(listOf(right, left), lines.map { it.rect })
            assertEquals(listOf("右", "左"), lines.map { it.text })
            assertTrue(engine.calls.all { it.rect == null })
        } finally {
            source.recycle()
        }
    }

    @Test
    fun `rotated line still respects the recognition score threshold`() {
        val source = Bitmap.createBitmap(4, 6, Bitmap.Config.ARGB_8888)
        val engine = RecordingOcrEngine(
            ArrayDeque(listOf(OcrEngine.OcrEngineResult("誤", 0.49f)))
        )

        try {
            val lines = recognizeJapaneseLines(
                source,
                listOf(RectF(0f, 0f, 4f, 6f)),
                engine
            )

            assertTrue(lines.isEmpty())
        } finally {
            source.recycle()
        }
    }

    private class RecordingOcrEngine(
        private val results: ArrayDeque<OcrEngine.OcrEngineResult>
    ) : OcrEngine {
        val calls = mutableListOf<Call>()

        override fun recognize(bitmap: Bitmap): String {
            error("recognizeWithScore is expected")
        }

        override fun recognizeWithScore(
            bitmap: Bitmap,
            rect: RectF?
        ): OcrEngine.OcrEngineResult {
            calls += Call(
                width = bitmap.width,
                height = bitmap.height,
                topLeftPixel = bitmap.getPixel(0, 0),
                rect = rect?.let(::RectF)
            )
            return results.removeFirst()
        }
    }

    private data class Call(
        val width: Int,
        val height: Int,
        val topLeftPixel: Int,
        val rect: RectF?
    )
}
