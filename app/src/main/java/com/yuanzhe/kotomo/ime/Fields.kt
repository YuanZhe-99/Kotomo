package com.yuanzhe.kotomo.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Sorts a text field by what it is for (PLAN §8.7): ordinary text, an e-mail address, a web
 * address, a phone number, a number, a date or time, or code-like text.
 */
enum class Field { TEXT, EMAIL, URL, PHONE, NUMBER, DATETIME, CODE }

/** Names what the Enter key does in a field: a new line or the field's editor action. */
enum class EnterStyle { NEWLINE, SEARCH, SEND, GO, NEXT, PREVIOUS, DONE }

/**
 * Holds the field-adaptation and spacing rules, as pure functions of the field's input type and
 * options and of the text before the cursor, so they are unit-tested on the JVM.
 *
 * Notes: called on the main thread when a field starts and after keys; nothing here touches the
 * InputConnection.
 */
internal object Fields {
    /**
     * Sorts a field.
     * Inputs: inputType and the hint or label text from the field's EditorInfo (other apps' data:
     * any value is accepted).
     * Returns: the field kind from [inputType]; `Field.CODE` is never guessed from flags or hint
     * text (which ordinary chat boxes, web views and verification-code fields set), and is set
     * only when the person turns on "Code in this app" (`AppMemory.code`).
     */
    @Suppress("UNUSED_PARAMETER")
    fun of(inputType: Int, hint: CharSequence? = null): Field {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_PHONE -> Field.PHONE
            InputType.TYPE_CLASS_NUMBER -> Field.NUMBER
            InputType.TYPE_CLASS_DATETIME -> Field.DATETIME
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> Field.EMAIL
                InputType.TYPE_TEXT_VARIATION_URI -> Field.URL
                else -> Field.TEXT
            }
            else -> Field.TEXT
        }
    }

    /**
     * Decides whether a field is sensitive (PLAN §14, the behavior contract): a password field
     * (`TYPE_TEXT_VARIATION_PASSWORD`, `…_VISIBLE_PASSWORD`, `…_WEB_PASSWORD`,
     * `TYPE_NUMBER_VARIATION_PASSWORD`) or one that sets `IME_FLAG_NO_PERSONALIZED_LEARNING`.
     * Returns: true where nothing may be learned, recorded or handed to AI: no learning in the
     * engines, no clipboard history, no emoji recents, no per-app memory, no writing help.
     * Notes: the one definition every such rule uses; the platform treats the flag as a request,
     * Kotomo as a hard rule.
     */
    fun sensitive(inputType: Int, imeOptions: Int): Boolean {
        if (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
        return password(inputType)
    }

    /**
     * Decides whether a field gets direct typing: every key types its character, with no
     * composition and no candidates.
     * Inputs: [urlEnglish] is the person's switch `modes.url_english` (B5, device feedback
     * 2026-11-13): a web-address field (`TYPE_TEXT_VARIATION_URI`, which browsers also give their
     * address bar, where people search) types directly only with it on.
     * Returns: true for password fields, raw key input (`TYPE_NULL`), e-mail addresses and, with
     * [urlEnglish], web addresses; false for a field that only asks for no personalized learning,
     * which keeps Chinese and Japanese conversion and learns nothing ([sensitive]).
     */
    fun direct(inputType: Int, urlEnglish: Boolean): Boolean {
        if (password(inputType)) return true
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_NULL -> true
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                (urlEnglish && variation == InputType.TYPE_TEXT_VARIATION_URI)
            else -> false
        }
    }

    /** Decides whether an input type is a password's. */
    private fun password(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /**
     * Chooses the Enter key's style.
     * Returns: NEWLINE when the field asks for no Enter action or names none; else its action.
     */
    fun enter(imeOptions: Int): EnterStyle {
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterStyle.NEWLINE
        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> EnterStyle.SEARCH
            EditorInfo.IME_ACTION_SEND -> EnterStyle.SEND
            EditorInfo.IME_ACTION_GO -> EnterStyle.GO
            EditorInfo.IME_ACTION_NEXT -> EnterStyle.NEXT
            EditorInfo.IME_ACTION_PREVIOUS -> EnterStyle.PREVIOUS
            EditorInfo.IME_ACTION_DONE -> EnterStyle.DONE
            else -> EnterStyle.NEWLINE
        }
    }

    /**
     * Decides what a second quick space turns into (double-space full stop).
     * Inputs: before is the text just before the cursor (at least the last two characters), the
     * first space already typed; mode is the keyboard's mode.
     * Returns: the text that replaces that space (". " in English mode and after Latin text, "。"
     * after Chinese or Japanese text), or null when the space follows no word
     * (another space, punctuation, the start of the field).
     */
    fun period(before: CharSequence?, mode: Mode): String? {
        if (before == null || before.length < 2 || before[before.length - 1] != ' ') return null
        val last = before[before.length - 2]
        if (!last.isLetterOrDigit()) return null
        return if (mode == Mode.ENGLISH || last.code < CJK_START) ". " else "。"
    }

    /** The first code point of the CJK blocks (radicals); letters below it are Latin, Greek, …. */
    private const val CJK_START = 0x2E80

    /** Punctuation that smart space attaches to the word before it, then follows with a space. */
    fun attaches(text: String): Boolean = text.length == 1 && text[0] in ".,?!:;"
}
