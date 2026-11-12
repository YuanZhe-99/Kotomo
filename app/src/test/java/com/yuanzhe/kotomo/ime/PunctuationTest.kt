package com.yuanzhe.kotomo.ime

import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks smart punctuation (T13-punct, PLAN §10.8): curly quotes from the character before the
 * cursor, bracket pairing, stepping over an added mark and deleting an empty pair, each with the
 * cases that must stay as typed.
 */
class PunctuationTest {
    /** A field as text with a cursor, behind the few InputConnection calls the keyboard makes. */
    private class Field(var text: String = "", var cursor: Int = text.length) {
        val ic: InputConnection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(InputConnection::class.java)) { _, m, a ->
            when (m.name) {
                "getTextBeforeCursor" -> text.substring(maxOf(0, cursor - a[0] as Int), cursor)
                "getTextAfterCursor" -> text.substring(cursor, minOf(text.length, cursor + a[0] as Int))
                "commitText" -> {
                    val t = a[0].toString()
                    text = text.substring(0, cursor) + t + text.substring(cursor)
                    // Android: a position above 0 is relative to the text's end, 0 or below to its start.
                    val pos = a[1] as Int
                    cursor = if (pos > 0) cursor + t.length + pos - 1 else cursor + pos
                    true
                }
                "deleteSurroundingText" -> {
                    val before = a[0] as Int
                    val after = a[1] as Int
                    text = text.substring(0, cursor - before) + text.substring(cursor + after)
                    cursor -= before
                    true
                }
                "beginBatchEdit", "endBatchEdit" -> true
                else -> error("unexpected call ${m.name}")
            }
        } as InputConnection
        override fun toString() = text.substring(0, cursor) + "|" + text.substring(cursor)
    }

    @Test
    fun quotesCurlByWhatComesBefore() {
        assertEquals("“", Punctuation.curl("\"", null))
        assertEquals("“", Punctuation.curl("\"", ' '))
        assertEquals("“", Punctuation.curl("\"", '('))
        assertEquals("”", Punctuation.curl("\"", 'd'))
        assertEquals("’", Punctuation.curl("'", 'n'))
        assertEquals("‘", Punctuation.curl("'", ' '))
        // Negative: other keys pass through.
        assertEquals(",", Punctuation.curl(",", 'a'))
    }

    @Test
    fun openingMarksPairOnlyBeforeNothingOrSpace() {
        assertEquals(")", Punctuation.closer("(", null))
        assertEquals("」", Punctuation.closer("「", ' '))
        assertEquals("”", Punctuation.closer("“", '。'))
        // Negative: right before a word, and for marks that open nothing.
        assertNull(Punctuation.closer("(", 'w'))
        assertNull(Punctuation.closer(")", null))
        assertNull(Punctuation.closer("”", null))
    }

    @Test
    fun pairingStepsOverAndDeletesTheEmptyPair() {
        val f = Field("say ")
        val p = SmartPunctuation()
        p.commit(f.ic, "(", quotes = false, pair = true)
        assertEquals("say (|)", f.toString())
        p.commit(f.ic, "x", quotes = false, pair = true)
        p.commit(f.ic, ")", quotes = false, pair = true)
        assertEquals("say (x)|", f.toString())
        p.commit(f.ic, "「", quotes = false, pair = true)
        assertTrue(p.backspace(f.ic))
        assertEquals("say (x)|", f.toString())
        // Negative: nothing added, so backspace is the normal one.
        assertFalse(p.backspace(f.ic))
    }

    @Test
    fun smartQuotesPairAndCloseWithTheQuoteKey() {
        val f = Field()
        val p = SmartPunctuation()
        p.commit(f.ic, "\"", quotes = true, pair = true)
        p.commit(f.ic, "hi", quotes = true, pair = true)
        p.commit(f.ic, "\"", quotes = true, pair = true)
        assertEquals("“hi”|", f.toString())
        p.commit(f.ic, " don", quotes = true, pair = true)
        p.commit(f.ic, "'", quotes = true, pair = true)
        assertEquals("“hi” don’|", f.toString())
    }

    @Test
    fun switchesOffTypeAsIs() {
        val f = Field()
        val p = SmartPunctuation()
        p.commit(f.ic, "\"", quotes = false, pair = false)
        p.commit(f.ic, "(", quotes = false, pair = false)
        assertEquals("\"(|", f.toString())
        assertFalse(p.backspace(f.ic))
    }
}
