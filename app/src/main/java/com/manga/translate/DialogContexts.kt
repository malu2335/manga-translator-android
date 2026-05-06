package com.manga.translate

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper

internal fun createAlertDialogContext(context: Context): Context {
    val appCompatContext = ContextThemeWrapper(context, R.style.Theme_MangaTranslator)
    return ContextThemeWrapper(appCompatContext, R.style.ThemeOverlay_MangaTranslator_AlertDialog)
}

internal fun createAlertDialogBuilder(context: Context): AlertDialog.Builder {
    return AlertDialog.Builder(createAlertDialogContext(context))
}
