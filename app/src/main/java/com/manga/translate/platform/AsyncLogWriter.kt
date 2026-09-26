package com.manga.translate.platform

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal data class LogRecord(
    val level: String,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/** Bounded, non-blocking producer path. Formatting, aggregation and I/O run on one worker. */
internal class AsyncLogWriter(
    private val sink: (LogRecord) -> Unit,
    private val clock: () -> Long = System::nanoTime,
    capacity: Int = 512,
    private val windowNanos: Long = TimeUnit.SECONDS.toNanos(30)
) : AutoCloseable {
    private val queue = ArrayBlockingQueue<LogRecord>(capacity)
    private val dropped = AtomicLong()
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "AppLogger-writer").apply { isDaemon = true }
    }
    private data class ErrorKey(val tag: String, val message: String, val trace: String?)
    private data class Repeats(val record: LogRecord, val started: Long, var count: Long = 0)
    private val errors = linkedMapOf<ErrorKey, Repeats>()

    init {
        executor.scheduleWithFixedDelay({ drain() }, 0, 100, TimeUnit.MILLISECONDS)
    }

    fun offer(record: LogRecord) {
        if (!queue.offer(record)) dropped.incrementAndGet()
    }

    private fun emit(record: LogRecord) {
        // A failed sink must not terminate the periodic worker or recurse into this logger.
        runCatching { sink(record) }
    }

    private fun summary(repeats: Repeats) {
        if (repeats.count > 0) {
            emit(repeats.record.copy(
                message = "${repeats.record.message} [suppressed ${repeats.count} repeated errors]",
                throwable = null,
                timestamp = System.currentTimeMillis()
            ))
        }
    }

    private fun drain() {
        val now = clock()
        val iterator = errors.values.iterator()
        while (iterator.hasNext()) {
            val repeats = iterator.next()
            if (now - repeats.started >= windowNanos) {
                summary(repeats)
                iterator.remove()
            }
        }
        val lost = dropped.getAndSet(0)
        if (lost > 0) emit(LogRecord("E", "AppLogger", "Log queue full; dropped $lost records"))
        // Bound each batch so a flood cannot indefinitely delay maintenance.
        repeat(512) {
            val record = queue.poll() ?: return
            if (record.level != "E") {
                emit(record)
            } else {
                val key = ErrorKey(record.tag, record.message, record.throwable?.stackTraceToString())
                val previous = errors[key]
                if (previous != null) {
                    previous.count++
                } else {
                    if (errors.size >= 128) {
                        val oldest = errors.entries.iterator()
                        summary(oldest.next().value)
                        oldest.remove()
                    }
                    // Retain no Throwable graphs in the aggregation cache.
                    errors[key] = Repeats(record.copy(throwable = null), now)
                    emit(record)
                }
            }
        }
    }

    /** Test/lifecycle barrier; never used by UI logging calls. */
    internal fun flush() {
        executor.submit {
            do {
                drain()
            } while (queue.isNotEmpty())
            errors.values.forEach(::summary)
            errors.clear()
        }.get(10, TimeUnit.SECONDS)
    }

    override fun close() {
        flush()
        executor.shutdown()
    }
}
