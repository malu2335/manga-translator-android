package com.manga.translate.translation

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Pull work only when a worker finishes its previous item, including downstream
 * sends. A semaphore inside one coroutine per page does not bound retained results.
 * The input holds lightweight references; workers never accumulate task futures.
 */
internal suspend fun <T> List<T>.forEachBounded(
    concurrency: Int,
    action: suspend (index: Int, item: T) -> Unit
) {
    require(concurrency > 0)
    coroutineScope {
        val next = AtomicInteger(0)
        List(minOf(concurrency, size)) {
            launch {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val index = next.getAndIncrement()
                    if (index >= size) break
                    action(index, this@forEachBounded[index])
                }
            }
        }.joinAll()
    }
}
