package com.manga.translate

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

open class SafeNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {
    private var scrollBarCrashRecovered = false

    override fun draw(canvas: Canvas) {
        try {
            super.draw(canvas)
        } catch (e: NullPointerException) {
            if (!isFrameworkScrollBarCrash(e)) {
                throw e
            }
            disableScrollBarsAfterCrash(e)
            super.draw(canvas)
        }
    }

    private fun isFrameworkScrollBarCrash(error: NullPointerException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("ScrollBarDrawable", ignoreCase = true)
    }

    private fun disableScrollBarsAfterCrash(error: NullPointerException) {
        if (!scrollBarCrashRecovered) {
            scrollBarCrashRecovered = true
            AppLogger.log(
                "SafeNestedScrollView",
                "Recovered from framework scrollbar crash by disabling scrollbars",
                error
            )
        }
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }
}
