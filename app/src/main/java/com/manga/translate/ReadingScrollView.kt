package com.manga.translate

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
class ReadingScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SafeNestedScrollView(context, attrs, defStyleAttr) {
    var scrollEnabled: Boolean = true

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return scrollEnabled && super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!scrollEnabled) return false
        val handled = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP && !handled) {
            performClick()
        }
        return handled
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}
