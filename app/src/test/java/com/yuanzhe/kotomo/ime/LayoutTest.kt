package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.settings.KeyStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * In every mode a long press on the bottom row's comma and full stop offers both widths
     * (，/, and 。/.) and the other language's marks (、 and ．), defaulting to the opposite width
     * first so long-pressing toggles full-width vs half-width (and the key's own mark second).
     */
    @Test
    fun commaAndFullStopOfferBothWidthsInEveryMode() {
        for (mode in listOf(Mode.CHINESE, Mode.MIXED, Mode.JAPANESE, Mode.ENGLISH)) {
            val (comma, stop) = Layout.punctuation(mode)
            val keys = Layout.letters(mode, false).rows.last().keys
            val commaMore = keys.first { it.action == KeyAction.Text(comma) }.more
            val stopMore = keys.first { it.action == KeyAction.Text(stop) }.more
            if (mode == Mode.ENGLISH) {
                assertEquals("，", commaMore.first())
                assertEquals("。", stopMore.first())
            } else {
                assertEquals(",", commaMore.first())
                assertEquals(".", stopMore.first())
            }
            assertEquals(comma, commaMore[1])
            assertEquals(stop, stopMore[1])
            assertTrue("$mode comma: $commaMore", commaMore.containsAll(listOf(",", "，", "、")))
            assertTrue("$mode full stop: $stopMore", stopMore.containsAll(listOf(".", "。", "．")))
            // Both widths are there, so the popup marks which is which.
            assertTrue(Layout.widthMarks(commaMore).containsAll(listOf(true, false)) && Layout.widthMarks(stopMore).containsAll(listOf(true, false)))
        }
    }

    /** Full-width symbols on both symbol pages default to half-width on long press and label both widths. */
    @Test
    fun fullWidthSymbolsDefaultToHalfWidthOnBothSymbolPages() {
        val fullWidth = setOf(
            "￥", "（", "）", "“", "”", "‘", "’", "「", "」", "『", "』", "【", "】", "《", "》",
            "〈", "〉", "、", "；", "：", "・", "·", "？", "！", "…", "—", "～", "｜",
        )
        for (mode in listOf(Mode.CHINESE, Mode.MIXED, Mode.JAPANESE)) {
            for (page in listOf(0, 1)) {
                val keys = Layout.symbols(mode, page = page).rows.flatMap { it.keys }
                for (k in keys.filter { it.label in fullWidth }) {
                    assertTrue("$mode page $page ${k.label}: empty more", k.more.size >= 2)
                    val marks = Layout.widthMarks(k.more)
                    assertEquals("$mode page $page ${k.label}: default is not half-width (${k.more})", false, marks[0])
                    assertEquals("$mode page $page ${k.label}: second is not full-width (${k.more})", true, marks[1])
                }
            }
        }
        val phoneZero = Layout.pad(Field.PHONE).rows.flatMap { it.keys }.first { it.label == "0" }
        assertEquals("+", phoneZero.more.first())
    }

    /** Symbol pages in Mixed, Chinese and Japanese modes default bottom-row comma/period to half-width, while letter pages keep full-width. */
    @Test
    fun symbolPagesUseHalfWidthCommaAndStopOnBottomRow() {
        for (mode in listOf(Mode.MIXED, Mode.CHINESE, Mode.JAPANESE)) {
            val (fullComma, fullStop) = Layout.punctuation(mode)
            val letterBottom = Layout.letters(mode, false).rows.last().keys
            assertTrue("$mode letters missing $fullComma", letterBottom.any { it.label == fullComma && it.more.first() == "," })
            assertTrue("$mode letters missing $fullStop", letterBottom.any { it.label == fullStop && it.more.first() == "." })

            for (page in listOf(0, 1)) {
                val bottom = Layout.symbols(mode, page = page).rows.last().keys
                val comma = bottom.firstOrNull { it.action == KeyAction.Text(",") }
                val stop = bottom.firstOrNull { it.action == KeyAction.Text(".") }
                assertTrue("$mode page $page missing half-width comma", comma != null && comma.label == ",")
                assertTrue("$mode page $page missing half-width stop", stop != null && stop.label == ".")
                assertEquals("$mode page $page comma long-press default", fullComma, comma!!.more.first())
                assertEquals("$mode page $page stop long-press default", fullStop, stop!!.more.first())
                assertEquals(listOf(true, false), Layout.widthMarks(comma.more).take(2))
                assertEquals(listOf(true, false), Layout.widthMarks(stop.more).take(2))
            }
        }
    }

    /** Right-side and narrow-width popups keep the default choice directly over the key without clipping. */
    @Test
    fun popupPlacesDefaultChoiceOverTheKeyOnEverySideAndWidth() {
        val maxCell = 116f
        for (w in listOf(1080f, 700f, 540f)) {
            val letters = Layout.letters(Mode.MIXED, false).place(w, keyHeight, rowGap, gap)
            val symbols = Layout.symbols(Mode.MIXED, page = 1).place(w, keyHeight, rowGap, gap)
            for (label in listOf("q", "e", "a", "i", "o", "p", "。")) {
                val pk = letters.first { it.key.label == label }
                val popup = Layout.placePopup(pk.key.more.size, 0, pk.centerX, w, maxCell)
                assertEquals("$w $label default at selected", 0, popup.order[popup.selected])
                assertTrue("$w $label left ${popup.left}", popup.left >= -0.01f)
                assertTrue("$w $label right", popup.left + pk.key.more.size * popup.cellWidth <= w + 0.01f)
                val slotLeft = popup.left + popup.selected * popup.cellWidth
                val slotRight = slotLeft + popup.cellWidth
                assertTrue(
                    "$w $label anchor ${pk.centerX} outside $slotLeft..$slotRight",
                    pk.centerX in (slotLeft - popup.cellWidth * 0.6f)..(slotRight + popup.cellWidth * 0.6f),
                )
            }
            for (label in listOf("【", "～")) {
                val pk = symbols.first { it.key.label == label }
                val popup = Layout.placePopup(pk.key.more.size, 0, pk.centerX, w, maxCell)
                assertEquals("$w $label default at selected", 0, popup.order[popup.selected])
                assertTrue("$w $label left ${popup.left}", popup.left >= -0.01f)
                assertTrue("$w $label right", popup.left + pk.key.more.size * popup.cellWidth <= w + 0.01f)
            }
            // Language selector keeps its configured order.
            val emoji = letters.first { it.key.action == KeyAction.Emoji }
            val fixed = Layout.placePopup(5, 2, emoji.centerX, w, maxCell, reorder = false)
            assertEquals(listOf(0, 1, 2, 3, 4), fixed.order)
            assertEquals(2, fixed.selected)
        }
    }

    /** T14-keys-1 (PLAN §10.9): the number row, mirrored function keys and the space width. */
    @Test
    fun keyStyleAddsDigitsMirrorsAndWidensSpace() {
        val plain = Layout.letters(Mode.ENGLISH, false)
        val style = KeyStyle(numberRow = true, mirrored = true, spacePercent = 140)
        val styled = Layout.letters(Mode.ENGLISH, false, style = style)
        assertEquals(plain.rows.size + 1, styled.rows.size)
        assertEquals("1234567890".map { it.toString() }, styled.rows[0].keys.map { it.label })
        // Mirrored: Enter first on the bottom row, backspace first on the row above; letters stay.
        assertEquals(KeyAction.Enter, styled.rows.last().keys.first().action)
        assertEquals(KeyAction.Backspace, styled.rows[3].keys.first().action)
        assertEquals(plain.rows[0].keys, styled.rows[1].keys)
        val space = { l: Layout -> l.rows.last().keys.first { it.action == KeyAction.Space }.width }
        assertEquals(space(plain) * 1.4f, space(styled), 0.001f)
        // Negative: the default style changes nothing, and split rows keep their halves when mirrored.
        assertEquals(plain, Layout.letters(Mode.ENGLISH, false, style = KeyStyle()))
        val split = Layout.letters(Mode.ENGLISH, false, split = true, style = KeyStyle(mirrored = true))
        val third = split.rows[2]
        assertEquals(KeyAction.Backspace, third.keys.first().action)
        assertEquals(third.keys.size - 5, third.splitAt)
    }

    /** T14-keys-2 (PLAN §10.9): the person's long-press symbols replace a letter's hint and popup. */
    @Test
    fun customLongPressSymbols() {
        val map = KeyStyle.parse("a=@ á\nB=β\nzz=x\n7=x\nq=\nc=©;")
        assertEquals(mapOf('a' to "@á", 'b' to "β", 'c' to "©;"), map)
        assertEquals(map, KeyStyle.parse(KeyStyle.format(map)))
        val keys = Layout.letters(Mode.ENGLISH, false, style = KeyStyle(longPress = map)).rows.flatMap { it.keys }
        val a = keys.first { it.label == "a" }
        assertEquals("@", a.hint)
        assertEquals(listOf("@", "á", "à"), a.more.take(3))
        // Negative: a letter without its own symbols keeps the design's hint.
        assertEquals("#", keys.first { it.label == "s" }.hint)
    }

    /** B1: the separator takes Shift's place and width while composing; without it the key is Shift. */
    @Test
    fun separatorTakesShiftsPlace() {
        for (split in listOf(false, true)) {
            val shift = Layout.letters(Mode.CHINESE, false, split).rows[2].keys.first()
            val separator = Layout.letters(Mode.CHINESE, false, split, separator = true).rows[2].keys.first()
            assertEquals(KeyAction.Shift, shift.action)
            assertEquals(KeyAction.Text("'"), separator.action)
            assertEquals(Layout.SEPARATOR, separator.label)
            assertEquals(shift.width, separator.width)
        }
    }

    /**
     * Symbol pages cover all 32 printable ASCII symbols and core math operators in every mode
     * without duplicate keys on a page or across page 0 and page 1.
     */
    @Test
    fun symbolPagesHaveNoDuplicatesAndCoverAllAsciiSymbols() {
        val printableAsciiSymbols = (0x21..0x7E)
            .map { it.toChar() }
            .filter { !it.isLetterOrDigit() }
            .map { it.toString() }
        assertEquals(32, printableAsciiSymbols.size)
        val mathSymbols = "+-*/=×÷±≠≈∞≤≥‰°".map { it.toString() }

        for (mode in Mode.entries) {
            val page0Keys = Layout.symbols(mode, page = 0).rows.dropLast(1).flatMap { it.keys }
                .filter { it.action is KeyAction.Text }
            val page1Keys = Layout.symbols(mode, page = 1).rows.dropLast(1).flatMap { it.keys }
                .filter { it.action is KeyAction.Text }

            val p0Labels = page0Keys.map { it.label }
            val p1Labels = page1Keys.map { it.label }
            assertEquals("$mode page 0 has duplicates: $p0Labels", p0Labels.size, p0Labels.toSet().size)
            assertEquals("$mode page 1 has duplicates: $p1Labels", p1Labels.size, p1Labels.toSet().size)
            assertTrue("$mode repeats keys across page 0 and 1: ${p0Labels.toSet() intersect p1Labels.toSet()}", (p0Labels.toSet() intersect p1Labels.toSet()).isEmpty())

            assertTrue("$mode page 0 missing /, *, _", listOf("/", "*", "_").all { it in p0Labels })
            assertTrue(
                "$mode page 1 missing core symbols",
                listOf("\\", "[", "]", "{", "}", "<", ">", "%", "`", "|", "=", "^").all { it in p1Labels },
            )

            val allKeys = Layout.symbols(mode, page = 0).rows.flatMap { it.keys } +
                Layout.symbols(mode, page = 1).rows.flatMap { it.keys }
            val direct = allKeys.filter { it.action is KeyAction.Text }.map { it.label }.toSet()
            val allReachable = direct + allKeys.flatMap { it.more }
            val defaults = allKeys.mapNotNull { it.more.firstOrNull() }.toSet()

            for (ascii in printableAsciiSymbols) {
                assertTrue("$mode missing ASCII symbol $ascii", ascii in allReachable)
                if (ascii !in direct) {
                    assertTrue("$mode non-direct ASCII $ascii is not a primary long-press choice", ascii in defaults)
                }
            }
            for (math in mathSymbols) {
                assertTrue("$mode missing math symbol $math", math in allReachable)
            }
        }
    }

    /** Split symbol pages never separate an opening bracket/quote from its closing counterpart. */
    @Test
    fun symbolPagesKeepBracketAndQuotePairsUnbrokenInSplitMode() {
        val pairs = listOf(
            "(" to ")", "（" to "）", "“" to "”", "‘" to "’",
            "「" to "」", "『" to "』", "【" to "】", "《" to "》",
            "〈" to "〉", "[" to "]", "{" to "}", "<" to ">",
        )
        for (mode in Mode.entries) {
            for (page in 0..1) {
                for (row in Layout.symbols(mode, split = true, page = page).rows) {
                    if (row.splitAt < 0) continue
                    val left = row.keys.subList(0, row.splitAt).map { it.label }
                    val right = row.keys.subList(row.splitAt, row.keys.size).map { it.label }
                    for ((open, close) in pairs) {
                        assertFalse(
                            "$mode page $page splits pair $open$close across splitAt=${row.splitAt}",
                            open in left && close in right,
                        )
                    }
                }
            }
        }
    }

    /** Every symbol key with long-press options lists itself, has no duplicates, and offers key operators. */
    @Test
    fun symbolLongPressVariantsIncludeKeyItselfAndNoDuplicates() {
        for (mode in Mode.entries) {
            for (page in 0..1) {
                val keys = Layout.symbols(mode, page = page).rows.dropLast(1).flatMap { it.keys }
                for (k in keys) {
                    if (k.more.isEmpty()) continue
                    assertEquals("$mode page $page ${k.label} has duplicate long-press items", k.more.size, k.more.distinct().size)
                    assertTrue("$mode page $page ${k.label} missing self in more=${k.more}", k.label in k.more)
                }
                if (page == 0) {
                    val slash = keys.first { it.label == "/" }
                    assertTrue("$mode / missing ÷ or \\", listOf("÷", "\\").all { it in slash.more })
                    val star = keys.first { it.label == "*" }
                    assertEquals("$mode * should offer × first", "×", star.more.first())
                } else {
                    val backslash = keys.first { it.label == "\\" }
                    assertTrue("$mode \\ missing /", "/" in backslash.more)
                    val pipe = keys.first { it.label == "|" }
                    assertEquals("$mode | should offer ｜ first", "｜", pipe.more.first())
                }
            }
        }
    }
}
