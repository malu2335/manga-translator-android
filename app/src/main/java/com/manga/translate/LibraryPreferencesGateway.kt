package com.manga.translate

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal class LibraryPreferencesGateway(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val repository: LibraryRepository
) {
    fun isFullTranslateEnabled(folder: File): Boolean {
        return prefs.getBoolean(
            fullTranslateKeyPrefix + settingsFolder(folder).absolutePath,
            true
        )
    }

    fun setFullTranslateEnabled(folder: File, enabled: Boolean) {
        prefs.edit() {
            putBoolean(fullTranslateKeyPrefix + settingsFolder(folder).absolutePath, enabled)
        }
    }

    fun getTranslationLanguage(folder: File): TranslationLanguage {
        val value = prefs.getString(languageKeyPrefix + settingsFolder(folder).absolutePath, null)
        return TranslationLanguage.fromString(value)
    }

    fun isVlDirectTranslateEnabled(folder: File): Boolean {
        return prefs.getBoolean(
            vlDirectTranslateKeyPrefix + settingsFolder(folder).absolutePath,
            false
        )
    }

    fun getReadingMode(folder: File): FolderReadingMode {
        val value = prefs.getString(
            readingModeKeyPrefix + settingsFolder(folder).absolutePath,
            null
        )
        return FolderReadingMode.fromPref(value)
    }

    fun setTranslationLanguage(folder: File, language: TranslationLanguage) {
        prefs.edit() {
            putString(languageKeyPrefix + settingsFolder(folder).absolutePath, language.name)
        }
    }

    fun setVlDirectTranslateEnabled(folder: File, enabled: Boolean) {
        prefs.edit() {
            putBoolean(vlDirectTranslateKeyPrefix + settingsFolder(folder).absolutePath, enabled)
        }
    }

    fun setReadingMode(folder: File, mode: FolderReadingMode) {
        prefs.edit() {
            putString(readingModeKeyPrefix + settingsFolder(folder).absolutePath, mode.prefValue)
        }
    }

    fun getImportTreeUri(): Uri? {
        return prefs.getString(importTreeKey, null)?.let(Uri::parse)
    }

    fun setImportTreeUri(uri: Uri) {
        prefs.edit() {putString(importTreeKey, uri.toString())}
    }

    fun getExportTreeUri(): Uri? {
        return prefs.getString(exportTreeKey, null)?.let(Uri::parse)
    }

    fun setExportTreeUri(uri: Uri) {
        prefs.edit() {putString(exportTreeKey, uri.toString())}
    }

    fun hasImportPermission(uri: Uri): Boolean {
        val persisted = context
            .contentResolver
            .persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }
        val root = DocumentFile.fromTreeUri(context, uri)
        return persisted && root?.canRead() == true
    }

    fun hasExportPermission(uri: Uri): Boolean {
        val persisted = context
            .contentResolver
            .persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        val root = DocumentFile.fromTreeUri(context, uri)
        return persisted && root?.canWrite() == true
    }

    fun buildImportInitialUri(): Uri? {
        return getImportTreeUri()?.takeIf(::hasImportPermission)
    }

    fun buildExportInitialUri(): Uri? {
        return try {
            DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Documents/manga-translator"
            )
        } catch (_: Exception) {
            try {
                DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Documents"
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun settingsFolder(folder: File): File = repository.resolveSettingsFolder(folder)

    private companion object {
        private const val importTreeKey = "ehviewer_tree_uri"
        private const val exportTreeKey = "export_tree_uri"
        private const val fullTranslateKeyPrefix = "full_translate_enabled_"
        private const val languageKeyPrefix = "translation_language_"
        private const val vlDirectTranslateKeyPrefix = "vl_direct_translate_enabled_"
        private const val readingModeKeyPrefix = "reading_mode_"
    }
}
