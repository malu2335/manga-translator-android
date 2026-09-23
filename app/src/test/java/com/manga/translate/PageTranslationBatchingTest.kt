package com.manga.translate

import com.manga.translate.translation.buildPageTranslationBatches
import org.junit.Assert.assertEquals
import org.junit.Test

class PageTranslationBatchingTest {
    @Test
    fun `five pages with two per request produces three ordered requests`() {
        assertEquals(
            listOf(listOf(0, 1), listOf(2, 3), listOf(4)),
            buildPageTranslationBatches(List(5) { true }, 2)
        )
    }

    @Test
    fun `partial and failed pages remain standalone and break batches`() {
        assertEquals(
            listOf(listOf(0, 1), listOf(2), listOf(3), listOf(4, 5)),
            buildPageTranslationBatches(listOf(true, true, false, false, true, true), 3)
        )
    }

    @Test
    fun `one page setting disables batching`() {
        assertEquals(listOf(listOf(0), listOf(1)), buildPageTranslationBatches(listOf(true, true), 1))
    }

    @Test
    fun `short and empty tasks never wait for a full batch`() {
        assertEquals(listOf(listOf(0, 1)), buildPageTranslationBatches(listOf(true, true), 20))
        assertEquals(emptyList<List<Int>>(), buildPageTranslationBatches(emptyList(), 20))
    }
}
