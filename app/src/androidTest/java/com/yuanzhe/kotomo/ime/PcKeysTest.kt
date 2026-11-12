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
import androidx.test.uiautomator.Until
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import com.yuanzhe.kotomo.settings.SettingsStore
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T20 (PLAN §10.11): the PC layout sends key events with the right codes and meta states. A
 * listener on the test field records what arrives from Kotomo's keys.
 *
 * Notes: the field's app is fixed to the PC layout through Kotomo's memory, as the quick panel's
 * "Fix this mode here" does, with the layout turned on in the settings; both are undone after.
 */
@RunWith(AndroidJUnit4::class)
class PcKeysTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val store = SettingsStore.get(context)
    private val memory = KeyboardMemory.get(context)

    @Before
    fun usePcLayout() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        runBlocking { store.update { it.copy(pcEnabled = true) } }
        memory.update { m -> m.withApp(context.packageName) { it.copy(pinned = Mode.PC) } }
        Thread.sleep(SETTLE_MS)
    }

    @After
    fun back() {
        memory.update { m -> m.withApp(context.packageName) { it.copy(pinned = null) } }
        runBlocking { store.update { it.copy(pcEnabled = false) } }
    }

    /** Positive: latched Ctrl then c is Ctrl down, C with META_CTRL_ON, Ctrl up; the latch is then gone. */
    @Test
    fun latchedCtrlWrapsTheNextKeyOnly() {
        val events = typed(listOf("Ctrl", "c", "q"))
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        assertEquals(
            listOf(
                Triple(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrl),
                Triple(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, ctrl),
                Triple(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, ctrl),
                Triple(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0),
                // Negative: q after the latch carries no modifier.
                Triple(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Q, 0),
                Triple(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Q, 0),
            ),
            events,
        )
    }

    /**
     * Positive: an arrow from the strip and a function key from the Fn page. (Not Esc: the test
     * activity takes it as Back and closes.)
     */
    @Test
    fun fnPageAndStripSendTheirCodes() {
        val events = typed(listOf("←", "Fn", "F5"))
        assertEquals(
            listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F5),
            events.map { it.second },
        )
    }

    /** Opens the test field, taps the keys described as [keys] and returns the key events it received. */
    private fun typed(keys: List<String>): List<Triple<Int, Int, Int>> {
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        val events = Collections.synchronizedList(mutableListOf<Triple<Int, Int, Int>>())
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            checkNotNull(device.wait(Until.findObject(By.desc("Esc")), WAIT_MS)) { "the PC layout did not appear" }
            scenario.onActivity { a ->
                a.findViewById<EditText>(FieldTestActivity.FIELD_ID).setOnKeyListener { _, _, e ->
                    events += Triple(e.action, e.keyCode, e.metaState)
                    false
                }
            }
            for (k in keys) {
                checkNotNull(device.wait(Until.findObject(By.desc(k)), WAIT_MS)) { "no key $k" }.click()
                Thread.sleep(KEY_MS)
            }
            Thread.sleep(SETTLE_MS)
        }
        return events.toList()
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        const val WAIT_MS = 5000L
        const val KEY_MS = 300L
        const val SETTLE_MS = 1000L
    }
}
