package com.yuanzhe.kotomo.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers field adaptation (T13, PLAN §8.7) and the spacing gestures (PLAN §8.1): field kinds,
 * Enter styles, the keypads and bottom rows, double-space full stop and smart space.
 */
class FieldsTest {
    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun fieldKindsFollowTheInputType() {
        assertEquals(Field.EMAIL, Fields.of(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, null))
        assertEquals(Field.EMAIL, Fields.of(text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, null))
        assertEquals(Field.URL, Fields.of(text or InputType.TYPE_TEXT_VARIATION_URI, null))
        assertEquals(Field.PHONE, Fields.of(InputType.TYPE_CLASS_PHONE, null))
        assertEquals(Field.NUMBER, Fields.of(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, null))
        assertEquals(Field.DATETIME, Fields.of(InputType.TYPE_CLASS_DATETIME, null))
        assertEquals(Field.TEXT, Fields.of(text, "Message"))
    }

    @Test
    fun codeLikeFieldsAreRecognised() {
        assertEquals(Field.CODE, Fields.of(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, null))
        assertEquals(Field.CODE, Fields.of(text, "Enter a command"))
        assertEquals(Field.CODE, Fields.of(text, "输入代码"))
        // Negative: an ordinary multi-line field, and a word that merely contains "code".
        assertEquals(Field.TEXT, Fields.of(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE, "Write a reply"))
        assertEquals(Field.TEXT, Fields.of(text, "Decoded message"))
    }

    @Test
    fun enterFollowsTheEditorAction() {
        assertEquals(EnterStyle.SEARCH, Fields.enter(EditorInfo.IME_ACTION_SEARCH))
        assertEquals(EnterStyle.SEND, Fields.enter(EditorInfo.IME_ACTION_SEND))
        assertEquals(EnterStyle.GO, Fields.enter(EditorInfo.IME_ACTION_GO))
        assertEquals(EnterStyle.DONE, Fields.enter(EditorInfo.IME_ACTION_DONE))
        // A chat box that wants Enter to break lines keeps the newline key.
        assertEquals(EnterStyle.NEWLINE, Fields.enter(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
        assertEquals(EnterStyle.NEWLINE, Fields.enter(EditorInfo.IME_ACTION_UNSPECIFIED))
    }

    @Test
    fun emailAndUrlFieldsOfferTheirSymbols() {
        val email = Layout.letters(Mode.CHINESE, false, field = Field.EMAIL).rows.last().keys.map { it.label }
        assertTrue("@" in email && "." in email && "，" !in email)
        val url = Layout.letters(Mode.CHINESE, false, field = Field.URL).rows.last().keys.map { it.label }
        assertTrue("/" in url && ".com" in url && "." in url)
        val code = Layout.letters(Mode.CHINESE, false, field = Field.CODE).rows.last().keys.map { it.label }
        assertTrue("," in code && "，" !in code)
        // Negative: ordinary Chinese text keeps full-width punctuation.
        assertTrue("，" in Layout.letters(Mode.CHINESE, false).rows.last().keys.map { it.label })
    }

    @Test
    fun sensitiveFieldsAreOneDefinition() { // T12
        assertTrue(Fields.sensitive(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0))
        assertTrue(Fields.sensitive(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0))
        assertTrue(Fields.sensitive(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0))
        assertTrue(Fields.sensitive(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0))
        assertTrue(Fields.sensitive(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        // Negative: ordinary, e-mail and URL fields are not sensitive (they may learn and record).
        assertFalse(Fields.sensitive(text, 0))
        assertFalse(Fields.sensitive(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0))
        assertFalse(Fields.sensitive(text or InputType.TYPE_TEXT_VARIATION_URI, 0))
    }

    @Test
    fun noLearningFieldsStillConvert() { // T12: the flag stops learning, not Chinese or Japanese
        assertFalse(Fields.direct(text))
        assertTrue(Fields.sensitive(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        // Negative: passwords, raw keys, e-mail and URLs keep direct typing.
        assertTrue(Fields.direct(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(Fields.direct(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertTrue(Fields.direct(InputType.TYPE_NULL))
        assertTrue(Fields.direct(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertTrue(Fields.direct(text or InputType.TYPE_TEXT_VARIATION_URI))
        assertFalse(Fields.direct(InputType.TYPE_CLASS_PHONE))
    }

    @Test
    fun keypadsHaveDigitsAndTheirSymbols() {
        val phone = Layout.pad(Field.PHONE).rows.flatMap { it.keys }
        assertEquals((0..9).map { "$it" }.toSet(), phone.map { it.label }.filter { it.length == 1 && it[0].isDigit() }.toSet())
        assertTrue("*" in phone.map { it.label } && "#" in phone.map { it.label })
        assertTrue("+" in phone.first { it.label == "0" }.more)
        val number = Layout.pad(Field.NUMBER).rows.flatMap { it.keys }.map { it.label }
        assertTrue("." in number && "*" !in number)
        listOf(Field.PHONE, Field.NUMBER, Field.DATETIME).forEach { f ->
            val actions = Layout.pad(f).rows.flatMap { it.keys }.map { it.action }
            assertTrue(KeyAction.Backspace in actions && KeyAction.Enter in actions && KeyAction.Space in actions)
        }
    }

    @Test
    fun doubleSpaceMakesAFullStopAfterAWord() {
        assertEquals(". ", Fields.period("hello ", Mode.ENGLISH))
        assertEquals(". ", Fields.period("ok ", Mode.MIXED))
        assertEquals("。", Fields.period("你好 ", Mode.CHINESE))
        assertEquals("。", Fields.period("こんにちは ", Mode.JAPANESE))
        // Negative: after another space, after punctuation, at the start of the field.
        assertNull(Fields.period("hello  ", Mode.ENGLISH))
        assertNull(Fields.period("end. ", Mode.ENGLISH))
        assertNull(Fields.period(" ", Mode.ENGLISH))
        assertNull(Fields.period(null, Mode.ENGLISH))
    }

    @Test
    fun smartSpaceAttachesSentencePunctuationOnly() {
        listOf(".", ",", "?", "!", ":", ";").forEach { assertTrue(it, Fields.attaches(it)) }
        listOf("(", "@", "a", "。", "..").forEach { assertFalse(it, Fields.attaches(it)) }
    }
}
