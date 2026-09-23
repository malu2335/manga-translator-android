package com.manga.translate.detection

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** Keeps GPU thread affinity and never returns while native code still uses a caller's bitmap. */
internal class ModelExecutionThread : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TfliteDetection").apply { isDaemon = true }
    }

    fun <T> call(block: () -> T): T {
        val future = executor.submit(Callable { block() })
        var interrupted = false
        try {
            while (true) {
                try {
                    return future.get()
                } catch (_: InterruptedException) {
                    // A GPU invocation cannot be interrupted safely. The caller may recycle its
                    // bitmap immediately on return, so finish first and preserve interrupt status.
                    interrupted = true
                } catch (failure: ExecutionException) {
                    throw failure.cause ?: failure
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    override fun close() = executor.shutdown()
}
