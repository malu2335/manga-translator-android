package com.manga.translate

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.background.PendingTranslationTasks
import com.manga.translate.background.TranslationKeepAliveService
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.platform.GlobalTaskProgressStore
import com.manga.translate.storage.FolderTranslationTaskDescriptor
import com.manga.translate.storage.TranslationTaskDescriptor
import com.manga.translate.storage.toJsonString
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranslationTaskHandoffTest {
    @After
    fun tearDown() = GlobalTaskProgressStore.hide()

    @Test
    fun `ten thousand image paths never enter the service intent`() {
        for (fullTranslate in listOf(false, true)) {
            val descriptor = descriptor(fullTranslate)
            val context = CapturingContext()
            TranslationKeepAliveService.startTranslationTask(context, descriptor)
            val intent = requireNotNull(context.startedIntent)
            val parcel = Parcel.obtain()
            try {
                // Demonstrate the old payload exceeds Binder's shared 1 MiB buffer.
                parcel.writeString(descriptor.toJsonString())
                assertTrue(parcel.dataSize() > 1024 * 1024)
            } finally {
                parcel.recycle()
            }
            val intentParcel = Parcel.obtain()
            try {
                intent.writeToParcel(intentParcel, 0)
                assertTrue(intentParcel.dataSize() < 16 * 1024)
            } finally {
                intentParcel.recycle()
            }
            assertSame(descriptor, TranslationKeepAliveService.takePendingTranslationTask(intent))
            assertNull(TranslationKeepAliveService.takePendingTranslationTask(intent))
        }
    }

    @Test
    fun `failed service start releases task and reports failure`() {
        val context = CapturingContext(fail = true)
        TranslationKeepAliveService.startTranslationTask(context, descriptor(false))
        assertNull(TranslationKeepAliveService.takePendingTranslationTask(requireNotNull(context.startedIntent)))
        assertTrue(GlobalTaskProgressStore.state.value.error)
    }

    @Test
    fun `handoff is isolated one shot and cannot survive process restart`() {
        val pending = PendingTranslationTasks()
        val first = descriptor(false)
        val second = descriptor(true)
        val firstId = pending.put(first)
        val secondId = pending.put(second)
        assertNull(PendingTranslationTasks().take(firstId))
        assertNull(pending.take(null))
        assertNull(pending.take("missing"))
        assertSame(second, pending.take(secondId))
        assertSame(first, pending.take(firstId))
        assertNull(pending.take(firstId))
    }

    private class CapturingContext(private val fail: Boolean = false) :
        ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        var startedIntent: Intent? = null
        override fun startForegroundService(service: Intent): ComponentName? {
            startedIntent = service
            if (fail) throw IllegalStateException("Service unavailable")
            return service.component
        }
    }

    private fun descriptor(full: Boolean) = TranslationTaskDescriptor(
        mode = "single",
        tasks = listOf(FolderTranslationTaskDescriptor(
            folderPath = "/storage/emulated/0/Android/data/com.manga.translate/files/manga_library/series/chapter",
            imagePaths = List(10_000) {
                "/storage/emulated/0/Android/data/com.manga.translate/files/manga_library/series/chapter/page_$it.jpg"
            },
            force = false,
            fullTranslate = full,
            glossaryProcessingEnabled = true,
            useVlDirectTranslate = false,
            language = TranslationLanguage.JA_TO_ZH
        ))
    )
}
