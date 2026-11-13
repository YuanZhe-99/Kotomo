package com.yuanzhe.kotomo.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** T10-idioms (PLAN §10.8): idiom completions from a pack's table, best first. */
class IdiomsTest {
    // Small original sample lines in the pack table's form (text, code, weight).
    private val idioms = Idioms.of(
        sequenceOf(
            "一心一意\tyi xin yi yi\t900",
            "一心二用\tyi xin er yong\t300",
            "画蛇添足\thua she tian zu\t500",
            "一心\tyi xin\t800",
            "abcd\tx\t1",
            "broken line",
            "马到成功\tma dao cheng gong\tmany",
        ),
    )

    @Test
    fun completesFromTwoOrThreeCharacters() {
        assertEquals(listOf("一意", "二用"), idioms.complete("一心"))
        assertEquals(listOf("足"), idioms.complete("画蛇添"))
        assertEquals(listOf("添足"), idioms.complete("我画蛇"))
        // Negative: a word that starts no idiom, one character, and lines that are not idioms.
        assertEquals(emptyList<String>(), idioms.complete("你好"))
        assertEquals(emptyList<String>(), idioms.complete("一"))
        assertEquals(emptyList<String>(), idioms.complete("马到"))
        assertEquals(emptyList<String>(), Idioms.NONE.complete("一心"))
    }
}
