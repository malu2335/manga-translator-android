package com.manga.translate

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import com.google.android.material.tabs.TabLayoutMediator
import com.manga.translate.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: MainPagerAdapter
    private lateinit var crashStateStore: CrashStateStore
    private lateinit var updateIgnoreStore: UpdateIgnoreStore
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressHideJob: Job? = null
    private val hideGlobalProgressRunnable = Runnable {
        if (!::binding.isInitialized) return@Runnable
        binding.globalProgressCard.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                binding.globalProgressCard.visibility = android.view.View.GONE
            }
            .start()
    }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT)
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeMode = SettingsStore(this).loadThemeMode()
        if (themeMode == ThemeMode.PASTEL) {
            setTheme(R.style.Theme_MangaTranslator_Pastel)
        } else if (themeMode == ThemeMode.DEEP_SEA) {
            setTheme(R.style.Theme_MangaTranslator_DeepSea)
        }
        AppCompatDelegate.setDefaultNightMode(themeMode.nightMode)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        crashStateStore = CrashStateStore(this)
        updateIgnoreStore = UpdateIgnoreStore(this)

        pagerAdapter = MainPagerAdapter(this)
        binding.mainPager.adapter = pagerAdapter
        binding.mainPager.isUserInputEnabled =
            binding.mainPager.currentItem != MainPagerAdapter.READING_INDEX
        TabLayoutMediator(binding.mainTabs, binding.mainPager) { tab, position ->
            tab.setText(pagerAdapter.getTitleRes(position))
        }.attach()
        binding.mainPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.mainPager.isUserInputEnabled = position != MainPagerAdapter.READING_INDEX
            }
        })
        observeGlobalProgress()
        requestNotificationPermissionIfNeeded()
        maybeShowCrashDialog()
        checkForUpdate()
    }

    fun switchToTab(index: Int) {
        binding.mainPager.setCurrentItem(index, true)
    }

    fun setPagerSwipeEnabled(enabled: Boolean) {
        binding.mainPager.isUserInputEnabled = enabled
    }

    private fun checkForUpdate() {
        if (hasCheckedUpdate) return
        hasCheckedUpdate = true
        lifecycleScope.launch {
            val updateInfo = UpdateChecker.fetchUpdateInfo()
            if (updateInfo == null) return@launch
            AppLogger.log(
                "UpdateChecker",
                "Local version=${VersionInfo.VERSION_NAME} (${VersionInfo.VERSION_CODE}), " +
                    "remote version=${updateInfo.versionName} (${updateInfo.versionCode})"
            )
            if (!isNewerVersion(updateInfo)) return@launch
            if (updateIgnoreStore.isIgnored(updateInfo.versionCode)) return@launch
            if (isFinishing || isDestroyed) return@launch
            showUpdateDialog(updateInfo)
        }
    }

    fun showUpdateDialog(
        updateInfo: UpdateInfo,
        showIgnoreButton: Boolean = true,
        titleOverride: String? = null
    ) {
        val versionLabel = buildVersionLabel(updateInfo)
        val dialogView = layoutInflater.inflate(R.layout.dialog_update, null)
        val latestTitleView = dialogView.findViewById<TextView>(R.id.update_dialog_latest_title)
        val latestContentView = dialogView.findViewById<TextView>(R.id.update_dialog_latest_content)
        val historyContainer = dialogView.findViewById<android.view.View>(R.id.update_dialog_history_container)
        val historyContentView = dialogView.findViewById<TextView>(R.id.update_dialog_history_content)
        val negativeButton = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.update_dialog_negative_button)
        val neutralButton = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.update_dialog_neutral_button)
        val positiveButton = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.update_dialog_positive_button)
        latestTitleView.text = getString(R.string.update_dialog_latest_header, versionLabel)
        latestContentView.text = buildLatestUpdateDialogMessage(updateInfo)
        val historyMessage = buildHistoryUpdateDialogMessage(updateInfo, versionLabel)
        historyContainer.visibility = if (historyMessage.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        historyContentView.text = historyMessage
        val dialog = AlertDialog.Builder(this)
            .setTitle(titleOverride ?: getString(R.string.update_dialog_title, versionLabel))
            .setView(dialogView)
            .create()
        negativeButton.text = getString(
            if (showIgnoreButton) R.string.update_dialog_ignore else R.string.update_dialog_cancel
        )
        negativeButton.setOnClickListener {
            if (showIgnoreButton) {
                updateIgnoreStore.saveIgnoredVersionCode(updateInfo.versionCode)
            }
            dialog.dismiss()
        }
        neutralButton.setOnClickListener {
            openProjectPage()
            dialog.dismiss()
        }
        positiveButton.setOnClickListener {
            dialog.dismiss()
            startDownload(updateInfo)
        }
        dialog.show()
    }

    private fun startDownload(updateInfo: UpdateInfo) {
        val downloadUrl = resolveDownloadUrl(updateInfo.apkUrl)
        val versionLabel = buildVersionLabel(updateInfo)
        val safeVersion = versionLabel.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val fileName = downloadUrl.toUri().lastPathSegment
            ?: "manga-translator-$safeVersion.apk"
        val request = DownloadManager.Request(downloadUrl.toUri())
            .setTitle(getString(R.string.update_download_title, versionLabel))
            .setDescription(getString(R.string.update_download_description, versionLabel))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        val downloadManager = getSystemService(DownloadManager::class.java)
        if (downloadManager == null) {
            AppLogger.log("MainActivity", "DownloadManager not available")
            Toast.makeText(this, R.string.update_download_failed_manual_tip, Toast.LENGTH_LONG).show()
            return
        }
        try {
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            AppLogger.log("MainActivity", "Failed to enqueue update download", e)
            Toast.makeText(this, R.string.update_download_failed_manual_tip, Toast.LENGTH_LONG).show()
        }
    }

    private fun resolveDownloadUrl(apkUrl: String): String {
        val normalizedUrl = apkUrl.trim()
        val githubUrl = normalizedUrl
            .replace(
                "https://gitee.com/jedzqer/manga-translator/releases/download/",
                "https://github.com/jedzqer/manga-translator-android/releases/download/"
            )
        val giteeUrl = normalizedUrl
            .replace(
                "https://github.com/jedzqer/manga-translator-android/releases/download/",
                "https://gh-proxy.com/https://github.com/jedzqer/manga-translator-android/releases/download/"
            )
        val source = SettingsStore(this).loadLinkSource()
        return if (source == LinkSource.GITHUB) githubUrl else giteeUrl
    }

    private fun openProjectPage() {
        val intent = Intent(Intent.ACTION_VIEW, PROJECT_URL.toUri())
        val manager = packageManager
        if (intent.resolveActivity(manager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, PROJECT_URL, Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeShowCrashDialog() {
        if (!crashStateStore.wasCrashedLastRun()) return
        crashStateStore.clearCrashFlag()
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_dialog_title)
            .setMessage(R.string.crash_dialog_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.crash_dialog_share) { _, _ ->
                shareLatestLog()
            }
            .showWithScrollableMessage()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(hideGlobalProgressRunnable)
        progressHideJob?.cancel()
    }

    private fun shareLatestLog() {
        val latest = AppLogger.listLogFiles().firstOrNull()
        if (latest == null || !latest.exists()) {
            AppLogger.log("MainActivity", "No crash logs available to share")
            Toast.makeText(this, R.string.logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            latest
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, getString(R.string.crash_dialog_share))
        val manager = packageManager
        if (chooser.resolveActivity(manager) != null) {
            startActivity(chooser)
        } else {
            AppLogger.log("MainActivity", "No activity to share crash logs")
        }
    }

    companion object {
        private var hasCheckedUpdate = false
        private const val PROJECT_URL = "https://github.com/jedzqer/manga-translator"
    }

    private fun isNewerVersion(updateInfo: UpdateInfo): Boolean {
        val remoteCode = updateInfo.versionCode
        if (remoteCode <= 0) return false
        return remoteCode > VersionInfo.VERSION_CODE
    }

    private fun buildVersionLabel(updateInfo: UpdateInfo): String {
        val versionName = updateInfo.versionName.trim()
        if (versionName.isNotBlank()) {
            val urlVersion = extractVersionFromUrl(updateInfo.apkUrl)
            if (urlVersion != null && isMoreSpecificVersion(urlVersion, versionName)) {
                return urlVersion
            }
            return versionName
        }
        return if (updateInfo.versionCode > 0) updateInfo.versionCode.toString() else "unknown"
    }

    fun isRemoteNewer(updateInfo: UpdateInfo): Boolean {
        return isNewerVersion(updateInfo)
    }

    private fun buildLatestUpdateDialogMessage(updateInfo: UpdateInfo): String {
        val latestChangelog = updateInfo.changelog.trim()
        if (latestChangelog.isBlank()) {
            return getString(R.string.update_dialog_message_default)
        }
        return latestChangelog
    }

    private fun buildHistoryUpdateDialogMessage(
        updateInfo: UpdateInfo,
        versionLabel: String
    ): String {
        val history = updateInfo.history.filterNot {
            it.versionName.equals(versionLabel, ignoreCase = true)
        }
        if (history.isEmpty()) return ""
        val builder = StringBuilder()
        history.forEachIndexed { index, entry ->
            if (index > 0) {
                builder.append("\n\n")
            }
            builder.append(entry.versionName)
            if (entry.releasedAt.isNotBlank()) {
                builder.append("  ").append(entry.releasedAt)
            }
            val changelog = entry.changelog.trim()
            if (changelog.isNotBlank()) {
                builder.append('\n').append(changelog)
            }
        }
        return builder.toString()
    }

    private fun extractVersionFromUrl(url: String): String? {
        return Regex("(\\d+\\.\\d+\\.\\d+)").find(url)?.value
    }

    private fun isMoreSpecificVersion(candidate: String, current: String): Boolean {
        return candidate.count { it == '.' } > current.count { it == '.' }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        val granted = ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        if (shouldShowRequestPermissionRationale(permission)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.notification_permission_title)
                .setMessage(R.string.notification_permission_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    notificationPermissionLauncher.launch(permission)
                }
                .showWithScrollableMessage()
        } else {
            notificationPermissionLauncher.launch(permission)
        }
    }

    private fun observeGlobalProgress() {
        progressHideJob?.cancel()
        progressHideJob = lifecycleScope.launch {
            GlobalTaskProgressStore.state.collect { state ->
                renderGlobalProgress(state)
            }
        }
    }

    private fun renderGlobalProgress(state: GlobalTaskProgressState) {
        mainHandler.removeCallbacks(hideGlobalProgressRunnable)
        if (!state.visible) {
            hideGlobalProgressRunnable.run()
            return
        }

        binding.globalProgressText.text = buildCompactProgressText(state)
        val textColor = if (state.error) {
            ContextCompat.getColor(this, android.R.color.holo_red_dark)
        } else {
            resolveColorAttr(R.attr.buttonTextColor)
        }
        binding.globalProgressText.setTextColor(textColor)

        if (binding.globalProgressCard.visibility != android.view.View.VISIBLE) {
            binding.globalProgressCard.alpha = 0f
            binding.globalProgressCard.visibility = android.view.View.VISIBLE
            binding.globalProgressCard.animate().alpha(1f).setDuration(180L).start()
        } else {
            binding.globalProgressCard.alpha = 1f
        }

        if (state.terminal) {
            mainHandler.postDelayed(hideGlobalProgressRunnable, 2200L)
        }
    }

    private fun buildCompactProgressText(state: GlobalTaskProgressState): String {
        val label = when {
            state.title.contains("嵌字") -> "嵌字"
            state.title.contains("导出") -> "导出"
            else -> "翻译"
        }
        if (state.terminal) {
            return if (state.error) "$label 失败" else "$label 完成"
        }
        val stage = extractStage(state.detail)
        if (stage != null) {
            return stage
        }
        val progress = extractProgress(state)
        if (progress != null) {
            return String.format(Locale.getDefault(), "%s %d/%d", label, progress.first, progress.second)
        }
        return "$label 中"
    }

    private fun extractProgress(state: GlobalTaskProgressState): Pair<Int, Int>? {
        val progress = state.progress
        val total = state.total
        if (progress != null && total != null && total > 0) {
            return progress.coerceIn(0, total) to total
        }
        val match = Regex("(\\d+)\\s*/\\s*(\\d+)").find(state.detail) ?: return null
        val parsedProgress = match.groupValues[1].toIntOrNull() ?: return null
        val parsedTotal = match.groupValues[2].toIntOrNull() ?: return null
        if (parsedTotal <= 0) return null
        return parsedProgress.coerceIn(0, parsedTotal) to parsedTotal
    }

    private fun extractStage(detail: String): String? {
        return when {
            detail.contains("预处理") && detail.contains("OCR", ignoreCase = true) -> "预处理 OCR"
            detail.contains("预处理") && detail.contains("译名") -> "预处理 译名"
            detail.contains("预处理") -> "预处理中"
            detail.contains("OCR", ignoreCase = true) -> "OCR中"
            detail.contains("译名") -> "译名中"
            detail.contains("导出") -> "导出中"
            detail.contains("嵌字") -> "嵌字中"
            detail.contains("翻译") -> "翻译中"
            else -> null
        }
    }

    private fun resolveColorAttr(attrRes: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attrRes, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }
}
