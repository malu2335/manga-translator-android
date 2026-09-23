package com.manga.translate

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.manga.translate.rendering.BubbleTextScaling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleTextScalingTest {
    @Test
    fun sourceLineWrappingReflowsButParagraphsRemain() {
        assertEquals("你好世界\n\n第二段", BubbleTextScaling.prepareTextForLayout("你好\n世界\n\n第二段"))
        assertEquals("Hello world", BubbleTextScaling.prepareTextForLayout("Hello\r\nworld"))
    }

    @Test
    fun reflowedShortLinesUseLargerFontWithoutOverflow() {
        val paint = TextPaint()
        val build = { text: String, width: Int, size: Float ->
            paint.textSize = size
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setIncludePad(false).build()
        }
        val original = "你好\n世界\n今天\n晴天"
        val reflowed = BubbleTextScaling.prepareTextForLayout(original)
        fun size(text: String) = BubbleTextScaling.findAutoHorizontalTextSize(
            text, 160, 80, build, BubbleTextScaling::layoutFits
        )
        val before = size(original)
        val after = size(reflowed)
        assertTrue("before=$before after=$after", after > before * 1.2f)
        assertTrue(BubbleTextScaling.layoutFits(build(reflowed, 160, after), 160, 80))
    }

    @Test
    fun smallBubbleRetainsMostOfItsTextArea() {
        val path = android.graphics.Path().apply {
            addRect(0f, 0f, 20f, 60f, android.graphics.Path.Direction.CW)
        }
        val rect = BubbleTextScaling.resolveTextRect(path)
        assertTrue("$rect", rect.width() > 17f)
        assertTrue("$rect", rect.height() > 54f)
        assertTrue(rect.left >= 0f && rect.right <= 20f)
        assertTrue(rect.top >= 0f && rect.bottom <= 60f)
    }

    @Test
    fun autoSizeCanShrinkBelowRemovedUserMinimum() {
        val textSize = BubbleTextScaling.findLargestFittingTextSize(
            maxWidth = 100,
            maxHeight = 40,
            fits = { it <= 3.25f }
        )

        assertTrue(textSize > 3f)
        assertTrue(textSize <= 3.25f)
    }

    @Test
    fun autoSizeReturnsTechnicalFloorWhenNothingFits() {
        val textSize = BubbleTextScaling.findLargestFittingTextSize(
            maxWidth = 1,
            maxHeight = 1,
            fits = { false }
        )

        assertEquals(0.5f, textSize, 0f)
    }

    @Test
    fun denseHorizontalTextShrinksUntilLayoutFitsBubble() {
        val text = "文字".repeat(50)
        val paint = TextPaint()
        val width = 40
        val height = 20
        val buildLayout = { textSize: Float ->
            paint.textSize = textSize
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
        }

        val textSize = BubbleTextScaling.findAutoHorizontalTextSize(
            text = text,
            maxWidth = width,
            maxHeight = height,
            buildLayout = { _, _, size -> buildLayout(size) },
            layoutFits = BubbleTextScaling::layoutFits
        )
        val layout = buildLayout(textSize)

        assertTrue(textSize < 8f)
        assertTrue(BubbleTextScaling.layoutFits(layout, width, height))
    }
}
