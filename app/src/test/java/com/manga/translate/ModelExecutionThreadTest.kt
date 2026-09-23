package com.manga.translate

import com.manga.translate.detection.ModelExecutionThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class ModelExecutionThreadTest {
    @Test
    fun `different callers use the same native resource thread`() {
        ModelExecutionThread().use { worker ->
            val creationThread = worker.call { Thread.currentThread() }
            assertNotSame(Thread.currentThread(), creationThread)
            var invocationThread: Thread? = null
            val caller = Thread { invocationThread = worker.call { Thread.currentThread() } }
            caller.start()
            caller.join(5000)
            assertFalse(caller.isAlive)
            assertSame(creationThread, invocationThread)
            assertSame(creationThread, worker.call { Thread.currentThread() })
        }
    }

    @Test
    fun `native linkage failures reach the caller unchanged and worker remains usable`() {
        ModelExecutionThread().use { worker ->
            val failure = UnsatisfiedLinkError("test delegate unavailable")
            try {
                worker.call<Unit> { throw failure }
                fail("Expected native failure")
            } catch (actual: UnsatisfiedLinkError) {
                assertSame(failure, actual)
            }
            assertEquals(42, worker.call { 42 })
        }
    }

    @Test
    fun `interrupted caller waits until bitmap is no longer used`() {
        ModelExecutionThread().use { worker ->
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val returned = CountDownLatch(1)
            val preservedInterrupt = AtomicBoolean()
            val caller = Thread {
                worker.call {
                    entered.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                }
                preservedInterrupt.set(Thread.currentThread().isInterrupted)
                returned.countDown()
            }
            try {
                caller.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                caller.interrupt()
                assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
            } finally {
                finish.countDown()
                caller.join(5000)
            }
            assertFalse(caller.isAlive)
            assertEquals(0L, returned.count)
            assertTrue(preservedInterrupt.get())
        }
    }
}
