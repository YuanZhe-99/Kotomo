package com.yuanzhe.kotomo.ime

import android.view.KeyEvent
import com.yuanzhe.kotomo.settings.PcHost
import com.yuanzhe.kotomo.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T20 (PLAN §10.11): the PC layout's modifier state machine, its keys' codes and meta states,
 * the shortcuts per host and the layouts' shape. Positive and negative cases for each.
 */
class PcTest {
    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

    @Test
    fun tapLatchesForOneKeyOnly() {
        val m = PcModifiers()
        m.tap(PcModifier.CTRL, 0)
        assertEquals(PcModifiers.State.LATCHED, m.state(PcModifier.CTRL))
        assertEquals(
            listOf(
                PcStroke(KeyEvent.KEYCODE_CTRL_LEFT, true, ctrl),
                PcStroke(KeyEvent.KEYCODE_C, true, ctrl),
                PcStroke(KeyEvent.KEYCODE_C, false, ctrl),
                PcStroke(KeyEvent.KEYCODE_CTRL_LEFT, false, 0),
            ),
            m.strokes(KeyEvent.KEYCODE_C),
        )
        // Negative: the latch is gone, the next key goes alone.
        assertNull(m.state(PcModifier.CTRL))
        assertEquals(listOf(PcStroke(KeyEvent.KEYCODE_V, true, 0), PcStroke(KeyEvent.KEYCODE_V, false, 0)), m.strokes(KeyEvent.KEYCODE_V))
    }

    @Test
    fun doubleTapLocksUntilTappedAgain() {
        val m = PcModifiers()
        m.tap(PcModifier.ALT, 0)
        m.tap(PcModifier.ALT, 100)
        assertEquals(PcModifiers.State.LOCKED, m.state(PcModifier.ALT))
        m.strokes(KeyEvent.KEYCODE_TAB)
        m.strokes(KeyEvent.KEYCODE_TAB)
        assertEquals(PcModifiers.State.LOCKED, m.state(PcModifier.ALT))
        m.tap(PcModifier.ALT, 5000)
        assertNull(m.state(PcModifier.ALT))
    }

    @Test
    fun slowSecondTapTurnsTheLatchOff() {
        val m = PcModifiers()
        m.tap(PcModifier.META, 0)
        m.tap(PcModifier.META, PcModifiers.DOUBLE_TAP_MS + 1)
        assertNull(m.state(PcModifier.META))
    }

    @Test
    fun holdAppliesWhileHeld() {
        val m = PcModifiers()
        m.hold(PcModifier.SHIFT)
        m.strokes(KeyEvent.KEYCODE_A)
        assertEquals(PcModifiers.State.HELD, m.state(PcModifier.SHIFT))
        m.release(PcModifier.SHIFT)
        assertNull(m.state(PcModifier.SHIFT))
        // Negative: releasing a latched modifier's finger does not clear the latch.
        m.tap(PcModifier.CTRL, 0)
        m.release(PcModifier.CTRL)
        assertEquals(PcModifiers.State.LATCHED, m.state(PcModifier.CTRL))
    }

