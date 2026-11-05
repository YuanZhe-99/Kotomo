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
 * Holds the field-adaptation and spacing rules, as pure functions of the field's input type,
 * options and hint and of the text before the cursor, so they are unit-tested on the JVM.
 *
 * Notes: called on the main thread when a field starts and after keys; nothing here touches the
 * InputConnection.
 */
internal object Fields {
    /** Words in a field's hint or label that mark it as code-like (commands, queries, code). */
    private val codeHint = Regex(
        "\\b(code|command|regex|sql|shell|terminal|script)\\b|代码|代碼|命令|コード|コマンド",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Sorts a field.
     * Inputs: inputType and the hint or label text from the field's EditorInfo (other apps' data:
     * any value is accepted).
     * Returns: the field kind; a multi-line field without suggestions or a hint naming code,
     * commands or queries is CODE.
     */
    fun of(inputType: Int, hint: CharSequence?): Field {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_PHONE -> Field.PHONE
            InputType.TYPE_CLASS_NUMBER -> Field.NUMBER
            InputType.TYPE_CLASS_DATETIME -> Field.DATETIME
            InputType.TYPE_CLASS_TEXT -> when {
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> Field.EMAIL
                variation == InputType.TYPE_TEXT_VARIATION_URI -> Field.URL
                inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0 &&
                    inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 -> Field.CODE
                hint != null && codeHint.containsMatchIn(hint) -> Field.CODE
                else -> Field.TEXT
            }
            else -> Field.TEXT
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
