package com.yuanzhe.kotomo.settings

import com.yuanzhe.kotomo.ime.clipboard.Clip
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks the clipboard page's search and categories (CLIP-26, PLAN §10.7). */
class ClipFilterTest {
    private val items = listOf(
        Clip(1, "Your code is 482913", 3),
        Clip(2, "https://example.com/a", 2, pinned = true),
        Clip(3, "See you at noon", 1, phrase = true),
    )

    private fun ids(filter: ClipFilter, query: String = "") = ClipFilter.apply(items, filter, query).map { it.id }

    @Test
    fun categoriesKeepTheirItems() {
        assertEquals(listOf(1L, 2L, 3L), ids(ClipFilter.ALL))
        assertEquals(listOf(1L), ids(ClipFilter.CODES))
        assertEquals(listOf(2L), ids(ClipFilter.LINKS))
        assertEquals(listOf(2L), ids(ClipFilter.PINNED))
        assertEquals(listOf(3L), ids(ClipFilter.PHRASES))
        assertEquals(emptyList<Long>(), ids(ClipFilter.PHONES))
    }

    @Test
    fun searchIgnoresCaseAndSpaces() {
        assertEquals(listOf(3L), ids(ClipFilter.ALL, "  NOON "))
        assertEquals(emptyList<Long>(), ids(ClipFilter.CODES, "noon"))
    }
}
