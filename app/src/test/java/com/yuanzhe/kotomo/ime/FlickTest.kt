package com.yuanzhe.kotomo.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T14-flick (PLAN §10.9): the 12-key layout's flick directions and the ゛゜小 cycles. */
class FlickTest {
    private val keys = Layout.flick().rows.flatMap { it.keys }
    private fun key(label: String) = keys.first { it.label == label }

    @Test
    fun flickDirectionsPickTheColumns() {
        val ka = key("か")
        assertEquals("か", Layout.flickText(ka, 2f, 3f, 20f))
        assertEquals("き", Layout.flickText(ka, -40f, 5f, 20f))
        assertEquals("く", Layout.flickText(ka, 5f, -40f, 20f))
        assertEquals("け", Layout.flickText(ka, 40f, 5f, 20f))
        assertEquals("こ", Layout.flickText(ka, 5f, 40f, 20f))
        // Negative: わ has nothing downwards, so that flick types nothing.
        assertNull(Layout.flickText(key("わ"), 0f, 40f, 20f))
        // Every kana row from あ to ら is there, ten in all, with the punctuation keys.
        assertEquals(10, keys.count { it.flick.size == 5 && it.label in "あかさたなはまやらわ" })
        assertTrue(keys.any { it.action == KeyAction.KanaModify })
    }

    @Test
    fun modifierCyclesThroughForms() {
        assertEquals('が', Kana.modify('か'))
        assertEquals('か', Kana.modify('が'))
        assertEquals("ばぱは", "は".let { var c = it[0]; buildString { repeat(3) { c = Kana.modify(c); append(c) } } })
        assertEquals('っ', Kana.modify('つ'))
        assertEquals('づ', Kana.modify('っ'))
        // Negative: a kana with no other form, and a letter, stay as they are.
        assertEquals('ん', Kana.modify('ん'))
        assertEquals('a', Kana.modify('a'))
        assertTrue(Kana.composes('ゔ') && Kana.composes('ー'))
        assertFalse(Kana.composes('ア') || Kana.composes('a'))
    }

    @Test
    fun nineKeyTypesDigitsForLetters() { // PLAN §10.9
        val keys = Layout.nineKey().rows.flatMap { it.keys }
        val digits = keys.mapNotNull { k -> (k.action as? KeyAction.Text)?.text?.takeIf { it.single() in '2'..'9' }?.let { it to k.label } }.toMap()
        assertEquals(mapOf("2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ"), digits)
        // The separator key types ', and nothing types a letter (negative).
        assertEquals(KeyAction.Text("'"), keys.first { it.label == "分词" }.action)
        assertEquals(emptyList<Key>(), keys.filter { (it.action as? KeyAction.Text)?.text?.singleOrNull() in 'a'..'z' })
    }
}
