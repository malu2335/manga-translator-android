package com.manga.translate.di

import android.content.Context
import com.manga.translate.CrashStateStore
import com.manga.translate.ExtractStateStore
import com.manga.translate.FloatingBubbleTranslationCoordinator
import com.manga.translate.FloatingEmptyBubbleCoordinator
import com.manga.translate.FloatingTranslationCacheStore
import com.manga.translate.FolderTranslationCoordinator
import com.manga.translate.GlossaryStore
import com.manga.translate.LibraryRepository
import com.manga.translate.LibraryUiCallbacks
import com.manga.translate.LlmClient
import com.manga.translate.MangaTranslateApp
import com.manga.translate.OcrStore
import com.manga.translate.ReadingEmptyBubbleCoordinator
import com.manga.translate.ReadingProgressStore
import com.manga.translate.SettingsStore
import com.manga.translate.TranslationPipeline
import com.manga.translate.TranslationStore
import com.manga.translate.UpdateIgnoreStore

internal class AppContainer(private val appContext: Context) {
    val settingsStore by lazy(LazyThreadSafetyMode.NONE) { SettingsStore(appContext) }
    val crashStateStore by lazy(LazyThreadSafetyMode.NONE) { CrashStateStore(appContext) }
    val updateIgnoreStore by lazy(LazyThreadSafetyMode.NONE) { UpdateIgnoreStore(appContext) }
    val readingProgressStore by lazy(LazyThreadSafetyMode.NONE) { ReadingProgressStore(appContext) }
    val libraryRepository by lazy(LazyThreadSafetyMode.NONE) { LibraryRepository(appContext) }
    val llmClient by lazy(LazyThreadSafetyMode.NONE) { LlmClient(appContext, settingsStore) }
    val translationStore by lazy(LazyThreadSafetyMode.NONE) { TranslationStore() }
    val ocrStore by lazy(LazyThreadSafetyMode.NONE) { OcrStore() }
    val glossaryStore by lazy(LazyThreadSafetyMode.NONE) { GlossaryStore() }
    val extractStateStore by lazy(LazyThreadSafetyMode.NONE) { ExtractStateStore() }
    val floatingTranslationCacheStore by lazy(LazyThreadSafetyMode.NONE) {
        FloatingTranslationCacheStore(appContext)
    }
    val libraryPrefs by lazy(LazyThreadSafetyMode.NONE) {
        appContext.getSharedPreferences(LIBRARY_PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun createTranslationPipeline(): TranslationPipeline {
        return TranslationPipeline(
            context = appContext,
            llmClient = llmClient,
            settingsStore = settingsStore,
            store = translationStore,
            ocrStore = ocrStore,
            floatingTranslationCacheStore = floatingTranslationCacheStore,
            floatingBubbleTranslationCoordinator = createFloatingBubbleTranslationCoordinator()
        )
    }

    fun createFolderTranslationCoordinator(
        translationPipeline: TranslationPipeline,
        ui: LibraryUiCallbacks
    ): FolderTranslationCoordinator {
        return FolderTranslationCoordinator(
            context = appContext,
            translationPipeline = translationPipeline,
            glossaryStore = glossaryStore,
            extractStateStore = extractStateStore,
            translationStore = translationStore,
            settingsStore = settingsStore,
            llmClient = llmClient,
            ui = ui
        )
    }

    fun createReadingEmptyBubbleCoordinator(): ReadingEmptyBubbleCoordinator {
        return ReadingEmptyBubbleCoordinator(
            context = appContext,
            translationStore = translationStore,
            glossaryStore = glossaryStore,
            llmClient = llmClient,
            libraryPrefs = libraryPrefs,
            settingsStore = settingsStore
        )
    }

    fun createFloatingEmptyBubbleCoordinator(): FloatingEmptyBubbleCoordinator {
        return FloatingEmptyBubbleCoordinator(
            context = appContext,
            llmClient = llmClient,
            floatingTranslationCacheStore = floatingTranslationCacheStore,
            settingsStore = settingsStore
        )
    }

    fun createFloatingBubbleTranslationCoordinator(): FloatingBubbleTranslationCoordinator {
        return FloatingBubbleTranslationCoordinator(
            llmClient = llmClient,
            floatingTranslationCacheStore = floatingTranslationCacheStore,
            settingsStore = settingsStore
        )
    }

    companion object {
        private const val LIBRARY_PREFS_NAME = "library_prefs"
    }
}

internal val Context.appContainer: AppContainer
    get() = (applicationContext as MangaTranslateApp).appContainer
