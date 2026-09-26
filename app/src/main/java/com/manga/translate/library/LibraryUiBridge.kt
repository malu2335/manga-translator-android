package com.manga.translate.library

import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

internal object LibraryUiBridge {
    private val callbacks = CopyOnWriteArraySet<LibraryUiCallbacks>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusLock = Any()
    private var pendingStatus: Pair<String, String>? = null
    private val deliverStatus = Runnable {
        val status = synchronized(statusLock) {
            pendingStatus.also { pendingStatus = null }
        }
        status?.let { (left, right) ->
            callbacks.forEach { it.setFolderStatus(left, right) }
        }
    }

    fun register(callbacks: LibraryUiCallbacks) {
        this.callbacks.add(callbacks)
    }

    fun unregister(callbacks: LibraryUiCallbacks) {
        this.callbacks.remove(callbacks)
    }

    fun hasAttachedUi(): Boolean = callbacks.any { it.isUiAttached() }

    fun setFolderStatus(left: String, right: String = "") {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // A completion/clear on main supersedes any queued OCR progress.
            synchronized(statusLock) {
                mainHandler.removeCallbacks(deliverStatus)
                pendingStatus = null
            }
            callbacks.forEach { it.setFolderStatus(left, right) }
        } else {
            // OCR reports from Dispatchers.Default. Keep only the latest status
            // and one main-thread message, even when cached pages finish rapidly.
            synchronized(statusLock) {
                val needsPost = pendingStatus == null
                pendingStatus = left to right
                if (needsPost) mainHandler.post(deliverStatus)
            }
        }
    }

    fun clearFolderStatus() {
        setFolderStatus("")
    }

    fun setTranslationActionsEnabled(enabled: Boolean) {
        callbacks.forEach { it.setTranslationActionsEnabled(enabled) }
    }

    fun setFolderExportEnabled(folder: File, enabled: Boolean) {
        callbacks.forEach { it.setFolderExportEnabled(folder, enabled) }
    }

    fun showToast(resId: Int) {
        callbacks.forEach { it.showToast(resId) }
    }

    fun showToastMessage(message: String) {
        callbacks.forEach { it.showToastMessage(message) }
    }

    fun showApiError(code: String, detail: String? = null) {
        callbacks.forEach { it.showApiError(code, detail) }
    }

    fun showModelError(
        content: String,
        useSystemOverlay: Boolean,
        onRetry: (() -> Unit)?,
        onSkip: (() -> Unit)? = null
    ) {
        val snapshot = callbacks.toList()
        if (snapshot.isEmpty()) {
            onSkip?.invoke()
            return
        }
        var delivered = false
        for (callback in snapshot) {
            if (!callbacks.contains(callback)) continue
            if (!callback.isUiAttached()) continue
            callback.showModelError(content, useSystemOverlay, onRetry, onSkip)
            delivered = true
        }
        // Avoid hanging reportModelError().await() when callbacks exist but none can show UI.
        if (!delivered) {
            onSkip?.invoke()
        }
    }

    fun refreshFolders() {
        callbacks.forEach { it.refreshFolders() }
    }

    fun refreshImages(folder: File) {
        callbacks.forEach { it.refreshImages(folder) }
    }

    fun showExportSuccess(path: String) {
        callbacks.firstOrNull { it.isLibraryInForeground() }?.showExportSuccess(path)
    }

    fun isAppInForeground(): Boolean = callbacks.any { it.isAppInForeground() }

    fun canShowSystemOverlay(): Boolean = callbacks.any { it.canShowSystemOverlay() }
}
