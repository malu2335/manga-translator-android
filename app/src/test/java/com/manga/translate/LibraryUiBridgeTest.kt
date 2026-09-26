package com.manga.translate

import android.os.Looper
import com.manga.translate.background.ServiceLibraryUiCallbacks
import com.manga.translate.library.LibraryUiBridge
import com.manga.translate.library.LibraryUiCallbacks
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryUiBridgeTest {
    private val updates = mutableListOf<Pair<String, String>>()
    private val callback = Proxy.newProxyInstance(
        LibraryUiCallbacks::class.java.classLoader,
        arrayOf(LibraryUiCallbacks::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "setFolderStatus" -> {
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                updates += (args!![0] as String) to (args[1] as String)
                null
            }
            else -> if (method.returnType == Boolean::class.javaPrimitiveType) true else null
        }
    } as LibraryUiCallbacks

    @Before
    fun setUp() { LibraryUiBridge.register(callback) }

    @After
    fun tearDown() {
        LibraryUiBridge.unregister(callback)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `ten thousand background OCR updates deliver only latest status on main`() {
        background {
            repeat(10_000) { ServiceLibraryUiCallbacks.setFolderStatus("OCR", "$it.jpg") }
        }
        assertTrue(updates.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("OCR" to "9999.jpg"), updates)
    }

    @Test
    fun `completion and clear supersede queued progress`() {
        background { ServiceLibraryUiCallbacks.setFolderStatus("OCR", "old.jpg") }
        ServiceLibraryUiCallbacks.setFolderStatus("Done", "")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Done" to ""), updates)
        background { ServiceLibraryUiCallbacks.setFolderStatus("OCR", "old.jpg") }
        ServiceLibraryUiCallbacks.clearFolderStatus()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Done" to "", "" to ""), updates)
    }

    @Test
    fun `detached UI does not receive queued status`() {
        background { ServiceLibraryUiCallbacks.setFolderStatus("OCR", "page.jpg") }
        LibraryUiBridge.unregister(callback)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(updates.isEmpty())
    }

    private fun background(action: () -> Unit) {
        val task = java.util.concurrent.FutureTask { action() }
        Thread(task).start()
        task.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }
}
