package com.manga.translate.settings.ui.dialogs

import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.manga.translate.R
import com.manga.translate.databinding.DialogOcrSettingsBinding
import com.manga.translate.model.OcrApiFormat
import com.manga.translate.ocr.LocalOcrConcurrency
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.ResourceWarningDialogs
import com.manga.translate.platform.showWithScrollableMessage
import com.manga.translate.settings.OcrApiSettings
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment
import java.util.Locale

/**
 * OCR API settings editing dialog.
 */
internal class OcrSettingsDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val currentSettings = settingsStore.loadOcrApiSettings()
        val dialogBinding = DialogOcrSettingsBinding.inflate(fragment.layoutInflater)
        dialogBinding.useLocalOcrSwitch.isChecked = currentSettings.useLocalOcr
        dialogBinding.ocrApiUrlInput.setText(currentSettings.apiUrl)
        dialogBinding.ocrApiKeyInput.setText(currentSettings.apiKey)
        dialogBinding.ocrModelNameInput.setText(currentSettings.modelName)
        dialogBinding.ocrApiTimeoutInput.setText(
            String.format(Locale.getDefault(), "%d", currentSettings.timeoutSeconds)
        )
        dialogBinding.ocrApiConcurrencyInput.setText(
            String.format(Locale.getDefault(), "%d", currentSettings.apiOcrConcurrencyLimit)
        )
        dialogBinding.localOcrConcurrencyInput.setText(
            String.format(Locale.getDefault(), "%d", currentSettings.localOcrConcurrencyLimit)
        )
        // Setup format dropdown
        val formatEntries = OcrApiFormat.entries.map { fragment.getString(it.labelRes) }
        val formatValues = OcrApiFormat.entries.toTypedArray()
        val formatAdapter = ArrayAdapter(
            fragment.requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            formatEntries
        )
        dialogBinding.ocrApiFormatInput.setAdapter(formatAdapter)
        dialogBinding.ocrApiFormatInput.threshold = 0
        dialogBinding.ocrApiFormatInput.setOnClickListener {
            dialogBinding.ocrApiFormatInput.showDropDown()
        }
        val currentFormatIndex = formatValues.indexOf(currentSettings.ocrApiFormat)
            .coerceAtLeast(0)
        dialogBinding.ocrApiFormatInput.setText(formatEntries[currentFormatIndex], false)

        fun resolveSelectedFormat(): OcrApiFormat {
            val selectedText = dialogBinding.ocrApiFormatInput.text?.toString().orEmpty()
            val idx = formatEntries.indexOf(selectedText)
            return if (idx >= 0) formatValues[idx] else OcrApiFormat.OPENAI_COMPATIBLE
        }

        fun updateInputsEnabled(useLocalOcr: Boolean) {
            val enabled = !useLocalOcr
            dialogBinding.ocrApiFormatLayout.visibility =
                if (enabled) View.VISIBLE else View.GONE
            dialogBinding.ocrApiUrlLayout.visibility =
                if (enabled) View.VISIBLE else View.GONE
            dialogBinding.ocrApiKeyLayout.visibility =
                if (enabled) View.VISIBLE else View.GONE
            dialogBinding.ocrModelNameLayout.visibility =
                if (enabled) View.VISIBLE else View.GONE
            dialogBinding.ocrApiTimeoutLayout.visibility =
                if (enabled) View.VISIBLE else View.GONE
            dialogBinding.ocrApiUrlInput.isEnabled = enabled
            dialogBinding.ocrApiKeyInput.isEnabled = enabled
            dialogBinding.ocrModelNameInput.isEnabled = enabled
            dialogBinding.ocrApiTimeoutInput.isEnabled = enabled
            dialogBinding.ocrApiConcurrencyLayout.visibility =
                if (enabled) View.VISIBLE else View.GONE
            dialogBinding.localOcrConcurrencyLayout.visibility =
                if (useLocalOcr) View.VISIBLE else View.GONE
            dialogBinding.ocrSettingsNote.setText(
                if (useLocalOcr) R.string.ocr_settings_note_local else R.string.ocr_settings_note_api
            )
        }

        updateInputsEnabled(currentSettings.useLocalOcr)
        dialogBinding.useLocalOcrSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateInputsEnabled(isChecked)
        }
        dialogBinding.ocrApiFormatInput.setOnItemClickListener { _, _, _, _ ->
            updateInputsEnabled(dialogBinding.useLocalOcrSwitch.isChecked)
        }

        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.ocr_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val timeoutInput = dialogBinding.ocrApiTimeoutInput.text?.toString()?.trim()
                val timeoutSeconds = fragment.parseIntInput(timeoutInput)
                    ?.coerceIn(SettingsStore.MIN_OCR_API_TIMEOUT_SECONDS, SettingsStore.MAX_OCR_API_TIMEOUT_SECONDS)
                    ?: currentSettings.timeoutSeconds
                val concurrencyInput = dialogBinding.ocrApiConcurrencyInput.text?.toString()?.trim()
                val apiOcrConcurrencyLimit = fragment.parseIntInput(concurrencyInput)
                    ?.coerceIn(SettingsStore.MIN_OCR_API_CONCURRENCY, SettingsStore.MAX_OCR_API_CONCURRENCY)
                    ?: currentSettings.apiOcrConcurrencyLimit
                val localConcurrencyInput = dialogBinding.localOcrConcurrencyInput.text?.toString()?.trim()
                val localOcrConcurrencyLimit = fragment.parseIntInput(localConcurrencyInput)
                    ?.coerceIn(0, 8)
                    ?: currentSettings.localOcrConcurrencyLimit
                val format = resolveSelectedFormat()
                val settings = OcrApiSettings(
                    useLocalOcr = dialogBinding.useLocalOcrSwitch.isChecked,
                    apiUrl = dialogBinding.ocrApiUrlInput.text?.toString()?.trim().orEmpty(),
                    apiKey = dialogBinding.ocrApiKeyInput.text?.toString()?.trim().orEmpty(),
                    modelName = dialogBinding.ocrModelNameInput.text?.toString()?.trim().orEmpty(),
                    timeoutSeconds = timeoutSeconds,
                    apiOcrConcurrencyLimit = apiOcrConcurrencyLimit,
                    localOcrConcurrencyLimit = localOcrConcurrencyLimit,
                    ocrApiFormat = format
                )
                saveOcrSettingsWithResourceCheck(settings)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveOcrSettingsWithResourceCheck(settings: OcrApiSettings) {
        fun save() {
            settingsStore.saveOcrApiSettings(settings)
            AppLogger.log(
                "Settings",
                "OCR mode set to ${
                    if (settings.useLocalOcr) {
                        "local:ppocrv6_small_rec"
                    } else {
                        "${settings.ocrApiFormat.prefValue} api"
                    }
                }"
            )
        }

        if (!settings.useLocalOcr || settings.localOcrConcurrencyLimit <= 0) {
            save()
            return
        }
        val assessment = LocalOcrConcurrency.assess(
            fragment.requireContext(),
            settings.localOcrConcurrencyLimit
        )
        if (!assessment.shouldWarn) {
            save()
            return
        }
        ResourceWarningDialogs.createBuilder(fragment.requireContext(), assessment)
            .setNegativeButton(R.string.resource_continue_anyway) { _, _ -> save() }
            .setPositiveButton(R.string.resource_cancel, null)
            .showWithScrollableMessage()
    }
}
