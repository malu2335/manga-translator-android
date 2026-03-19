package com.manga.translate

import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

internal fun AlertDialog.Builder.showWithScrollableMessage(): AlertDialog {
    return create().also { dialog ->
        dialog.show()
        dialog.enableScrollableMessage()
    }
}

internal fun AlertDialog.enableScrollableMessage() {
    val messageView = findViewById<TextView>(android.R.id.message) ?: return
    messageView.movementMethod = ScrollingMovementMethod.getInstance()
    messageView.isVerticalScrollBarEnabled = true
    messageView.isScrollbarFadingEnabled = false
}
