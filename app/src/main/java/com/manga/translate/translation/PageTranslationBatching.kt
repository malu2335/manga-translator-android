package com.manga.translate.translation

internal fun buildPageTranslationBatches(batchable: List<Boolean>, batchSize: Int): List<List<Int>> {
    require(batchSize > 0)
    val groups = mutableListOf<List<Int>>()
    val pending = mutableListOf<Int>()
    fun flushPending() {
        if (pending.isNotEmpty()) {
            groups.add(pending.toList())
            pending.clear()
        }
    }
    batchable.forEachIndexed { index, canBatch ->
        if (!canBatch) {
            flushPending()
            groups.add(listOf(index))
        } else {
            pending.add(index)
            if (pending.size == batchSize) flushPending()
        }
    }
    flushPending()
    return groups
}
