package com.manga.translate

import android.content.Context
import androidx.appcompat.app.AlertDialog

internal fun showModelErrorDialog(
    context: Context,
    responseContent: String,
    onRetry: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    windowType: Int? = null
): AlertDialog {
    val dialog = AlertDialog.Builder(context)
        .setTitle(R.string.model_response_failed_title)
        .setMessage(ErrorDialogFormatter.formatModelErrorMessage(context, responseContent))
        .setNegativeButton(R.string.translation_skip) { _, _ -> onSkip?.invoke() }
        .apply {
            if (onRetry != null) {
                setPositiveButton(R.string.translation_continue) { _, _ -> onRetry.invoke() }
            } else {
                setPositiveButton(android.R.string.ok, null)
            }
        }
        .create()
    dialog.setCanceledOnTouchOutside(false)
    dialog.setOnCancelListener { onSkip?.invoke() }
    if (windowType != null) {
        dialog.window?.setType(windowType)
    }
    dialog.show()
    dialog.enableScrollableMessage()
    return dialog
}
