package com.manga.translate

import android.content.Context
import androidx.appcompat.app.AlertDialog

internal fun showModelErrorDialog(
    context: Context,
    responseContent: String,
    onContinue: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    windowType: Int? = null
): AlertDialog {
    val dialog = AlertDialog.Builder(context)
        .setTitle(R.string.model_response_failed_title)
        .setMessage(ErrorDialogFormatter.formatModelErrorMessage(context, responseContent))
        .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel?.invoke() }
        .apply {
            if (onContinue != null) {
                setPositiveButton(R.string.translation_continue) { _, _ -> onContinue.invoke() }
            } else {
                setPositiveButton(android.R.string.ok, null)
            }
        }
        .create()
    dialog.setCanceledOnTouchOutside(false)
    dialog.setOnCancelListener { onCancel?.invoke() }
    if (windowType != null) {
        dialog.window?.setType(windowType)
    }
    dialog.show()
    dialog.enableScrollableMessage()
    return dialog
}
