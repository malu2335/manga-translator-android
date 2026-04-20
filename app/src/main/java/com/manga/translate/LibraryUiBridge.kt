package com.manga.translate

import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

internal object LibraryUiBridge {
    private val callbacks = CopyOnWriteArraySet<LibraryUiCallbacks>()

    fun register(callbacks: LibraryUiCallbacks) {
        this.callbacks.add(callbacks)
    }

    fun unregister(callbacks: LibraryUiCallbacks) {
        this.callbacks.remove(callbacks)
    }

    fun hasAttachedUi(): Boolean = callbacks.any { it.isUiAttached() }

    fun setFolderStatus(left: String, right: String = "") {
        callbacks.forEach { it.setFolderStatus(left, right) }
    }

    fun clearFolderStatus() {
        callbacks.forEach { it.clearFolderStatus() }
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
        val activeCallbacks = callbacks.filter { it.isUiAttached() }
        if (activeCallbacks.isEmpty()) {
            onSkip?.invoke()
            return
        }
        activeCallbacks.forEach { callback ->
            callback.showModelError(content, useSystemOverlay, onRetry, onSkip)
        }
    }

    fun refreshFolders() {
        callbacks.forEach { it.refreshFolders() }
    }

    fun refreshImages(folder: File) {
        callbacks.forEach { it.refreshImages(folder) }
    }

    fun isAppInForeground(): Boolean = callbacks.any { it.isAppInForeground() }

    fun canShowSystemOverlay(): Boolean = callbacks.any { it.canShowSystemOverlay() }
}
