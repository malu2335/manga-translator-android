package com.manga.translate.library

import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.manga.translate.R
import java.io.File

internal fun showFolderTranslationStyleDialog(
    context: Context,
    folder: File,
    preferences: LibraryPreferencesGateway,
    globalStyle: String
): AlertDialog {
    val view = LayoutInflater.from(context).inflate(R.layout.dialog_folder_translation_style, null)
    val followGlobal = view.findViewById<SwitchCompat>(R.id.follow_global_style)
    val input = view.findViewById<EditText>(R.id.folder_style_input)
    val localStyle = preferences.getTranslationStyle(folder)
    followGlobal.isChecked = localStyle == null
    input.setText(localStyle ?: globalStyle)
    input.isEnabled = !followGlobal.isChecked
    followGlobal.setOnCheckedChangeListener { _, checked -> input.isEnabled = !checked }
    return AlertDialog.Builder(context)
        .setTitle(R.string.translation_style_title)
        .setView(view)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            preferences.setTranslationStyle(folder, if (followGlobal.isChecked) null else input.text.toString())
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
