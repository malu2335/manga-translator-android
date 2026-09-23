package com.manga.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.app.VersionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VersionInfoTest {
    @Test
    fun `version code resolves from the installed manifest`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(BuildConfig.VERSION_CODE, VersionInfo.versionCode(context))
    }

    @Test
    fun `version name resolves from the installed manifest`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(BuildConfig.VERSION_NAME, VersionInfo.versionName(context))
    }

    @Test
    fun `resolved version code is positive so update comparisons are meaningful`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(VersionInfo.versionCode(context) > 0)
    }
}
