package com.manga.translate.settings.ui.dialogs

import android.content.Intent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.manga.translate.R
import com.manga.translate.app.MainActivity
import com.manga.translate.app.UpdateChecker
import com.manga.translate.app.VersionInfo
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.ui.SettingsFragment
import com.manga.translate.settings.ui.SettingsNetworkController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * About dialog including the "view updates" flow. Remote update info is
 * fetched through [SettingsNetworkController].
 */
internal class AboutDialog(
    private val fragment: SettingsFragment,
    private val networkController: SettingsNetworkController
) {
    fun show() {
        val versionName = resolveVersionName()
        val dialogView = fragment.layoutInflater.inflate(R.layout.dialog_about, null)
        val messageView = dialogView.findViewById<TextView>(R.id.about_dialog_message)
        val qqGroup = MainActivity.getLatestUpdateInfo()?.qqGroup
        messageView.text = buildAboutDialogMessage(versionName, qqGroup)
        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.about_dialog_title)
            .setView(dialogView)
            .create()
        dialogView.findViewById<View>(R.id.about_dialog_cancel).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.about_dialog_open_project).setOnClickListener {
            dialog.dismiss()
            openUrl(PROJECT_URL)
        }
        dialogView.findViewById<View>(R.id.about_dialog_view_updates).setOnClickListener {
            dialog.dismiss()
            loadAndShowUpdateDialog()
        }
        dialog.show()
    }

    private fun buildAboutDialogMessage(versionName: String, qqGroup: String?): String {
        return if (qqGroup.isNullOrBlank()) {
            fragment.getString(R.string.about_dialog_message, versionName)
        } else {
            fragment.getString(R.string.about_dialog_message_with_group, versionName, qqGroup)
        }
    }

    private fun loadAndShowUpdateDialog() {
        val hostActivity = fragment.activity as? MainActivity ?: return
        val loadingDialog = AlertDialog.Builder(fragment.requireContext())
            .setView(ProgressBar(fragment.requireContext()))
            .create()
        loadingDialog.setCanceledOnTouchOutside(false)
        var loadJob: Job? = null
        loadingDialog.setOnCancelListener {
            loadJob?.cancel()
        }
        loadingDialog.show()
        loadJob = fragment.lifecycleScope.launch {
            try {
                val updateInfo = networkController.fetchUpdateInfo(
                    timeoutMs = 30_000,
                    includePreview = true,
                    languageKey = UpdateChecker.resolveChangelogLanguageKey(fragment.requireContext())
                )
                if (!fragment.isAdded) return@launch
                if (updateInfo == null) {
                    Toast.makeText(
                        fragment.requireContext(),
                        R.string.update_dialog_load_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                if (hostActivity.isFinishing || hostActivity.isDestroyed) return@launch
                val title = if (hostActivity.isRemoteNewer(updateInfo)) {
                    null
                } else {
                    fragment.getString(R.string.update_dialog_no_update_title)
                }
                hostActivity.showUpdateDialog(
                    updateInfo,
                    showIgnoreButton = false,
                    titleOverride = title
                )
            } catch (_: CancellationException) {
                AppLogger.log("Settings", "Update dialog loading cancelled by user")
            } finally {
                if (loadingDialog.isShowing) {
                    loadingDialog.dismiss()
                }
            }
        }
    }

    private fun resolveVersionName(): String =
        VersionInfo.versionName(fragment.requireContext())

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        val manager = fragment.requireContext().packageManager
        if (intent.resolveActivity(manager) != null) {
            fragment.startActivity(intent)
        } else {
            Toast.makeText(fragment.requireContext(), url, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val PROJECT_URL = "https://github.com/jedzqer/manga-translator"
        @Suppress("unused")
        const val RELEASES_URL = "https://github.com/jedzqer/manga-translator/releases"
    }
}
