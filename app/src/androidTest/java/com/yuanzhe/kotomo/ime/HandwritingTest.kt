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
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import com.yuanzhe.kotomo.settings.Settings
import com.yuanzhe.kotomo.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T22 (PLAN §12): the handwriting panel. Positive: the model downloads only after Download, a
 * stroke drawn across the writing area is read as 一 and committed. Negative: a password field
 * does not open the panel.
 *
 * Notes: downloads ML Kit's mainland Chinese model (about 20 MB) over the emulator's network; when
 * the download cannot run here the positive case is skipped, not passed. Strokes are drawn with
 * UiDevice.swipe, a finger and not a stylus.
 */
@RunWith(AndroidJUnit4::class)
class HandwritingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val res = context.resources

    @Before
    fun selectKotomo() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
    }

    @Test
    fun strokeIsReadAndCommitted() = withHandwritingTool {
        open(InputType.TYPE_CLASS_TEXT) { scenario ->
            tapTool()
            val notice = By.text(s(R.string.hw_download_notice))
            // The writing area exists only once the model is ready.
            val hint = By.desc(s(R.string.hw_area))
            // ML Kit's first call registers all its model groups, which takes about a minute on the emulator.
            val deadline = System.currentTimeMillis() + DOWNLOAD_MS
            while (System.currentTimeMillis() < deadline && !device.hasObject(notice) && !device.hasObject(hint)) Thread.sleep(SHORT_MS)
            if (device.hasObject(notice)) {
                assertFalse("the model was ready without Download", device.hasObject(hint))
                device.findObject(By.text(s(R.string.ai_download))).click()
            }
            val ready = device.wait(Until.findObject(hint), DOWNLOAD_MS)
            assumeTrue("ML Kit's handwriting model could not be downloaded here", ready != null)
            val area = checkNotNull(device.findObject(By.desc(s(R.string.hw_area)))) { "no writing area" }.visibleBounds
            device.swipe(area.left + area.width() / 5, area.centerY(), area.right - area.width() / 5, area.centerY(), STEPS)
            val one = checkNotNull(device.wait(Until.findObject(By.text("一")), WAIT_MS)) { "一 was not among the candidates" }
            one.click()
            Thread.sleep(SHORT_MS)
            assertEquals("一", text(scenario))
        }
    }

    @Test
    fun passwordFieldKeepsTheKeys() = withHandwritingTool {
        open(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            tapTool()
            assertFalse("the panel opened in a password field", device.wait(Until.hasObject(By.desc(s(R.string.hw_area))), SHORT_MS) == true)
            assertFalse(device.hasObject(By.text(s(R.string.hw_download_notice))))
        }
    }

    private fun open(inputType: Int, block: (ActivityScenario<FieldTestActivity>) -> Unit) {
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, inputType)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }
            block(scenario)
        }
    }

    /** Taps the toolbar's last button, where the only tool on the bar sits. */
    private fun tapTool() {
        device.wait(Until.gone(By.desc(s(R.string.quick_close))), QUICK_PASTE_MS)
        val q = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS))
        val y = q.visibleBounds.top - res.getDimensionPixelSize(R.dimen.kb_pad_top) - res.getDimensionPixelSize(R.dimen.bar_height) / 2
        val pad = res.getDimensionPixelSize(R.dimen.toolbar_pad) + res.getDimensionPixelSize(R.dimen.toolbar_button) / 2
        device.click(device.displayWidth - pad, y)
        device.waitForIdle()
    }

    private fun s(id: Int) = res.getString(id)

    private fun text(scenario: ActivityScenario<FieldTestActivity>): String {
        var t = ""
        scenario.onActivity { t = it.findViewById<EditText>(FieldTestActivity.FIELD_ID).text.toString() }
        return t
    }

    /** Runs [block] with Handwriting as the only tool on the bar, in Chinese mode with Simplified script. */
    private fun withHandwritingTool(block: () -> Unit) {
        val store = SettingsStore.get(context)
        val before = store.current
        val memory = KeyboardMemory.get(context)
        memory.update { m -> m.withApp(context.packageName) { it.copy(pinned = Mode.CHINESE) } }
        runBlocking { store.update { only(it) } }
        Thread.sleep(SHORT_MS)
        try {
            block()
        } finally {
            runBlocking { store.update { before } }
            memory.update { m -> m.withApp(context.packageName) { it.copy(pinned = null) } }
        }
    }

    private fun only(s: Settings) = s.copy(
        toolOrder = listOf(Tool.HANDWRITING) + (Tool.configurable - Tool.HANDWRITING),
        hiddenTools = Tool.configurable.toSet() - Tool.HANDWRITING,
        chineseScript = com.yuanzhe.kotomo.settings.ChineseScript.SIMPLIFIED,
    )

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        const val WAIT_MS = 8000L
        const val SHORT_MS = 1500L
        const val DOWNLOAD_MS = 180_000L
        const val QUICK_PASTE_MS = 65_000L
        const val STEPS = 30
    }
}
