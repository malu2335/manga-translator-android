package com.manga.translate

import com.manga.translate.platform.AsyncLogWriter
import com.manga.translate.platform.LogRecord
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AsyncLogWriterTest {
    @Test
    fun `slow sink does not block producers and overflow is reported`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = Collections.synchronizedList(mutableListOf<LogRecord>())
        val caller = Thread.currentThread()
        val writer = AsyncLogWriter(sink = {
            assertNotSame(caller, Thread.currentThread())
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            output.add(it)
        }, capacity = 2)
        try {
            writer.offer(LogRecord("I", "test", "first"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(10) { writer.offer(LogRecord("I", "test", "queued $it")) }
            release.countDown()
            writer.flush()
            assertEquals(listOf("first", "queued 0", "queued 1"),
                output.filter { it.tag == "test" }.map { it.message })
            assertTrue(output.any { it.message == "Log queue full; dropped 8 records" })
        } finally {
            release.countDown()
            writer.close()
        }
    }

    @Test
    fun `interleaved duplicate errors retain first stack and count repeats`() {
        val output = mutableListOf<LogRecord>()
        AsyncLogWriter(sink = { output.add(it) }).use { writer ->
            val error = IllegalStateException("broken")
            repeat(5) {
                writer.offer(LogRecord("E", "test", "failed", error))
                writer.offer(LogRecord("I", "test", "progress"))
            }
            writer.flush()
            assertEquals(1, output.count { it.throwable != null })
            assertEquals(5, output.count { it.message == "progress" })
            assertTrue(output.any { it.message == "failed [suppressed 4 repeated errors]" && it.throwable == null })
        }
    }

    @Test
    fun `different causes and tags are not combined`() {
        val output = mutableListOf<LogRecord>()
        AsyncLogWriter(sink = { output.add(it) }).use { writer ->
            val first = IllegalStateException("broken", IllegalArgumentException("one"))
            val second = IllegalStateException("broken", IllegalArgumentException("two"))
            // Same outer frame, different underlying cause.
            second.stackTrace = first.stackTrace
            writer.offer(LogRecord("E", "A", "failed", first))
            writer.offer(LogRecord("E", "A", "failed", second))
            writer.offer(LogRecord("E", "B", "failed", first))
            writer.flush()
            assertEquals(3, output.count { it.throwable != null })
        }
    }

    @Test
    fun `quiet errors emit summary on timer without another log call`() {
        val summary = CountDownLatch(1)
        AsyncLogWriter(sink = {
            if (it.message.contains("suppressed 1")) summary.countDown()
        }, windowNanos = TimeUnit.MILLISECONDS.toNanos(150)).use { writer ->
            repeat(2) { writer.offer(LogRecord("E", "test", "failed")) }
            assertTrue(summary.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `sink failure does not stop subsequent records`() {
        val output = mutableListOf<LogRecord>()
        AsyncLogWriter(sink = {
            if (it.message == "broken sink") error("disk failed")
            output.add(it)
        }).use { writer ->
            writer.offer(LogRecord("I", "test", "broken sink"))
            writer.offer(LogRecord("I", "test", "next"))
            writer.flush()
            assertEquals("next", output.single().message)
        }
    }
}
