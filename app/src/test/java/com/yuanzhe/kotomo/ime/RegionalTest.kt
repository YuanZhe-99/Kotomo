package com.yuanzhe.kotomo.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** T11-region (PLAN §10.3): regional words read from an OpenCC table and moved up the list. */
class RegionalTest {
    private val words = Regional.parse("# comment\n鼠標\t滑鼠\n軟件\t軟體 軟件\nbroken line\n\t\n")

    @Test
    fun readsTheTablesValues() {
        assertEquals(setOf("滑鼠", "軟體", "軟件"), words)
    }

    @Test
    fun movesRegionalWordsToTheFront() {
        val list = listOf("話術" to 0, "花束" to 1, "huashu" to Candidates.TYPED, "花樹" to 2, "滑鼠" to 3)
        assertEquals(listOf("滑鼠", "話術", "花束", "huashu", "花樹"), Regional.promote(list, words) { it >= 0 }.map { it.first })
    }

    @Test
    fun leavesTheListAloneOtherwise() {
        // Negative: no regional word, one too far down, letters as typed that spell one, no words.
        val plain = listOf("話術" to 0, "花束" to 1)
        assertEquals(plain, Regional.promote(plain, words) { it >= 0 })
        val far = List(Regional.WITHIN) { "詞$it" to it } + ("滑鼠" to Regional.WITHIN)
        assertEquals(far, Regional.promote(far, words) { it >= 0 })
        val typed = listOf("話術" to 0, "滑鼠" to Candidates.TYPED)
        assertEquals(typed, Regional.promote(typed, words) { it >= 0 })
        assertEquals(plain, Regional.promote(plain, emptySet()) { it >= 0 })
    }
}
