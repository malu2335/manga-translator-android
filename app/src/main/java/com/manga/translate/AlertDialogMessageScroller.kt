package com.manga.translate

import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.NestedScrollView

internal fun AlertDialog.Builder.showWithScrollableMessage(): AlertDialog {
    attachSafeScrollableMessageIfNeeded()
    return create().also { dialog ->
        dialog.show()
        dialog.enableScrollableMessage()
    }
}

internal fun AlertDialog.enableScrollableMessage() {
    val messageView = findViewById<TextView>(android.R.id.message) ?: return
    messageView.movementMethod = ScrollingMovementMethod.getInstance()
    disableFrameworkScrollBars(messageView)
    (messageView.parent as? View)?.let(::disableFrameworkScrollBars)
    findScrollableAncestors(messageView).forEach(::disableFrameworkScrollBars)
}

private fun findScrollableAncestors(start: View): List<View> {
    val scrollableViews = mutableListOf<View>()
    var current = start.parent
    while (current is View) {
        if (current is NestedScrollView || current is ScrollView) {
            scrollableViews += current
        }
        current = current.parent
    }
    return scrollableViews
}

private fun disableFrameworkScrollBars(view: View) {
    // Some Android builds crash in ScrollBarDrawable methods when dialog scrollbars are enabled.
    view.isVerticalScrollBarEnabled = false
    view.isHorizontalScrollBarEnabled = false
    view.isScrollbarFadingEnabled = false
    if (view is ViewGroup) {
        view.clipToPadding = true
    }
}

private fun AlertDialog.Builder.attachSafeScrollableMessageIfNeeded() {
    val message = extractBuilderMessage() ?: return
    setView(createSafeScrollableMessageView(message))
    clearBuilderMessage()
}

private fun AlertDialog.Builder.createSafeScrollableMessageView(message: CharSequence): View {
    val context = context
    val density = context.resources.displayMetrics.density
    val paddingHorizontal = (24 * density).toInt()
    val paddingTop = (8 * density).toInt()
    val paddingBottom = (4 * density).toInt()
    val maxHeight = (context.resources.displayMetrics.heightPixels * 0.4f).toInt()
    val messageView = AppCompatTextView(context).apply {
        text = message
        setTextIsSelectable(true)
        movementMethod = ScrollingMovementMethod.getInstance()
        setLineSpacing(0f, 1.15f)
        ellipsize = null
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }
    return FrameLayout(context).apply {
        addView(
            SafeNestedScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isScrollbarFadingEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(
                    messageView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    maxHeight
                )
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        setPadding(paddingHorizontal, paddingTop, paddingHorizontal, paddingBottom)
    }
}

private fun AlertDialog.Builder.extractBuilderMessage(): CharSequence? {
    return runCatching {
        val paramsField = javaClass.getDeclaredField("P").apply { isAccessible = true }
        val params = paramsField.get(this) ?: return null
        val messageField = params.javaClass.getDeclaredField("mMessage").apply { isAccessible = true }
        messageField.get(params) as? CharSequence
    }.getOrNull()?.takeUnless { TextUtils.isEmpty(it) }
}

private fun AlertDialog.Builder.clearBuilderMessage() {
    runCatching {
        val paramsField = javaClass.getDeclaredField("P").apply { isAccessible = true }
        val params = paramsField.get(this) ?: return
        val messageField = params.javaClass.getDeclaredField("mMessage").apply { isAccessible = true }
        messageField.set(params, null)
    }
}
