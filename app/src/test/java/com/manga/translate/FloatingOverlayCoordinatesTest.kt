package com.manga.translate

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.widget.FrameLayout
import com.manga.translate.floating.FloatingDetectionOverlayView
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.settings.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingOverlayCoordinatesTest {
    @Test fun insetWindowUsesScreenCoordinatesForDrawingAndManualSelection() = checkCoordinates(true, false)

    @Test fun insetWindowMapsMaskContourToTheSameScreenPosition() = checkCoordinates(true, true)

    @Test fun fullScreenWindowKeepsOriginalAlignment() = checkCoordinates(false, false)

    private fun checkCoordinates(inset: Boolean, contour: Boolean) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val view = FloatingDetectionOverlayView(activity)
        activity.setContentView(root)
        root.addView(view, FrameLayout.LayoutParams(360, 700).apply {
            leftMargin = if (inset) 20 else 0
            topMargin = if (inset) 24 else 0
        })
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 800)
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        if (inset) assertTrue(location[1] >= 24)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getRealMetrics(metrics)
        // Also exercise capture images scaled to half the physical display resolution.
        view.setTranslationSession(metrics.widthPixels / 2, metrics.heightPixels / 2,
            listOf(BubbleTranslation(id = 1, rect = RectF(50f, 100f, 150f, 200f), translatedText = "test",
                maskContour = if (contour) floatArrayOf(
                    100f / metrics.widthPixels, 200f / metrics.heightPixels,
                    300f / metrics.widthPixels, 200f / metrics.heightPixels,
                    300f / metrics.widthPixels, 400f / metrics.heightPixels,
                    100f / metrics.widthPixels, 400f / metrics.heightPixels
                ) else null)))
        view.setFloatingBubbleRenderSettings(SettingsStore(activity).loadFloatingBubbleRenderSettings().copy(
            opacityPercent = 100, sizeAdjustPercent = 0, autoAdaptBubbleColor = false))
        val bitmap = Bitmap.createBitmap(360, 700, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val x = 200 - location[0]
        val top = 200 - location[1]
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(x, top - 3))
        assertEquals(255, Color.alpha(bitmap.getPixel(x, top + 4)))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(x, 400 - location[1] + 3))
        view.setEditMode(true)
        view.setCreateBubbleMode(true)
        var created: RectF? = null
        view.onManualBubbleCreated = { created = it }
        fun touch(action: Int, screenX: Float, screenY: Float) {
            val event = MotionEvent.obtain(0, 1, action,
                screenX - location[0], screenY - location[1], 0)
            view.onTouchEvent(event)
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 100f, 200f)
        touch(MotionEvent.ACTION_UP, 300f, 400f)
        assertEquals(RectF(50f, 100f, 150f, 200f), created)
        bitmap.recycle()
        controller.pause().stop().destroy()
    }
}