    @Test
    fun shortcutModifiersPressInPcOrderAndReleaseInReverse() {
        val strokes = PcModifiers().strokes(KeyEvent.KEYCODE_FORWARD_DEL, setOf(PcModifier.ALT, PcModifier.CTRL))
        assertEquals(
            listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT),
            strokes.map { it.code },
        )
        val both = ctrl or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        assertEquals(both, strokes[2].meta)
        assertEquals(0, strokes.last().meta)
    }

    @Test
    fun charactersMapToUsKeys() {
        assertEquals(KeyEvent.KEYCODE_Q to false, PcKeys.of('q'))
        assertEquals(KeyEvent.KEYCODE_Q to true, PcKeys.of('Q'))
        assertEquals(KeyEvent.KEYCODE_2 to true, PcKeys.of('@'))
        assertEquals(KeyEvent.KEYCODE_APOSTROPHE to true, PcKeys.of('"'))
        assertEquals(KeyEvent.KEYCODE_SLASH to false, PcKeys.of('/'))
        // Negative: characters a US keyboard does not have are typed as text.
        assertNull(PcKeys.of('€'))
        assertNull(PcKeys.of('中'))
    }

    @Test
    fun hostChangesNamesAndShortcutsNotCodes() {
        val mac = PcKeys.shortcuts(PcHost.MACOS).first()
        val win = PcKeys.shortcuts(PcHost.WINDOWS).first()
        assertEquals(setOf(PcModifier.META), mac.third)
        assertEquals(setOf(PcModifier.CTRL), win.third)
        assertEquals(mac.second, win.second)
        assertEquals(KeyEvent.KEYCODE_ESCAPE, PcKeys.secure(PcHost.MACOS).second)
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, PcKeys.secure(PcHost.LINUX).second)
        assertEquals(listOf("Ctrl", "Super", "Alt"), PcKeys.modifiers(PcHost.LINUX).map { it.first })
        assertTrue(PcHost.entries.all { h -> PcKeys.modifiers(h).none { it.second == PcModifier.SHIFT } })
    }

    @Test
    fun everyTypedKeyOfEveryLayoutHasACode() {
        val layouts = PcHost.entries.flatMap { host ->
            (0..PcLayout.FN).map { PcLayout.phone(it, host) } + PcLayout.full(host)
        }
        for (layout in layouts) {
            for (key in layout.rows.flatMap { it.keys }) {
                val text = (key.action as? KeyAction.Text)?.text ?: continue
                assertTrue("no code for '$text'", text.all { PcKeys.of(it) != null })
            }
        }
    }

    @Test
    fun layoutsHaveTheDesignedShape() {
        val letters = PcLayout.phone(0, PcHost.WINDOWS)
        assertEquals(listOf("Esc", "Tab", "Ctrl", "Win", "Alt", "←", "↑", "↓", "→", "Fn"), letters.rows.first().keys.map { it.label })
        assertEquals(5, letters.rows.size)
        // The function strip and the five PC rows.
        assertEquals(6, PcLayout.full(PcHost.WINDOWS).rows.size)
        // Split: every row has two halves; the phone layout has none.
        assertTrue(PcLayout.phone(0, PcHost.WINDOWS, split = true).rows.all { it.splitAt > 0 })
        assertFalse(letters.rows.any { it.splitAt > 0 })
        val fn = PcLayout.phone(PcLayout.FN, PcHost.MACOS).rows.flatMap { it.keys }.map { it.label }
        assertTrue("F12" in fn && "⌥⌘Esc" in fn && "Ctrl+Alt+Del" !in fn)
    }

    @Test
    fun pcModeIsHiddenUntilTurnedOn() {
        assertFalse(Mode.PC in Settings().modes)
        assertTrue(Mode.PC in Settings(pcEnabled = true).modes)
        assertFalse(Mode.PC in Settings(pcEnabled = true, hiddenModes = setOf(Mode.PC)).modes)
        // Hiding every other mode never leaves only PC.
        assertFalse(Mode.PC in Settings(hiddenModes = Mode.entries.toSet()).modes)
    }

    /** Both PC phone symbol pages cover all 32 printable ASCII symbols (with `/` on page 1 and `\` on page 2). */
    @Test
    fun pcLayoutsCoverAllPrintableAsciiSymbols() {
        val printableAsciiSymbols = (0x21..0x7E).map { it.toChar() }.filter { !it.isLetterOrDigit() }
        assertEquals(32, printableAsciiSymbols.size)
        val p1 = PcLayout.phone(1, PcHost.WINDOWS).rows.flatMap { it.keys }.mapNotNull { (it.action as? KeyAction.Text)?.text }
        val p2 = PcLayout.phone(2, PcHost.WINDOWS).rows.flatMap { it.keys }.mapNotNull { (it.action as? KeyAction.Text)?.text }
        assertTrue("PC symbol page 1 missing /", "/" in p1)
        assertTrue("PC symbol page 2 missing \\", "\\" in p2)
        val combined = (p1 + p2).toSet()
        for (c in printableAsciiSymbols) {
            assertTrue("PC symbol pages missing $c", c.toString() in combined)
            assertTrue("PcKeys.of missing $c", PcKeys.of(c) != null)
        }
    }
}
