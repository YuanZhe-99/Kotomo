package com.yuanzhe.kotomo.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers spike S1's keyboard geometry: placement, hit testing in gaps, and the page contents. */
class LayoutTest {
    private val width = 1080f
    private val height = 4 * 150f
    private val gap = 16f

    @Test
    fun rowsFillTheWidthWithoutOverlap() {
        for (layout in Mode.entries.flatMap { listOf(Layout.letters(it, false), Layout.symbols(it)) }) {
            val placed = layout.place(width, height, gap)
            placed.groupBy { it.top }.values.forEach { row ->
                row.zipWithNext().forEach { (a, b) -> assertTrue("keys overlap: ${a.key.label} ${b.key.label}", a.right <= b.left) }
                assertTrue(row.last().right <= width - gap / 2 + 0.01f)
            }
        }
    }

    @Test
    fun hitFindsTheKeyUnderTheTouch() {
        val placed = Layout.letters(Mode.MIXED, false).place(width, height, gap)
        val q = placed.first { it.key.label == "q" }
        assertEquals("q", Layout.hit(placed, q.centerX, q.centerY)?.key?.label)
    }

    @Test
    fun touchesInGapsAndMarginsStillType() {
        val placed = Layout.letters(Mode.MIXED, false).place(width, height, gap)
        val q = placed.first { it.key.label == "q" }
        val w = placed.first { it.key.label == "w" }
        // In the gap between q and w, nearer to w.
        assertEquals("w", Layout.hit(placed, w.left - 1f, q.centerY)?.key?.label)
        // The half-key margin left of "a" belongs to "a".
        val a = placed.first { it.key.label == "a" }
        assertEquals("a", Layout.hit(placed, 1f, a.centerY)?.key?.label)
        // Above the first row still means the first row.
        assertEquals("q", Layout.hit(placed, q.centerX, 0f)?.key?.label)
    }

    @Test
    fun hitOnEmptyLayoutIsNull() {
        assertNull(Layout.hit(emptyList(), 10f, 10f))
    }

    @Test
    fun shiftedLettersAreCapitals() {
        val labels = Layout.letters(Mode.ENGLISH, shifted = true).rows[0].keys.map { it.label }
        assertEquals("QWERTYUIOP".map(Char::toString), labels)
    }

    @Test
    fun topRowHintsAreDigits() {
        val hints = Layout.letters(Mode.MIXED, false).rows[0].keys.map { it.hint }
        assertEquals("1234567890".map(Char::toString), hints)
    }

    @Test
    fun punctuationFollowsTheMode() {
        assertEquals("，" to "。", Layout.punctuation(Mode.MIXED))
        assertEquals("、" to "。", Layout.punctuation(Mode.JAPANESE))
        assertEquals("," to ".", Layout.punctuation(Mode.ENGLISH))
    }

    @Test
    fun bottomRowHasEmojiInPlaceOfTheLanguageKey() {
        val bottom = Layout.letters(Mode.MIXED, false).rows.last().keys.map { it.action }
        assertEquals(KeyAction.Emoji, bottom[2])
        assertTrue(bottom.contains(KeyAction.Space))
    }
}
