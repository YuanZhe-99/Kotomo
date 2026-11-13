package com.yuanzhe.kotomo.ime

import android.view.KeyEvent

/** What one key of a physical keyboard does in Kotomo (PLAN §12). */
sealed interface HardKey {
    /** The key acts as the on-screen key [action] would. */
    data class Act(val action: KeyAction) : HardKey

    /** Commits the candidate at [index] (number keys while composing). */
    data class Pick(val index: Int) : HardKey

    /** Moves the selection to the next candidate. */
    data object Next : HardKey

    /** Drops the composition without committing anything. */
    data object Cancel : HardKey

    /** Switches to the next input mode in the person's order. */
    data object NextMode : HardKey

    /** Kotomo leaves the key alone: the app (or the system) gets it unchanged. */
    data object Pass : HardKey
}

/**
 * Maps the keys of a physical keyboard onto Kotomo's own key handling (PLAN §12), so that letters
 * compose pinyin and romaji like the on-screen keys and candidates can be chosen without touching
 * the screen.
 * Notes: pure (unit-tested); the service calls it on the main thread for every key-down. Keys with
 * Ctrl, Alt or Meta always pass, so shortcuts keep working; English and PC modes and direct fields
 * (passwords, addresses) pass every key but Shift+Space.
 */
object HardKeys {
    /**
     * Decides what the key [keyCode] does.
     * Inputs: [char] is the character the key types with its meta state (0 for none), [composing]
     * whether a composition is open, [candidates] how many candidates the bar shows.
     * Returns: the action; [HardKey.Pass] for keys Kotomo does not take.
     */
    fun map(keyCode: Int, char: Int, meta: Int, mode: Mode, direct: Boolean, composing: Boolean, candidates: Int): HardKey {
        if (meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON or KeyEvent.META_FUNCTION_ON) != 0) return HardKey.Pass
        val shift = meta and KeyEvent.META_SHIFT_ON != 0
        if (keyCode == KeyEvent.KEYCODE_SPACE && shift) return HardKey.NextMode
        if (direct || mode == Mode.ENGLISH || mode == Mode.PC) return HardKey.Pass
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> return if (composing) HardKey.Act(KeyAction.Space) else HardKey.Pass
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return if (composing) HardKey.Act(KeyAction.Enter) else HardKey.Pass
            KeyEvent.KEYCODE_DEL -> return if (composing) HardKey.Act(KeyAction.Backspace) else HardKey.Pass
            KeyEvent.KEYCODE_ESCAPE -> return if (composing) HardKey.Cancel else HardKey.Pass
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_TAB -> return if (composing && candidates > 1) HardKey.Next else HardKey.Pass
        }
        if (char <= ' '.code || char >= 0x7f) return HardKey.Pass
        val c = char.toChar()
        return when {
            c in 'a'..'z' || c in 'A'..'Z' -> HardKey.Act(KeyAction.Text(c.toString()))
            c in '1'..'9' && composing && c - '1' < candidates -> HardKey.Pick(c - '1')
            // Other digits and punctuation: as on screen, they commit the composition first and
            // type the mode's punctuation (full-width in Chinese and Japanese).
            c == '\'' && !composing -> HardKey.Pass
            c in '0'..'9' && !composing -> HardKey.Pass
            else -> HardKey.Act(KeyAction.Text(c.toString()))
        }
    }
}
