package com.manga.translate.settings.ui.dialogs

import androidx.appcompat.app.AlertDialog
import com.manga.translate.R
import com.manga.translate.databinding.DialogTranslationRequestSettingsBinding
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

internal class TranslationRequestSettingsDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val binding = DialogTranslationRequestSettingsBinding.inflate(fragment.layoutInflater)
        binding.maxConcurrencyInput.setText(settingsStore.loadMaxConcurrency().toString())
        binding.batchPagesInput.setText(settingsStore.loadTranslationBatchPages().toString())
        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.translation_request_settings_title)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val maxConcurrency = binding.maxConcurrencyInput.text?.toString()?.trim()?.toIntOrNull()
                val batchPages = binding.batchPagesInput.text?.toString()?.trim()?.toIntOrNull()
                val validConcurrency = maxConcurrency != null &&
                    maxConcurrency in SettingsStore.MIN_MAX_CONCURRENCY..SettingsStore.MAX_MAX_CONCURRENCY
                val validBatchPages = batchPages != null &&
                    batchPages in 1..SettingsStore.MAX_TRANSLATION_BATCH_PAGES
                binding.maxConcurrencyLayout.error = if (validConcurrency) {
                    null
                } else {
                    fragment.getString(R.string.max_concurrency_hint)
                }
                binding.batchPagesLayout.error = if (validBatchPages) {
                    null
                } else {
                    fragment.getString(R.string.translation_batch_pages_hint)
                }
                if (!validConcurrency || !validBatchPages) return@setOnClickListener
                settingsStore.saveMaxConcurrency(maxConcurrency)
                settingsStore.saveTranslationBatchPages(batchPages)
                fragment.updateTranslationRequestSettingsButton()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
