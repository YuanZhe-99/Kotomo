package com.yuanzhe.kotomo.ime

import android.view.inputmethod.InputConnection

/**
 * Decides smart punctuation (PLAN §10.8): curly quotes for the ASCII quote keys and closing
 * brackets typed together with their opening ones, as pure functions so both are unit-tested.
 *
 * Notes: each has its own switch (`punctuation.smart_quotes`, `punctuation.auto_pair`, off by
 * default); neither applies in code, URL, e-mail or direct-input fields, where an ASCII quote or a
 * lone bracket is what the person means (PLAN §10.10's code mode).
 */
internal object Punctuation {
    /** Opening marks and the closing mark auto-pairing adds after each. */
    val PAIRS = mapOf(
        "(" to ")", "[" to "]", "{" to "}", "（" to "）", "【" to "】", "〔" to "〕", "《" to "》", "〈" to "〉",
        "「" to "」", "『" to "』", "“" to "”", "‘" to "’",
    )

    /**
     * Turns an ASCII quote into a curly one from the character before the cursor.
     * Inputs: [typed] the key's text; [before] the character before the cursor, null at the start.
     * Returns: “ or ‘ at the start, after a space or an opening mark; ” after anything else; ’ after
     * a letter or digit too (the apostrophe in "don't"); any other text unchanged.
     */
    fun curl(typed: String, before: Char?): String {
        val opens = before == null || before.isWhitespace() || before.toString() in PAIRS || before in "—–-/"
        return when (typed) {
            "\"" -> if (opens) "“" else "”"
            "'" -> if (opens) "‘" else "’"
            else -> typed
        }
    }

    /**
     * Decides whether an opening mark gets its closing one.
     * Inputs: [after] the character after the cursor, null at the end.
     * Returns: the closing mark, or null when [text] opens nothing or the cursor sits right before
     * a word (pairing there would wrap nothing and leave a stray closing mark).
     */
    fun closer(text: String, after: Char?): String? {
        val close = PAIRS[text] ?: return null
        return if (after == null || after.isWhitespace() || !after.isLetterOrDigit()) close else null
    }
}

/**
 * Applies smart punctuation to one keyboard's field: remembers the closing marks it added, so
 * typing that mark steps over it and backspace right after pairing deletes both.
 *
 * Notes: main thread. [reset] when the field, the cursor or the mode changes outside typing; the
 * remembered marks only ever let the keyboard step over or delete a mark that is really there.
 */
internal class SmartPunctuation {
    /** Closing marks added by pairing, innermost last. */
    private val added = ArrayDeque<String>()

    /** Forgets the added closing marks (new field, cursor moved, Enter, mode switch). */
    fun reset() = added.clear()

    /**
     * Commits [typed] (a key's text outside a composition) through the switches that are on.
     * Inputs: [quotes] and [pair] the two switches, already off for fields that take neither.
     * Side effects: commits text to [ic] or moves its cursor over a mark pairing added.
     */
    fun commit(ic: InputConnection, typed: String, quotes: Boolean, pair: Boolean) {
        if (!quotes && !pair) {
            ic.commitText(typed, 1)
            return
        }
        val before = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
        val after = ic.getTextAfterCursor(1, 0)?.firstOrNull()
        val top = added.lastOrNull()
        // Typing the mark pairing added (or the quote key that would type it) steps over it.
        if (top != null && after?.toString() == top && (typed == top || quotes && Punctuation.curl(typed, 'x') == top)) {
            added.removeLast()
            // Retyping the mark in place puts the cursor after it in every editor.
            ic.beginBatchEdit()
            ic.deleteSurroundingText(0, top.length)
            ic.commitText(top, 1)
            ic.endBatchEdit()
            return
        }
        val text = if (quotes) Punctuation.curl(typed, before) else typed
        val close = if (pair) Punctuation.closer(text, after) else null
        if (close == null) {
            ic.commitText(text, 1)
        } else {
            ic.beginBatchEdit()
            ic.commitText(text, 1)
            // A cursor position of 0 leaves the cursor before the closing mark.
            ic.commitText(close, 0)
            ic.endBatchEdit()
            added.addLast(close)
        }
    }

    /**
     * Deletes an empty pair the keyboard just added, when the cursor sits between its marks.
     * Returns: true when it deleted the pair; false leaves backspace to the normal path.
     */
    fun backspace(ic: InputConnection): Boolean {
        val top = added.lastOrNull() ?: return false
        val before = ic.getTextBeforeCursor(1, 0)?.toString()
        val after = ic.getTextAfterCursor(top.length, 0)?.toString()
        if (after != top || before == null || Punctuation.PAIRS[before] != top) return false
        added.removeLast()
        ic.deleteSurroundingText(before.length, top.length)
        return true
    }
}
