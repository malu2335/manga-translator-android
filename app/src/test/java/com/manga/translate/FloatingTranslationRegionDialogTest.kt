package com.manga.translate

import android.view.View
import android.widget.ScrollView
import android.view.MotionEvent
import android.view.ViewGroup
import com.manga.translate.floating.FloatingRegionSelectionView
import android.app.Dialog
import com.manga.translate.floating.FloatingBallOverlayService
import com.manga.translate.settings.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class FloatingTranslationRegionDialogTest {
    @Test @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun narrowLightDialogSavesAndResetsRegion() = checkDialog()

    @Test @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun narrowDarkDialogScrollsAndCancelsChanges() = checkDialog()

    private fun checkDialog() {
        val controller = Robolectric.buildService(FloatingBallOverlayService::class.java).create()
        val service = controller.get()
        val settings = SettingsStore(service)
        settings.saveFloatingTranslateApiSettings(settings.loadFloatingTranslateApiSettings().copy(
            detectionTopInsetPercent = 10, detectionBottomInsetPercent = 20))
        val show = FloatingBallOverlayService::class.java.getDeclaredMethod("showTranslationRegionDialog")
            .apply { isAccessible = true }
        try {
            show.invoke(service)
            var dialog = ShadowDialog.getLatestDialog()
            fun selector(dialog: Dialog): FloatingRegionSelectionView =
                (dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup)
                    .getChildAt(0) as FloatingRegionSelectionView
            val selection = selector(dialog)
            assertEquals(0.1f, selection.selectedRegion().top, 0.001f)
            assertEquals(0.8f, selection.selectedRegion().bottom, 0.001f)
            val confirm = dialog.findViewById<View>(R.id.region_confirm)!!
            val scroll = confirm.parent.parent as ScrollView
            assertFalse(scroll.clipToPadding)
            val density = service.resources.displayMetrics.density
            scroll.measure(
                View.MeasureSpec.makeMeasureSpec((280 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((140 * density).toInt(), View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, scroll.measuredWidth, scroll.measuredHeight)
            assertTrue(scroll.getChildAt(0).height > scroll.height)
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            service.getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
            fun touch(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(0, 1, action,
                    x * metrics.widthPixels, y * metrics.heightPixels, 0)
                selection.onTouchEvent(event)
                event.recycle()
            }
            // Reverse dragging works, and lifting the finger does not save the region.
            touch(MotionEvent.ACTION_DOWN, 0.8f, 0.7f)
            assertFalse(confirm.isEnabled)
            touch(MotionEvent.ACTION_UP, 0.2f, 0.3f)
            assertTrue(confirm.isEnabled)
            assertEquals(10, settings.loadFloatingTranslateApiSettings().detectionTopInsetPercent)
            val selected = selection.selectedRegion()
            touch(MotionEvent.ACTION_DOWN, 0.4f, 0.4f)
            touch(MotionEvent.ACTION_UP, 0.4f, 0.4f)
            assertEquals(selected, selection.selectedRegion())
            touch(MotionEvent.ACTION_DOWN, 0.1f, 0.1f)
            touch(MotionEvent.ACTION_CANCEL, 0.9f, 0.9f)
            assertEquals(selected, selection.selectedRegion())
            assertTrue(confirm.isEnabled)
            confirm.performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(30, settings.loadFloatingTranslateApiSettings().detectionTopInsetPercent)
            assertEquals(20 to 20, SettingsStore(service).loadFloatingDetectionHorizontalInsets())
            show.invoke(service)
            dialog = ShadowDialog.getLatestDialog()
            dialog.findViewById<View>(R.id.region_full_screen).performClick()
            assertEquals(0f, selector(dialog).selectedRegion().top, 0.001f)
            dialog.findViewById<View>(R.id.region_cancel).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(30, settings.loadFloatingTranslateApiSettings().detectionTopInsetPercent)
            assertEquals(20 to 20, settings.loadFloatingDetectionHorizontalInsets())
            show.invoke(service)
            dialog = ShadowDialog.getLatestDialog()
            dialog.findViewById<View>(R.id.region_full_screen).performClick()
            dialog.findViewById<View>(R.id.region_confirm).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0, settings.loadFloatingTranslateApiSettings().detectionTopInsetPercent)
            assertEquals(0, settings.loadFloatingTranslateApiSettings().detectionBottomInsetPercent)
            assertEquals(0 to 0, settings.loadFloatingDetectionHorizontalInsets())
        } finally {
            controller.destroy()
        }
    }
}
