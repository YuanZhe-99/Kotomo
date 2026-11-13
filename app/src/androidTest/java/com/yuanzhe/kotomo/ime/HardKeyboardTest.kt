package com.yuanzhe.kotomo.ime

import android.content.Intent
import android.text.InputType
import android.view.KeyEvent
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import com.yuanzhe.kotomo.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T21 (PLAN §12): a physical keyboard types through Kotomo. The emulator has a hardware keyboard;
 * with the on-screen keys turned off for it, only the candidate bar shows, letters compose, number
 * keys pick candidates, Esc drops the composition, and English mode and password fields get the
 * keys unchanged. With the 9-key layout chosen, hidden keys leave librime on the letter schema.
 *
 * Notes: key events are injected like a hardware keyboard's (through the input system, so the
 * input method sees them before the app). The first field of a run may come up before librime has
 * opened its data; the typing is then repeated.
 */
@RunWith(AndroidJUnit4::class)
class HardKeyboardTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext

    @Before
    fun hardKeyboardOnly() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 0")
    }

    @After
    fun onScreenKeysBack() {
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = null) } }
    }

    /** Positive: pinyin from physical keys converts; space commits the first candidate; no on-screen keys. */
    @Test
    fun pinyinFromPhysicalKeys() {
        val (text, keys) = typed(Mode.CHINESE, "nihao", listOf(KeyEvent.KEYCODE_SPACE), expected = "你好")
        assertEquals("你好", text)
        assertNull("on-screen keys showed next to the physical keyboard", keys)
    }

    /** Positive: with the Chinese 9-key layout chosen, physical letters still type pinyin (PLAN §10.9). */
    @Test
    fun pinyinFromPhysicalKeysWithNineKeyChosen() {
        val store = SettingsStore.get(context)
        val before = store.current
        runBlocking { store.update { it.copy(chineseNineKey = true) } }
        try {
            val (text, _) = typed(Mode.CHINESE, "nihao", listOf(KeyEvent.KEYCODE_SPACE), expected = "你好")
            assertEquals("你好", text)
        } finally {
            runBlocking { store.update { before } }
        }
    }

    /** Positive: a number key picks that candidate; Esc then drops a new composition. */
    @Test
    fun numberPicksAndEscapeCancels() {
        val (text, _) = typed(Mode.CHINESE, "nihao", listOf(KeyEvent.KEYCODE_1), expected = "你好", then = "shi" to KeyEvent.KEYCODE_ESCAPE)
        assertEquals("你好", text)
    }

    /** Positive: romaji compose in Japanese mode; Enter keeps the kana. */
    @Test
    fun romajiFromPhysicalKeys() {
        val (text, _) = typed(Mode.JAPANESE, "sakura", listOf(KeyEvent.KEYCODE_ENTER), expected = "さくら")
        assertEquals("さくら", text)
    }

    /** Negative: English mode leaves the keys to the field. */
    @Test
    fun englishModePassesKeys() {
        val (text, _) = typed(Mode.ENGLISH, "nihao", emptyList(), expected = "nihao")
        assertEquals("nihao", text)
    }

    /** Negative: a password field gets the letters as typed, even in Chinese mode. */
    @Test
    fun passwordFieldPassesKeys() {
        val (text, _) = typed(
            Mode.CHINESE, "nihao", listOf(KeyEvent.KEYCODE_SPACE), expected = "nihao ",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        assertEquals("nihao ", text)
    }

    /** Positive: Shift+Space moves from Chinese to the next mode, whose letters then pass (English). */
    @Test
    fun shiftSpaceSwitchesMode() {
        val settings = SettingsStore.get(context).current
        val modes = settings.modes
        val next = modes[(modes.indexOf(Mode.CHINESE) + 1) % modes.size]
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = Mode.CHINESE) } }
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        ActivityScenario.launch<FieldTestActivity>(intent).use {
            Thread.sleep(SETTLE_MS)
            instrumentation.sendKeySync(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
            instrumentation.sendKeySync(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
            Thread.sleep(SETTLE_MS)
        }
        // The pinned mode wins at the next field, so the switch is read from the keyboard's memory
        // of the last mode used.
        assertEquals(next, KeyboardMemory.get(context).current.lastMode)
    }

    /**
     * Opens a field in [mode], presses [letters] then [keys] as a physical keyboard would and
     * returns the field's text and the on-screen "q" key if one showed.
     */
    private fun typed(
        mode: Mode, letters: String, keys: List<Int>, expected: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT, then: Pair<String, Int>? = null,
    ): Pair<String, Any?> {
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = mode) } }
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, inputType)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            var text = ""
            var keyShown: Any? = null
            repeat(ATTEMPTS) {
                Thread.sleep(SETTLE_MS)
                device.executeShellCommand("input keyboard text $letters")
                Thread.sleep(READ_MS)
                keyShown = device.findObject(By.desc("q"))
                keys.forEach { device.pressKeyCode(it) }
                then?.let { (more, key) ->
                    device.executeShellCommand("input keyboard text $more")
                    Thread.sleep(READ_MS)
                    device.pressKeyCode(key)
                }
                Thread.sleep(READ_MS * 2)
                scenario.onActivity { text = it.findViewById<EditText>(FieldTestActivity.FIELD_ID).text.toString() }
                if (text == expected) return text to keyShown
                scenario.onActivity { it.findViewById<EditText>(FieldTestActivity.FIELD_ID).setText("") }
            }
            return text to keyShown
        }
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        const val ATTEMPTS = 3
        const val SETTLE_MS = 1500L
        const val READ_MS = 700L
    }
}
