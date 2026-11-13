package com.yuanzhe.kotomo.ime

import android.content.Intent
import android.text.InputType
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import com.yuanzhe.kotomo.settings.KeyStyle
import com.yuanzhe.kotomo.settings.Settings
import com.yuanzhe.kotomo.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T14-flick, T14-float, T14-keys (PLAN §10.9): the 12-key flick layout types kana through Mozc's
 * composition, the floating keyboard is narrower and moves with its grip, and the number row
 * appears only when turned on.
 */
@RunWith(AndroidJUnit4::class)
class LayoutsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val store = SettingsStore.get(context)
    private val memory = KeyboardMemory.get(context)
    private lateinit var before: Settings

    @Before
    fun selectKotomo() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        before = store.current
    }

    @After
    fun restore() {
        runBlocking { store.update { before } }
        memory.update { m -> m.withApp(context.packageName) { it.copy(pinned = null) } }
    }

    /** Positive: か then ゛゜小 is が, a flick left on た is ち, Enter keeps the kana. */
    @Test
    fun flickTypesKana() {
        change { it.copy(japaneseFlick = true) }
        memory.update { m -> m.withApp(context.packageName) { it.copy(pinned = Mode.JAPANESE) } }
        open { scenario ->
            key("か")
            key(MODIFY)
            val ta = checkNotNull(device.wait(Until.findObject(By.desc("た")), WAIT_MS)).visibleBounds
            device.swipe(ta.centerX(), ta.centerY(), ta.centerX() - ta.width(), ta.centerY(), SWIPE_STEPS)
            Thread.sleep(KEY_MS)
            checkNotNull(device.wait(Until.findObject(By.descStartsWith(ENTER)), WAIT_MS)) { "no Enter" }.click()
            assertEquals("がち", read(scenario))
        }
    }

    /**
     * Positive (PLAN §10.9): on the Chinese 9-key layout MNO GHI GHI ABC MNO (6 4 4 2 6) and space
     * type 你好; negative: the letter layout has no MNO key.
     */
    @Test
    fun nineKeyTypesPinyin() {
        change { it.copy(chineseNineKey = true) }
        memory.update { m -> m.withApp(context.packageName) { it.copy(pinned = Mode.CHINESE) } }
        open { scenario ->
            listOf("MNO", "GHI", "GHI", "ABC", "MNO").forEach(::key)
            Thread.sleep(KEY_MS * 3)
            checkNotNull(device.wait(Until.findObject(By.descStartsWith(SPACE)), WAIT_MS)) { "no space" }.click()
            assertEquals("你好", read(scenario))
        }
        change { it.copy(chineseNineKey = false) }
        open { _ ->
            checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "the letter layout did not come back" }
            assertFalse(device.hasObject(By.desc("MNO")))
        }
    }

    /** Positive: floating, the keys are narrower than the screen and the grip moves them up. */
    @Test
    fun floatingKeyboardMovesWithItsGrip() {
        change { it.copy(floating = true) }
        open { _ ->
            val q = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)).visibleBounds
            val p = device.findObject(By.desc("p")).visibleBounds
            assertTrue("narrower than the screen", p.right - q.left < device.displayWidth * 0.85)
            val grip = checkNotNull(device.wait(Until.findObject(By.desc(GRIP)), WAIT_MS)).visibleBounds
            device.drag(grip.centerX(), grip.centerY(), grip.centerX(), grip.centerY() - DRAG_PX, SWIPE_STEPS * 2)
            Thread.sleep(KEY_MS)
            val moved = device.findObject(By.desc("q")).visibleBounds
            assertTrue("moved up: ${q.top} -> ${moved.top}", moved.top < q.top - DRAG_PX / 2)
        }
        // Negative: not floating, there is no grip.
        change { it.copy(floating = false) }
        open { _ ->
            checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS))
            assertFalse(device.hasObject(By.desc(GRIP)))
        }
    }

    /** Positive: the number row adds a 1 key to the letters; negative: without it there is none. */
    @Test
    fun numberRowOnlyWhenOn() {
        memory.update { m -> m.withApp(context.packageName) { it.copy(pinned = Mode.ENGLISH) } }
        change { it.copy(keyStyle = KeyStyle(numberRow = true)) }
        open { _ -> checkNotNull(device.wait(Until.findObject(By.desc("1")), WAIT_MS)) { "no number row" } }
        change { it.copy(keyStyle = KeyStyle()) }
        open { _ ->
            checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS))
            assertFalse(device.hasObject(By.desc("1")))
        }
    }

    private fun change(block: (Settings) -> Settings) {
        runBlocking { store.update(block) }
        Thread.sleep(KEY_MS)
    }

    private fun open(block: (ActivityScenario<FieldTestActivity>) -> Unit) {
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            Thread.sleep(KEY_MS * 3)
            block(scenario)
        }
    }

    private fun key(desc: String) {
        checkNotNull(device.wait(Until.findObject(By.desc(desc)), WAIT_MS)) { "no key $desc" }.click()
        Thread.sleep(KEY_MS)
    }

    private fun read(scenario: ActivityScenario<FieldTestActivity>): String {
        Thread.sleep(KEY_MS * 2)
        var text = ""
        scenario.onActivity { text = it.findViewById<EditText>(FieldTestActivity.FIELD_ID).text.toString() }
        return text
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        const val MODIFY = "゛゜小"
        /** The Enter key's description starts with this (R.string.key_enter, English). */
        const val ENTER = "Enter"
        /** The space key's description starts with this (R.string.key_space, English). */
        const val SPACE = "Space"
        /** The floating grip's description (R.string.float_grip, English). */
        const val GRIP = "Move the keyboard"
        const val WAIT_MS = 5000L
        const val KEY_MS = 400L
        const val SWIPE_STEPS = 10
        const val DRAG_PX = 300
    }
}
