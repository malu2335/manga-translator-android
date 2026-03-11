package com.manga.translate

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

internal class LibraryDialogs {
    private fun formatInt(value: Int): String = String.format(Locale.getDefault(), "%d", value)
    private fun dp(context: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics
    ).toInt()

    private fun showMessageDialog(
        context: Context,
        titleRes: Int,
        message: CharSequence,
        positiveRes: Int = android.R.string.ok,
        onPositive: (() -> Unit)? = null
    ) {
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(positiveRes) { _, _ -> onPositive?.invoke() }
            .show()
    }

    private fun showTextInputDialog(
        context: Context,
        titleRes: Int,
        initialText: String = "",
        trimResult: Boolean = false,
        onConfirm: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.folder_name_hint)
            if (initialText.isNotEmpty()) {
                setText(initialText)
                setSelection(text.length)
            }
        }
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text?.toString().orEmpty()
                onConfirm(if (trimResult) value.trim() else value)
            }
            .show()
    }

    private fun showSingleChoiceDialog(
        context: Context,
        titleRes: Int,
        items: Array<String>,
        checkedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                onSelected(which)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildDialogContainer(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20f), dp(context, 12f), dp(context, 20f), dp(context, 12f))
        }
    }

    private fun matchWrapLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    fun showCreateFolderDialog(context: Context, onConfirm: (String) -> Unit) {
        showTextInputDialog(context, R.string.create_folder, onConfirm = onConfirm)
    }

    fun confirmDeleteFolder(context: Context, folderName: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.folder_delete)
            .setMessage(context.getString(R.string.folder_delete_confirm, folderName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.folder_delete) { _, _ -> onConfirm() }
            .show()
    }

    fun showRenameFolderDialog(
        context: Context,
        oldName: String,
        onConfirm: (String) -> Unit
    ) {
        showTextInputDialog(context, R.string.folder_rename, initialText = oldName, onConfirm = onConfirm)
    }

    fun showFullTranslateInfo(context: Context) {
        showMessageDialog(
            context,
            R.string.folder_full_translate_info_title,
            context.getString(R.string.folder_full_translate_info)
        )
    }

    fun showLanguageSettingDialog(
        context: Context,
        currentLanguage: TranslationLanguage,
        onSelected: (TranslationLanguage) -> Unit
    ) {
        val languages = TranslationLanguage.values()
        val languageNames = languages.map { context.getString(it.displayNameResId) }.toTypedArray()
        val currentIndex = languages.indexOf(currentLanguage)
        showSingleChoiceDialog(context, R.string.folder_language_setting_title, languageNames, currentIndex) {
            onSelected(languages[it])
        }
    }

    fun showFixedLanguageDialog(context: Context) {
        showSingleChoiceDialog(
            context,
            R.string.folder_language_setting_title,
            arrayOf(context.getString(R.string.folder_language_to_zh)),
            0
        ) { }
    }

    fun showApiErrorDialog(context: Context, errorCode: String) {
        showMessageDialog(
            context,
            R.string.api_request_failed_title,
            context.getString(R.string.api_request_failed_message, errorCode)
        )
    }

    fun showModelErrorDialog(context: Context, responseContent: String) {
        showMessageDialog(
            context,
            R.string.model_response_failed_title,
            context.getString(R.string.model_response_failed_message, responseContent)
        )
    }

    fun showEhViewerSubfolderPicker(
        context: Context,
        folders: List<DocumentFile>,
        onPicked: (DocumentFile) -> Unit
    ) {
        val names = folders.map { it.name ?: "未命名" }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.ehviewer_select_folder)
            .setItems(names) { _, index -> onPicked(folders[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showEhViewerImportNameDialog(
        context: Context,
        defaultName: String,
        onConfirm: (String) -> Unit
    ) {
        showTextInputDialog(
            context,
            R.string.ehviewer_import_name_title,
            initialText = defaultName,
            trimResult = true
        ) { name ->
            if (name.isEmpty()) {
                Toast.makeText(context, R.string.folder_create_failed, Toast.LENGTH_SHORT).show()
            } else {
                onConfirm(name)
            }
        }
    }

    fun showExportSuccessDialog(context: Context, path: String) {
        showMessageDialog(
            context,
            R.string.export_success_title,
            context.getString(R.string.export_success_message, path)
        )
    }

    fun showExportOptionsDialog(
        context: Context,
        defaultThreads: Int,
        defaultExportAsCbz: Boolean,
        hasEmbeddedImages: Boolean,
        exportRootPathHint: String,
        onConfirm: (Int, Boolean, Boolean) -> Unit
    ) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.export_thread_hint)
            setText(formatInt(defaultThreads))
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val cbzCheckBox = CheckBox(context).apply {
            text = context.getString(R.string.export_as_cbz_option)
            isChecked = defaultExportAsCbz
        }
        val embeddedCheckBox = CheckBox(context).apply {
            text = context.getString(R.string.export_embedded_images_option)
            isChecked = hasEmbeddedImages
            isEnabled = hasEmbeddedImages
            if (!hasEmbeddedImages) {
                alpha = 0.5f
            }
        }
        val pathHintView = TextView(context).apply {
            setPadding(0, dp(context, 8f), 0, 0)
            text = context.getString(R.string.export_path_hint_format, exportRootPathHint)
        }
        val container = buildDialogContainer(context).apply {
            addView(input, matchWrapLayoutParams())
            addView(cbzCheckBox, matchWrapLayoutParams())
            addView(embeddedCheckBox, matchWrapLayoutParams())
            addView(pathHintView, matchWrapLayoutParams())
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.export_options_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val threadCount = input.text?.toString()?.toIntOrNull()
                if (threadCount == null || threadCount !in 1..16) {
                    Toast.makeText(context, R.string.export_thread_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onConfirm(threadCount, cbzCheckBox.isChecked, embeddedCheckBox.isChecked)
            }
            .show()
    }

    fun showEmbedOptionsDialog(
        context: Context,
        defaultThreads: Int,
        defaultUseWhiteBubbleCover: Boolean,
        defaultUseEllipseLimit: Boolean,
        defaultUseImageRepair: Boolean,
        onConfirm: (Int, Boolean, Boolean, Boolean) -> Unit
    ) {
        val note = TextView(context).apply {
            text = context.getString(R.string.embed_thread_note)
        }
        val input = EditText(context).apply {
            hint = context.getString(R.string.embed_thread_hint)
            setText(formatInt(defaultThreads))
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val whiteCoverCheckBox = CheckBox(context).apply {
            text = context.getString(R.string.embed_white_cover_option)
            isChecked = defaultUseWhiteBubbleCover
        }
        val ellipseLimitCheckBox = CheckBox(context).apply {
            text = context.getString(R.string.embed_ellipse_limit_option)
            isChecked = defaultUseEllipseLimit
            isEnabled = defaultUseWhiteBubbleCover
            alpha = if (defaultUseWhiteBubbleCover) 1f else 0.5f
        }
        val imageRepairCheckBox = CheckBox(context).apply {
            text = context.getString(R.string.embed_image_repair_option)
            isChecked = defaultUseImageRepair
        }
        whiteCoverCheckBox.setOnCheckedChangeListener { _, isChecked ->
            ellipseLimitCheckBox.isEnabled = isChecked
            ellipseLimitCheckBox.alpha = if (isChecked) 1f else 0.5f
            if (!isChecked) {
                ellipseLimitCheckBox.isChecked = false
            }
        }
        val container = buildDialogContainer(context).apply {
            addView(
                note,
                matchWrapLayoutParams().apply {
                    bottomMargin = dp(context, 8f)
                }
            )
            addView(input, matchWrapLayoutParams())
            addView(
                whiteCoverCheckBox,
                matchWrapLayoutParams().apply {
                    topMargin = dp(context, 10f)
                }
            )
            addView(ellipseLimitCheckBox, matchWrapLayoutParams())
            addView(imageRepairCheckBox, matchWrapLayoutParams())
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.embed_options_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val threadCount = input.text?.toString()?.toIntOrNull()
                if (threadCount == null || threadCount !in 1..16) {
                    Toast.makeText(context, R.string.embed_thread_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onConfirm(
                    threadCount,
                    whiteCoverCheckBox.isChecked,
                    ellipseLimitCheckBox.isChecked,
                    imageRepairCheckBox.isChecked
                )
            }
            .show()
    }

    fun confirmDeleteSelectedImages(
        context: Context,
        selectedCount: Int,
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.delete_selected)
            .setMessage(context.getString(R.string.delete_images_confirm, selectedCount))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_selected) { _, _ -> onConfirm() }
            .show()
    }
}
