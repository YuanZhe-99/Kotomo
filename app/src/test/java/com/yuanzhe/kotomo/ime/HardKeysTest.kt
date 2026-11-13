package com.yuanzhe.kotomo.ime

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** T21 (PLAN §12): what each key of a physical keyboard does. */
class HardKeysTest {
    private fun map(code: Int, char: Char? = null, meta: Int = 0, mode: Mode = Mode.CHINESE, direct: Boolean = false, composing: Boolean = false, candidates: Int = 0) =
        HardKeys.map(code, char?.code ?: 0, meta, mode, direct, composing, candidates)

    @Test
    fun lettersComposeInEveryComposingMode() {
        for (m in listOf(Mode.CHINESE, Mode.JAPANESE, Mode.MIXED)) {
            assertEquals(HardKey.Act(KeyAction.Text("n")), map(KeyEvent.KEYCODE_N, 'n', mode = m))
        }
        assertEquals(HardKey.Act(KeyAction.Text("N")), map(KeyEvent.KEYCODE_N, 'N', meta = KeyEvent.META_SHIFT_ON, mode = Mode.MIXED))
    }

    @Test
    fun numbersPickWhileComposing() {
        assertEquals(HardKey.Pick(0), map(KeyEvent.KEYCODE_1, '1', composing = true, candidates = 5))
        assertEquals(HardKey.Pick(2), map(KeyEvent.KEYCODE_3, '3', composing = true, candidates = 5))
        // Beyond the candidates the digit commits and is typed, as on screen.
        assertEquals(HardKey.Act(KeyAction.Text("7")), map(KeyEvent.KEYCODE_7, '7', composing = true, candidates = 5))
        // Negative: with nothing composed a digit is just a digit for the app.
        assertEquals(HardKey.Pass, map(KeyEvent.KEYCODE_1, '1'))
    }

    @Test
    fun editingKeysActOnlyOnAComposition() {
        assertEquals(HardKey.Act(KeyAction.Space), map(KeyEvent.KEYCODE_SPACE, ' ', composing = true))
        assertEquals(HardKey.Act(KeyAction.Enter), map(KeyEvent.KEYCODE_ENTER, composing = true))
        assertEquals(HardKey.Act(KeyAction.Backspace), map(KeyEvent.KEYCODE_DEL, composing = true))
        assertEquals(HardKey.Cancel, map(KeyEvent.KEYCODE_ESCAPE, composing = true))
        assertEquals(HardKey.Next, map(KeyEvent.KEYCODE_DPAD_DOWN, composing = true, candidates = 3))
        for (code in listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_DOWN)) {
            assertEquals(HardKey.Pass, map(code, if (code == KeyEvent.KEYCODE_SPACE) ' ' else null))
        }
    }

    @Test
    fun punctuationTakesTheModesForm() {
        assertEquals(HardKey.Act(KeyAction.Text(",")), map(KeyEvent.KEYCODE_COMMA, ','))
        assertEquals(HardKey.Act(KeyAction.Text("'")), map(KeyEvent.KEYCODE_APOSTROPHE, '\'', mode = Mode.MIXED, composing = true))
    }

    @Test
    fun shiftSpaceSwitchesModeEverywhere() {
        for (m in Mode.entries) {
            assertEquals(HardKey.NextMode, map(KeyEvent.KEYCODE_SPACE, ' ', meta = KeyEvent.META_SHIFT_ON, mode = m))
        }
        assertEquals(HardKey.NextMode, map(KeyEvent.KEYCODE_SPACE, ' ', meta = KeyEvent.META_SHIFT_ON, direct = true))
    }

    /** Negative: shortcuts, English and PC modes and direct fields reach the app unchanged. */
    @Test
    fun shortcutsAndDirectFieldsPass() {
        assertEquals(HardKey.Pass, map(KeyEvent.KEYCODE_C, 'c', meta = KeyEvent.META_CTRL_ON))
        assertEquals(HardKey.Pass, map(KeyEvent.KEYCODE_SPACE, ' ', meta = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
        assertEquals(HardKey.Pass, map(KeyEvent.KEYCODE_N, 'n', mode = Mode.ENGLISH))
        assertEquals(HardKey.Pass, map(KeyEvent.KEYCODE_N, 'n', mode = Mode.PC))
        assertEquals(HardKey.Pass, map(KeyEvent.KEYCODE_N, 'n', direct = true))
        assertEquals(HardKey.Pass, map(KeyEvent.KEYCODE_F5))
    }
}
