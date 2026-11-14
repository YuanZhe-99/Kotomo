package com.yuanzhe.kotomo.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.text.InputType
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import com.yuanzhe.kotomo.ime.clipboard.ClipboardHistory
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
 * CLIP-20 on an emulator or device (PLAN §10.7): with the app being typed in excluded
 * (`clipboard.excluded_apps`), what is copied there is neither kept nor offered; with it not
 * excluded, the same kind of copy is kept.
 *
 * Notes: needs Kotomo installed as a debug build and selected as FieldTypingTest does. Runs in
 * the app's process, so it reads the same [ClipboardHistory] the keyboard writes. The test texts
 * are made up and deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardAppTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val settings = SettingsStore.get(context)
    private val history = ClipboardHistory.get(context)

    @Before
    fun selectKotomo() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
    }

    @After
    fun cleanUp() = runBlocking {
        settings.update { it.copy(clipboard = it.clipboard.copy(excludedApps = emptySet())) }
        history.items.value.filter { it.text.startsWith(PREFIX) }.forEach { history.delete(it.id) }
        history.flush()
    }

    /** Copies [text] from the field's activity and returns whether the history keeps it within the wait. */
    private fun copyAndWait(scenario: ActivityScenario<FieldTestActivity>, text: String): Boolean {
        scenario.onActivity { it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, text)) }
        val until = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < until) {
            if (history.items.value.any { it.text == text }) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    @Test
    fun anExcludedAppKeepsNothingAndOthersDo() {
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            checkNotNull(device.wait(Until.findObject(By.desc("k")), WAIT_MS)) { "Kotomo's keys did not appear" }
            runBlocking { settings.update { it.copy(clipboard = it.clipboard.copy(history = true, excludedApps = setOf(context.packageName))) } }
            // The keyboard hears of a changed setting on its own thread a moment after the write.
            Thread.sleep(POLL_MS * 5)
            assertFalse("copied while typing in an excluded app: not kept", copyAndWait(scenario, "$PREFIX excluded ${System.nanoTime()}"))
            runBlocking { settings.update { it.copy(clipboard = it.clipboard.copy(excludedApps = emptySet())) } }
            Thread.sleep(POLL_MS * 5)
            assertTrue("copied in an app that is not excluded: kept", copyAndWait(scenario, "$PREFIX kept ${System.nanoTime()}"))
        }
    }

    /**
     * CLIP-27 (PLAN §10.7, user decision 2026-11-13): a copied message with a verification code
     * offers the code on the quick-paste strip, and tapping it types the code; negative: the same
     * message marked sensitive offers nothing.
     */
    @Test
    fun aCopiedCodeIsOfferedAndTyped() {
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            checkNotNull(device.wait(Until.findObject(By.desc("k")), WAIT_MS)) { "Kotomo's keys did not appear" }
            runBlocking { settings.update { it.copy(clipboard = it.clipboard.copy(history = true, quickPaste = true)) } }
            val hidden = (100000..999999).random().toString()
            scenario.onActivity {
                val clip = ClipData.newPlainText(null, "$PREFIX: your verification code is $hidden")
                clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                it.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }
            assertTrue("a sensitive clip offered its code", device.wait(Until.findObject(By.text(hidden)), WAIT_MS / 2) == null)
            val code = (100000..999999).random().toString()
            scenario.onActivity {
                it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, "$PREFIX: your verification code is $code"))
            }
            checkNotNull(device.wait(Until.findObject(By.text(code)), WAIT_MS)) { "the copied code is not offered" }.click()
            Thread.sleep(POLL_MS * 3)
            var typed = ""
            scenario.onActivity { typed = it.findViewById<android.widget.EditText>(FieldTestActivity.FIELD_ID).text.toString() }
            assertEquals(code, typed)
        }
    }

    /**
     * A6 (device feedback 2026-11-13): a long press on a clip opens a menu whose lines are at
     * least the panel touch size tall; Pin pins the clip. Negative: a plain tap pastes and opens
     * no menu.
     */
    @Test
    fun longPressMenuIsEasyToTap() {
        val previous = settings.current
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        try {
            runBlocking {
                settings.update {
                    it.copy(
                        clipboard = it.clipboard.copy(history = true, quickPaste = false),
                        toolOrder = listOf(Tool.CLIPBOARD) + (Tool.configurable - Tool.CLIPBOARD),
                        hiddenTools = Tool.configurable.toSet() - Tool.CLIPBOARD,
                    )
                }
            }
            ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
                val q = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }
                val text = "$PREFIX menu ${System.nanoTime()}"
                assertTrue(copyAndWait(scenario, text))
                val res = context.resources
                val barY = q.visibleBounds.top - res.getDimensionPixelSize(R.dimen.kb_pad_top) - res.getDimensionPixelSize(R.dimen.bar_height) / 2
                // With one tool on the bar, Clipboard sits at its end.
                device.click(device.displayWidth - res.getDimensionPixelSize(R.dimen.toolbar_pad) - res.getDimensionPixelSize(R.dimen.toolbar_button) / 2, barY)
                val card = checkNotNull(device.wait(Until.findObject(By.text(text)), WAIT_MS)) { "the clipboard panel does not list the clip" }
                assertFalse("a menu is open before any long press", device.hasObject(By.text(context.getString(R.string.clip_pin))))
                card.longClick()
                val pin = checkNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.clip_pin))), WAIT_MS)) { "the long press opened no menu" }
                // The label sits inside its clickable line: the line's bounds are the touch target.
                val line = generateSequence(pin) { it.parent }.firstOrNull { it.isClickable } ?: pin
                val min = res.getDimensionPixelSize(R.dimen.panel_touch)
                assertTrue("Pin's line is ${line.visibleBounds.height()} px tall, under $min", line.visibleBounds.height() >= min)
                pin.click()
                val until = System.currentTimeMillis() + WAIT_MS
                while (System.currentTimeMillis() < until && history.items.value.none { it.text == text && it.pinned }) Thread.sleep(POLL_MS)
                assertTrue("Pin did not pin the clip", history.items.value.any { it.text == text && it.pinned })
            }
        } finally {
            runBlocking { settings.update { it.copy(clipboard = previous.clipboard, toolOrder = previous.toolOrder, hiddenTools = previous.hiddenTools) } }
        }
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        const val PREFIX = "Kotomo clipboard test"
        const val WAIT_MS = 5000L
        const val POLL_MS = 200L
    }
}
