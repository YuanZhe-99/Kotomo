package com.yuanzhe.kotomo.settings

import com.yuanzhe.kotomo.ime.clipboard.Clip
import com.yuanzhe.kotomo.ime.clipboard.ClipImage
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks the clipboard page's search and categories (CLIP-26, PLAN §10.7). */
class ClipFilterTest {
    private val items = listOf(
        Clip(1, "Your code is 482913", 4),
        Clip(2, "https://example.com/a", 3, pinned = true),
        Clip(3, "See you at noon", 2, phrase = true),
        Clip(4, "", 1, image = ClipImage("0".repeat(64) + ".kci", "image/png", 64, 48)),
    )

    private fun ids(filter: ClipFilter, query: String = "") = ClipFilter.apply(items, filter, query).map { it.id }

    @Test
    fun categoriesKeepTheirItems() {
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(ClipFilter.ALL))
        assertEquals(listOf(1L), ids(ClipFilter.CODES))
        assertEquals(listOf(2L), ids(ClipFilter.LINKS))
        assertEquals(listOf(2L), ids(ClipFilter.PINNED))
        assertEquals(listOf(3L), ids(ClipFilter.PHRASES))
        assertEquals(listOf(4L), ids(ClipFilter.IMAGES))
        assertEquals(listOf(3L), ids(ClipFilter.TEXT))
        assertEquals(emptyList<Long>(), ids(ClipFilter.PHONES))
    }

    @Test
    fun searchIgnoresCaseAndSpaces() {
        assertEquals(listOf(3L), ids(ClipFilter.ALL, "  NOON "))
        assertEquals(emptyList<Long>(), ids(ClipFilter.CODES, "noon"))
        assertEquals(emptyList<Long>(), ids(ClipFilter.IMAGES, "png"))
    }
}
