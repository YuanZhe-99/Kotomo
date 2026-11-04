package com.yuanzhe.kotomo.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the keyboard geometry (S1, PLAN §8.10): placement, split halves, hit testing, pages. */
class LayoutTest {
    private val width = 1080f
    private val keyHeight = 150f
    private val rowGap = 20f
    private val gap = 16f

    private fun pages(split: Boolean) =
        Mode.entries.flatMap { listOf(Layout.letters(it, false, split), Layout.symbols(it, split, 0), Layout.symbols(it, split, 1)) }

    @Test
    fun symbolPagesSwitchAndFollowTheMode() {
        val zh = Layout.symbols(Mode.CHINESE, page = 0)
        val labels = zh.rows.flatMap { r -> r.keys.map { it.label } }
        assertTrue("full-width Chinese punctuation", "？" in labels && "（" in labels)
        assertEquals(KeyAction.Symbols(1), zh.rows[2].keys.first().action)
        assertEquals(KeyAction.Symbols(0), Layout.symbols(Mode.CHINESE, page = 1).rows[2].keys.first().action)
        val en = Layout.symbols(Mode.ENGLISH, page = 0).rows.flatMap { r -> r.keys.map { it.label } }
        assertTrue("$" in en && "?" in en && "？" !in en)
        val dollar = Layout.symbols(Mode.ENGLISH).rows[1].keys.first { it.label == "$" }
        assertTrue("€" in dollar.more)
    }

    @Test
    fun rowsFillTheWidthWithoutOverlap() {
        for (layout in pages(split = false)) {
            val placed = layout.place(width, keyHeight, rowGap, gap)
            placed.groupBy { it.top }.values.forEach { row ->
                row.zipWithNext().forEach { (a, b) -> assertTrue("keys overlap: ${a.key.label} ${b.key.label}", a.right <= b.left + 0.01f) }
                assertTrue(row.first().left >= -0.01f)
                assertTrue(row.last().right <= width + 0.01f)
            }
        }
    }

    @Test
    fun splitHalvesKeepTheirWidthOnAnyScreen() {
        val half = 700f
        for (screen in listOf(2400f, 1800f, 3200f)) {
            for (layout in pages(split = true)) {
                val placed = layout.place(screen, keyHeight, rowGap, gap, half)
                val rows = placed.groupBy { it.top }.values.toList()
                // Every split row has keys only inside the two halves at the edges.
                rows.dropLast(1).forEach { row ->
                    row.forEach { k -> assertTrue("${k.key.label} in the middle", k.right <= half + 0.01f || k.left >= screen - half - 0.01f) }
                    assertTrue(row.last().right <= screen + 0.01f)
                }
                // The bottom row spans the whole width; the space bar takes the rest.
                val bottom = rows.last()
                assertTrue(bottom.last().right <= screen + 0.01f && bottom.last().right > screen - gap - 1f)
            }
        }
    }

    @Test
    fun splitDuplicatesGAndV() {
        val labels = Layout.letters(Mode.MIXED, false, split = true).rows.map { r -> r.keys.map { it.label } }
        assertEquals(2, labels[1].count { it == "g" })
        assertEquals(2, labels[2].count { it == "v" })
    }

    @Test
    fun hitFindsTheKeyUnderTheTouch() {
        val placed = Layout.letters(Mode.MIXED, false).place(width, keyHeight, rowGap, gap)
        val q = placed.first { it.key.label == "q" }
        assertEquals("q", Layout.hit(placed, q.centerX, q.centerY)?.key?.label)
    }

    @Test
    fun touchesInGapsAndMarginsStillType() {
        val placed = Layout.letters(Mode.MIXED, false).place(width, keyHeight, rowGap, gap)
        val q = placed.first { it.key.label == "q" }
        val w = placed.first { it.key.label == "w" }
        assertEquals("w", Layout.hit(placed, w.left - 1f, q.centerY)?.key?.label)
        val a = placed.first { it.key.label == "a" }
        assertEquals("a", Layout.hit(placed, 1f, a.centerY)?.key?.label)
        assertEquals("q", Layout.hit(placed, q.centerX, q.top + keyHeight + rowGap / 3)?.key?.label)
    }

    @Test
    fun touchesInTheSplitMiddleGoToTheNearestHalf() {
        val placed = Layout.letters(Mode.MIXED, false, split = true).place(2400f, keyHeight, rowGap, gap, 700f)
        val t = placed.first { it.key.label == "t" }
        assertEquals("t", Layout.hit(placed, 800f, t.centerY)?.key?.label)
        assertEquals("y", Layout.hit(placed, 1650f, t.centerY)?.key?.label)
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
    fun hintsFollowTheDesign() {
        val rows = Layout.letters(Mode.MIXED, false).rows
        assertEquals("1234567890".map(Char::toString), rows[0].keys.map { it.hint })
        assertEquals(listOf("@", "#", "$", "%", "&", "-", "+", "(", ")"), rows[1].keys.map { it.hint })
    }

    @Test
    fun longPressOffersTheHintFirst() {
        val e = Layout.letters(Mode.ENGLISH, false).rows[0].keys.first { it.label == "e" }
        assertEquals("3", e.more.first())
        assertTrue("é" in e.more)
    }

    @Test
    fun popupMarksFullAndHalfWidthOnlyWhenBothAreOffered() {
        assertEquals(listOf(true, false, null), Layout.widthMarks(listOf("？", "?", "¿")))
        assertEquals(listOf(true, true, true, false), Layout.widthMarks(listOf("“", "「", "『", "\"")))
        // One kind only, or letters: nothing to tell apart.
        assertEquals(listOf(null, null, null), Layout.widthMarks(listOf("、", "，", "；")))
        assertEquals(listOf(null, null), Layout.widthMarks(listOf("3", "é")))
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
