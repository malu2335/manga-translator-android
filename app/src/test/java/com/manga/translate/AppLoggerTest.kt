package com.manga.translate

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.platform.AppLogger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppLoggerTest {
    @Test
    fun `fatal errors are immediately available with full stack even when repeated`() {
        AppLogger.init(ApplicationProvider.getApplicationContext())
        val error = IllegalStateException("fatal-test-cause")
        repeat(2) {
            AppLogger.logFatal("CrashTest", "fatal-test-message-$it", error)
            val snapshot = AppLogger.listLogFiles().single { it.name == "crash_latest.log" }.readText()
            assertTrue(snapshot.contains("fatal-test-message-$it"))
            assertTrue(snapshot.contains("IllegalStateException: fatal-test-cause"))
            assertTrue(snapshot.contains("AppLoggerTest"))
        }
        assertTrue(AppLogger.readLogs().contains("fatal-test-message-1"))
    }
}
