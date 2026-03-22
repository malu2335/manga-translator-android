package com.manga.translate

import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView

internal fun AlertDialog.Builder.showWithScrollableMessage(): AlertDialog {
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
