package com.yuanzhe.kotomo.ime

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.yuanzhe.kotomo.settings.PcHost

/**
 * Names a PC modifier: its key code and the meta bits it sets on the keys sent while it is on.
 *
 * Notes: Win, Cmd and Super are all [META] (KEYCODE_META_LEFT); the host system only changes the
 * name shown on the key (PLAN §10.11).
 */
enum class PcModifier(val code: Int, val meta: Int) {
    CTRL(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
    ALT(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
    META(KeyEvent.KEYCODE_META_LEFT, KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON),
    SHIFT(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
}

/** One key event to send: a key code pressed ([down]) or released, with [meta] state. */
data class PcStroke(val code: Int, val down: Boolean, val meta: Int)

/**
 * Holds the PC layout's modifier states (PLAN §10.11): a tap latches a modifier for the next key,
 * a second tap within [DOUBLE_TAP_MS] locks it, a tap on a locked one releases it; a modifier held
 * down applies while it is held.
 *
 * Notes: main thread; pure state, unit-tested. A latch is released once the next non-modifier key
 * was sent; a lock or a hold is not.
 */
class PcModifiers {
    /** How a modifier is on. */
    enum class State { LATCHED, LOCKED, HELD }

    private val states = mutableMapOf<PcModifier, State>()
    private val lastTap = mutableMapOf<PcModifier, Long>()

    /** Returns how [m] is on, or null when it is off. */
    fun state(m: PcModifier): State? = states[m]

    /** The modifiers on, in the order PC keyboards press them (Ctrl, Alt, Meta, Shift). */
    val active: List<PcModifier> get() = PcModifier.entries.filter { it in states }

    /**
     * Applies a tap on [m] at [at] (uptime milliseconds): off → latched; latched, tapped again
     * quickly → locked; latched otherwise, locked or held → off.
     */
    fun tap(m: PcModifier, at: Long) {
        val quick = lastTap[m]?.let { at - it < DOUBLE_TAP_MS } == true
        val next = when (states[m]) {
            null -> State.LATCHED
            State.LATCHED -> if (quick) State.LOCKED else null
            State.LOCKED, State.HELD -> null
        }
        if (next == null) {
            states.remove(m)
            lastTap.remove(m)
        } else {
            states[m] = next
            lastTap[m] = at
        }
    }

    /** Applies [m] being pressed and held (released by [release]). */
    fun hold(m: PcModifier) {
        states[m] = State.HELD
    }

    /** Releases a held [m]; a latched or locked one stays. */
    fun release(m: PcModifier) {
        if (states[m] == State.HELD) states.remove(m)
    }

    /** Releases the latched modifiers, as sending a key does. */
    fun releaseLatched() {
        states.entries.removeAll { it.value == State.LATCHED }
    }

    /** Clears every modifier (leaving the field or the layout). */
    fun clear() {
        states.clear()
        lastTap.clear()
    }

    /**
     * Turns one key into the strokes to send with the modifiers that are on, then releases the
     * latched ones.
     * Inputs: [code] the key code; [extra] modifiers the key itself needs (Shift for `@`, or a
     * shortcut's own modifiers).
     * Returns: modifier downs, the key's down and up with the combined meta state, modifier ups
     * in reverse order.
     */
    fun strokes(code: Int, extra: Set<PcModifier> = emptySet()): List<PcStroke> {
        val mods = PcModifier.entries.filter { it in states || it in extra }
        releaseLatched()
        return press(code, mods)
    }

    companion object {
        /** Two taps on a modifier closer than this lock it. */
        const val DOUBLE_TAP_MS = 400L

        /** Spells out [mods] pressed in order, [code] tapped, and [mods] released in reverse. */
        fun press(code: Int, mods: List<PcModifier>): List<PcStroke> {
            val out = mutableListOf<PcStroke>()
            var meta = 0
            for (m in mods) {
                meta = meta or m.meta
                out += PcStroke(m.code, true, meta)
            }
            out += PcStroke(code, true, meta)
            out += PcStroke(code, false, meta)
            for (m in mods.reversed()) {
                meta = meta and m.meta.inv()
                out += PcStroke(m.code, false, meta)
            }
            return out
        }
    }
}

/**
 * Maps the PC layout's printable keys to key codes on a US PC keyboard (PLAN §10.11), so that
 * letters, digits and symbols are sent as key events rather than text and combinations like
 * Ctrl+C work.
 */
object PcKeys {
    private val plain = buildMap {
        ('a'..'z').forEachIndexed { i, c -> put(c, KeyEvent.KEYCODE_A + i) }
        ('0'..'9').forEachIndexed { i, c -> put(c, KeyEvent.KEYCODE_0 + i) }
        put(' ', KeyEvent.KEYCODE_SPACE); put('-', KeyEvent.KEYCODE_MINUS); put('=', KeyEvent.KEYCODE_EQUALS)
        put('[', KeyEvent.KEYCODE_LEFT_BRACKET); put(']', KeyEvent.KEYCODE_RIGHT_BRACKET); put('\\', KeyEvent.KEYCODE_BACKSLASH)
        put(';', KeyEvent.KEYCODE_SEMICOLON); put('\'', KeyEvent.KEYCODE_APOSTROPHE); put(',', KeyEvent.KEYCODE_COMMA)
        put('.', KeyEvent.KEYCODE_PERIOD); put('/', KeyEvent.KEYCODE_SLASH); put('`', KeyEvent.KEYCODE_GRAVE)
    }

    /** The shifted characters and the unshifted key they are on. */
    private const val SHIFTED = "!@#$%^&*()_+{}|:\"<>?~"
    private const val BASES = "1234567890-=[]\\;',./`"

    /**
     * Returns the key code of [c] and whether Shift is needed, or null for a character a US PC
     * keyboard does not have.
     */
    fun of(c: Char): Pair<Int, Boolean>? {
        plain[c]?.let { return it to false }
        if (c in 'A'..'Z') return plain.getValue(c.lowercaseChar()) to true
        val i = SHIFTED.indexOf(c)
        return if (i < 0) null else plain.getValue(BASES[i]) to true
    }

    /**
     * Lists the Fn page's shortcuts for [host]: label and the key with its modifiers.
     * Notes: the codes are the same on every host; only the shortcuts people use differ.
     */
    fun shortcuts(host: PcHost): List<Triple<String, Int, Set<PcModifier>>> {
        val primary = if (host == PcHost.MACOS) PcModifier.META else PcModifier.CTRL
        val p = if (host == PcHost.MACOS) "⌘" else "Ctrl+"
        return listOf(
            Triple("${p}C", KeyEvent.KEYCODE_C, setOf(primary)),
            Triple("${p}V", KeyEvent.KEYCODE_V, setOf(primary)),
            Triple("${p}X", KeyEvent.KEYCODE_X, setOf(primary)),
            Triple("${p}Z", KeyEvent.KEYCODE_Z, setOf(primary)),
            Triple("${p}A", KeyEvent.KEYCODE_A, setOf(primary)),
            when (host) {
                PcHost.WINDOWS -> Triple("Alt+Tab", KeyEvent.KEYCODE_TAB, setOf(PcModifier.ALT))
                PcHost.MACOS -> Triple("⌘Tab", KeyEvent.KEYCODE_TAB, setOf(PcModifier.META))
                PcHost.LINUX -> Triple("Alt+Tab", KeyEvent.KEYCODE_TAB, setOf(PcModifier.ALT))
            },
            when (host) {
                PcHost.WINDOWS -> Triple("Win+D", KeyEvent.KEYCODE_D, setOf(PcModifier.META))
                PcHost.MACOS -> Triple("⌘Space", KeyEvent.KEYCODE_SPACE, setOf(PcModifier.META))
                PcHost.LINUX -> Triple("Super", KeyEvent.KEYCODE_META_LEFT, emptySet())
            },
        )
    }

    /** The host's security combination: Ctrl+Alt+Del, or ⌥⌘Esc (Force Quit) on macOS. */
    fun secure(host: PcHost): Triple<String, Int, Set<PcModifier>> = if (host == PcHost.MACOS) {
        Triple("⌥⌘Esc", KeyEvent.KEYCODE_ESCAPE, setOf(PcModifier.ALT, PcModifier.META))
    } else {
        Triple("Ctrl+Alt+Del", KeyEvent.KEYCODE_FORWARD_DEL, setOf(PcModifier.CTRL, PcModifier.ALT))
    }

    /** The modifier keys' names and order on [host] (Ctrl·Win·Alt, ⌃·⌥·⌘, Ctrl·Super·Alt). */
    fun modifiers(host: PcHost): List<Pair<String, PcModifier>> = when (host) {
        PcHost.WINDOWS -> listOf("Ctrl" to PcModifier.CTRL, "Win" to PcModifier.META, "Alt" to PcModifier.ALT)
        PcHost.MACOS -> listOf("⌃Ctrl" to PcModifier.CTRL, "⌥Opt" to PcModifier.ALT, "⌘Cmd" to PcModifier.META)
        PcHost.LINUX -> listOf("Ctrl" to PcModifier.CTRL, "Super" to PcModifier.META, "Alt" to PcModifier.ALT)
    }
}

/** The PC layout's own keys (PLAN §10.11); letters, digits, symbols, space, Enter and ⌫ reuse [KeyAction]. */
sealed interface PcKey {
    /** Sends [code] with the modifiers that are on plus [mods] (function keys, arrows, shortcuts). */
    data class Code(val code: Int, val mods: Set<PcModifier> = emptySet()) : PcKey
    /** A modifier key: tap to latch, tap twice to lock, hold to apply while held. */
    data class Modifier(val modifier: PcModifier) : PcKey
    /** A modifier pressed while another finger types: applies until [Release]. */
    data class Hold(val modifier: PcModifier) : PcKey
    /** The finger holding [modifier] lifted. */
    data class Release(val modifier: PcModifier) : PcKey
    /** Opens or leaves the Fn page (function keys, navigation, shortcuts). */
    data object Fn : PcKey
    /** Hides the keyboard. */
    data object Hide : PcKey
}

/**
 * Builds the PC layout (PLAN §10.11): on a phone a strip of Esc, Tab, the modifiers, the arrows
 * and Fn above three pages (letters, symbols, Fn); on a wide screen the whole PC keyboard, or the
 * phone pages split into two halves at the sides.
 *
 * Notes: keys are named for [host]; the codes they send are the same on every host.
 */
object PcLayout {
    private fun code(label: String, code: Int, width: Float = 1f, mods: Set<PcModifier> = emptySet()) =
        Key(label, KeyAction.Pc(PcKey.Code(code, mods)), width, function = true)

    private fun mod(label: String, m: PcModifier, width: Float = 1f) = Key(label, KeyAction.Pc(PcKey.Modifier(m)), width, function = true)

    private fun chars(s: String) = s.map { Key(it.toString(), KeyAction.Text(it.toString())) }

    private fun backspace(width: Float = 1.5f) = Key("⌫", KeyAction.Backspace, width, function = true)

    private fun enter(width: Float = 1.5f) = Key("⏎", KeyAction.Enter, width, function = true)

    private fun mods(host: PcHost) = PcKeys.modifiers(host).map { (label, m) -> mod(label, m) }

    private val arrows = listOf(
        code("←", KeyEvent.KEYCODE_DPAD_LEFT), code("↑", KeyEvent.KEYCODE_DPAD_UP),
        code("↓", KeyEvent.KEYCODE_DPAD_DOWN), code("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    )

    private fun f(n: Int) = code("F$n", KeyEvent.KEYCODE_F1 + n - 1)

    /** The phone strip: Esc, Tab, the three modifiers, the arrows and Fn. */
    private fun strip(host: PcHost, split: Boolean): Row {
        val keys = listOf(code("Esc", KeyEvent.KEYCODE_ESCAPE), code("Tab", KeyEvent.KEYCODE_TAB)) + mods(host) + arrows +
            Key("Fn", KeyAction.Pc(PcKey.Fn), function = true)
        return Row(keys, splitAt = if (split) 5 else -1)
    }

    /** The bottom row of the letters and symbols pages. */
    private fun bottom(page: KeyAction, label: String, split: Boolean) = Row(
        listOf(
            Key(label, page, 1.4f, function = true), Key(",", KeyAction.Text(",")),
            Key("⌨↓", KeyAction.Pc(PcKey.Hide), function = true), Key("", KeyAction.Space, 4.6f),
            Key(".", KeyAction.Text(".")), enter(),
        ),
        splitAt = if (split) 3 else -1,
    )

    /**
     * Builds a phone page: [page] 0 letters, 1 and 2 the symbol pages, [FN] the Fn page.
     * Inputs: [split] puts each row's halves at the two sides (the wide screen's split layout).
     */
    fun phone(page: Int, host: PcHost, split: Boolean = false): Layout {
        val at = { n: Int -> if (split) n else -1 }
        val rows = when (page) {
            0 -> listOf(
                Row(chars("qwertyuiop"), splitAt = at(5)),
                Row(chars("asdfghjkl"), lead = 0.5f, trail = 0.5f, splitAt = at(5)),
                Row(listOf(mod("⇧", PcModifier.SHIFT, 1.5f)) + chars("zxcvbnm") + backspace(), splitAt = at(5)),
                bottom(KeyAction.Symbols(0), "?123", split),
            )
            1, 2 -> {
                val (r1, r2, r3) = if (page == 1) {
                    Triple("1234567890", "@#\$_&-+()/", "*\"':;!?")
                } else {
                    Triple("~`|\\^%=<>", "[]{}", "*\"':;!?")
                }
                val other = Key(if (page == 1) "=\\<" else "?123", KeyAction.Symbols(if (page == 1) 1 else 0), 1.5f, function = true)
                listOf(
                    Row(chars(r1), splitAt = at(5)),
                    Row(chars(r2), splitAt = at(r2.length / 2)),
                    Row(listOf(other) + chars(r3) + backspace(), splitAt = at(4)),
                    bottom(KeyAction.Letters, "ABC", split),
                )
            }
            else -> {
                val nav1 = listOf(code("Ins", KeyEvent.KEYCODE_INSERT), code("Home", KeyEvent.KEYCODE_MOVE_HOME), code("PgUp", KeyEvent.KEYCODE_PAGE_UP))
                val nav2 = listOf(code("Del", KeyEvent.KEYCODE_FORWARD_DEL), code("End", KeyEvent.KEYCODE_MOVE_END), code("PgDn", KeyEvent.KEYCODE_PAGE_DOWN))
                val (secureLabel, secureCode, secureMods) = PcKeys.secure(host)
                listOf(
                    Row((1..6).map(::f) + nav1, splitAt = at(5)),
                    Row((7..12).map(::f) + nav2, splitAt = at(5)),
                    Row(PcKeys.shortcuts(host).map { (label, c, m) -> code(label, c, mods = m) }, splitAt = at(4)),
                    Row(
                        listOf(
                            Key("ABC", KeyAction.Pc(PcKey.Fn), 1.4f, function = true),
                            code("PrtSc", KeyEvent.KEYCODE_SYSRQ, 1.4f),
                            code(secureLabel, secureCode, 2.4f, secureMods),
                            code("Menu", KeyEvent.KEYCODE_MENU, 1.4f),
                            enter(),
                        ),
                        splitAt = at(2),
                    ),
                )
            }
        }
        return Layout(listOf(strip(host, split)) + rows)
    }

    /** The wide screen's whole PC keyboard: the function strip and the five standard rows. */
    fun full(host: PcHost): Layout {
        val shiftL = mod("⇧ Shift", PcModifier.SHIFT, 2.25f)
        val shiftR = mod("⇧ Shift", PcModifier.SHIFT, 1.75f)
        val m = PcKeys.modifiers(host).map { (label, x) -> mod(label, x, 1.25f) }
        // Right of the space bar, as on PC keyboards: Alt (Option) then Ctrl.
        val right = m.reversed().filter { (it.action as KeyAction.Pc).key != PcKey.Modifier(PcModifier.META) }
        return Layout(
            listOf(
                Row(
                    listOf(code("Esc", KeyEvent.KEYCODE_ESCAPE)) + (1..12).map(::f) + listOf(
                        code("PrtSc", KeyEvent.KEYCODE_SYSRQ), code("Ins", KeyEvent.KEYCODE_INSERT),
                        code("Del", KeyEvent.KEYCODE_FORWARD_DEL), code("Home", KeyEvent.KEYCODE_MOVE_HOME),
                        code("End", KeyEvent.KEYCODE_MOVE_END), code("PgUp", KeyEvent.KEYCODE_PAGE_UP),
                        code("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
                    ),
                ),
                Row(chars("`1234567890-=") + backspace(2f)),
                Row(listOf(code("Tab", KeyEvent.KEYCODE_TAB, 1.5f)) + chars("qwertyuiop[]\\")),
                Row(listOf(code("Caps", KeyEvent.KEYCODE_CAPS_LOCK, 1.75f)) + chars("asdfghjkl;'") + enter(2.25f)),
                Row(listOf(shiftL) + chars("zxcvbnm,./") + shiftR + arrows[1]),
                Row(m + Key("", KeyAction.Space, 5.5f) + right + arrows[0] + arrows[2] + arrows[3]),
            ),
        )
    }

    /** The page number of the Fn page in [phone]. */
    const val FN = 3
}

/**
 * Runs the PC layout (PLAN §10.11): turns its keys into key events sent through the field's
 * InputConnection, keeps the modifiers and the page, and converts, learns and records nothing.
 *
 * Notes: main thread; nothing here waits for I/O. Characters a US PC keyboard does not have are
 * committed as text. Whether the app passes the events on to a remote computer is up to the app:
 * an input method can send Android key events only (no scan codes, no pointer).
 */
class PcInput(private val hide: () -> Unit) {
    val modifiers = PcModifiers()
    /** The phone page shown: 0 letters, 1 and 2 symbols, [PcLayout.FN] the Fn page. */
    var page = 0
        private set

    /** Starts over: letters page, no modifier on (a new field, or leaving the PC layout). */
    fun reset() {
        page = 0
        modifiers.clear()
    }

    /**
     * Handles [action] typed at [at] (uptime) while the PC layout is shown.
     * Returns: false for the actions the service handles itself (mode switch, one-handed mode).
     * Side effects: sends key events or text to [ic]; may hide the keyboard.
     */
    fun onKey(action: KeyAction, ic: InputConnection, at: Long): Boolean {
        when (action) {
            is KeyAction.Text -> action.text.forEach { c ->
                val key = PcKeys.of(c)
                if (key == null) {
                    // Text carries no modifier: latched ones are released as after any key.
                    modifiers.releaseLatched()
                    ic.commitText(c.toString(), 1)
                } else {
                    send(ic, modifiers.strokes(key.first, if (key.second) setOf(PcModifier.SHIFT) else emptySet()))
                }
            }
            KeyAction.Space -> send(ic, modifiers.strokes(KeyEvent.KEYCODE_SPACE))
            KeyAction.Enter -> send(ic, modifiers.strokes(KeyEvent.KEYCODE_ENTER))
            KeyAction.Backspace -> send(ic, modifiers.strokes(KeyEvent.KEYCODE_DEL))
            is KeyAction.MoveCursor -> {
                val code = if (action.steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                repeat(kotlin.math.abs(action.steps)) { send(ic, modifiers.strokes(code)) }
            }
            is KeyAction.DeleteWords -> repeat(action.count) { send(ic, modifiers.strokes(KeyEvent.KEYCODE_DEL, setOf(PcModifier.CTRL))) }
            is KeyAction.Symbols -> page = 1 + action.page
            KeyAction.Letters -> page = 0
            is KeyAction.Pc -> when (val k = action.key) {
                is PcKey.Code -> send(ic, modifiers.strokes(k.code, k.mods))
                is PcKey.Modifier -> modifiers.tap(k.modifier, at)
                is PcKey.Hold -> modifiers.hold(k.modifier)
                is PcKey.Release -> modifiers.release(k.modifier)
                PcKey.Fn -> page = if (page == PcLayout.FN) 0 else PcLayout.FN
                PcKey.Hide -> hide()
            }
            KeyAction.Shift -> modifiers.tap(PcModifier.SHIFT, at)
            KeyAction.Emoji, is KeyAction.SetMode, is KeyAction.SetOneHanded, is KeyAction.SetOneHandedWidth -> return false
        }
        return true
    }

    /** Sends [strokes] as soft-keyboard key events. */
    private fun send(ic: InputConnection, strokes: List<PcStroke>) {
        val now = SystemClock.uptimeMillis()
        for (s in strokes) {
            val action = if (s.down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            ic.sendKeyEvent(
                KeyEvent(now, now, action, s.code, 0, s.meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE),
            )
        }
    }
}
