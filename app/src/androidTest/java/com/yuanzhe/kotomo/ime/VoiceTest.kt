package com.yuanzhe.kotomo.ime

import android.Manifest
import android.content.Intent
import android.text.InputType
import android.view.inputmethod.EditorInfo
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T23 (PLAN §12): voice typing. Negative: password fields and fields that ask not to be learned
 * from do not open the panel. Positive: without the microphone the panel asks for it and does not
 * listen; with it, the microphone button starts the on-device recognizer.
 *
 * Notes: the emulator runs without audio input, so what is heard is never checked here (NOT_RUN in
 * platform-notes.md). The permission is granted by the test through UiAutomation, not the dialog;
 * it is never revoked here, since revoking kills the test's process. Without on-device recognition
 * the panel says so and the positive case ends there.
 */
@RunWith(AndroidJUnit4::class)
class VoiceTest {
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
    fun offInSensitiveFields() = withVoiceTool {
        for ((type, options) in listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD to 0,
            InputType.TYPE_CLASS_TEXT to EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )) {
            open(type, options) {
                tapTool()
                assertFalse("the voice panel opened", device.wait(Until.hasObject(By.text(s(R.string.translate_back))), SHORT_MS) == true)
            }
        }
    }

    @Test
    fun asksForTheMicrophoneThenListens() = withVoiceTool {
        open(InputType.TYPE_CLASS_TEXT, 0) {
            tapTool()
            checkNotNull(device.wait(Until.findObject(By.text(s(R.string.translate_back))), WAIT_MS)) { "the voice panel did not open" }
            assumeTrue("no on-device speech recognition here", !device.hasObject(By.text(s(R.string.voice_unavailable))))
            if (!Voice.permitted(context)) {
                assertTrue("no request for the microphone", device.hasObject(By.text(s(R.string.voice_allow))))
                assertFalse("it offered to listen without the microphone", device.hasObject(By.desc(s(R.string.voice_start))))
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
                device.findObject(By.text(s(R.string.translate_back))).click()
                tapTool()
            }
            val start = device.wait(Until.findObject(By.desc(s(R.string.voice_start))), WAIT_MS)
            assumeTrue("the speech model is missing on this device", start != null)
            start!!.click()
            // Listening, or the recognizer's answer to no audio: either way it ran and the panel shows it.
            val answered = listOf(R.string.voice_listening, R.string.voice_no_match, R.string.voice_fail).any { id ->
                device.wait(Until.hasObject(By.text(s(id))), SHORT_MS) == true
            }
            assertTrue("the recognizer did not start", answered)
            device.findObject(By.text(s(R.string.translate_back))).click()
        }
    }

    private fun open(inputType: Int, imeOptions: Int, block: (ActivityScenario<FieldTestActivity>) -> Unit) {
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, inputType)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, imeOptions)
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

    /** Runs [block] with Voice typing as the only tool on the bar, in Chinese mode. */
    private fun withVoiceTool(block: () -> Unit) {
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
        toolOrder = listOf(Tool.VOICE) + (Tool.configurable - Tool.VOICE),
        hiddenTools = Tool.configurable.toSet() - Tool.VOICE,
    )

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        const val WAIT_MS = 8000L
        const val SHORT_MS = 2500L
        const val QUICK_PASTE_MS = 65_000L
    }
}
