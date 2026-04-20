package com.manga.translate

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.manga.translate.di.AppContainer

class MangaTranslateApp : Application() {
    internal val appContainer by lazy(LazyThreadSafetyMode.NONE) { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        val settingsStore = appContainer.settingsStore
        AppCompatDelegate.setApplicationLocales(settingsStore.loadAppLanguage().toLocales())
        val themeMode = settingsStore.loadThemeMode()
        AppCompatDelegate.setDefaultNightMode(themeMode.nightMode)
        val crashStateStore = appContainer.crashStateStore
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.log("Crash", "Uncaught exception on ${thread.name}", throwable)
            crashStateStore.markCrashed()
            previousHandler?.uncaughtException(thread, throwable)
        }
        if (TranslationTaskPersistence(this).load() != null) {
            TranslationKeepAliveService.resumePendingTask(this)
        }
    }
}
