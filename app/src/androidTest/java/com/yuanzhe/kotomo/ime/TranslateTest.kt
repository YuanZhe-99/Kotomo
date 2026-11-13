package com.yuanzhe.kotomo.ime

import android.content.Intent
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import com.yuanzhe.kotomo.settings.Settings
import com.yuanzhe.kotomo.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens the toolbar's Translate (T18, PLAN §11.1, §14) on an emulator or device: refused while AI
 * is off and in fields that ask not to be learned from; with AI on, the panel opens and waits for
 * Download before any model is fetched; after a download the preview replaces the text only on
 * Replace, and Undo restores it.
 *
 * Notes: the download case needs Google's model servers; it is skipped, not passed, when they
 * cannot be reached. Emulator results cover behavior only.
 */
@RunWith(AndroidJUnit4::class)
class TranslateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val res = instrumentation.targetContext.resources

    /** Makes Kotomo the keyboard. */
    @Before
    fun selectKotomo() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        // A clip copied by an earlier test brings the quick-paste strip, which moves the bar.
        instrumentation.runOnMainSync { instrumentation.targetContext.getSystemService(android.content.ClipboardManager::class.java).clearPrimaryClip() }
    }

    /** Negative: with AI off, Translate shows a notice and no panel. */
    @Test
    fun offWhileAiIsOff() = withSettings({ translateOnly(it).copy(ai = it.ai.copy(enabled = false)) }) {
        open(0) { scenario ->
            tapTranslate()
            assertFalse("the panel opened while AI is off", device.wait(Until.hasObject(By.text(s(com.yuanzhe.kotomo.R.string.translate_from))), SHORT_MS) == true)
            assertEquals("hello world", text(scenario))
        }
    }

    /** Negative: a field that asks not to be learned from never reaches the panel (PLAN §14). */
    @Test
    fun offInFieldsThatAskNotToLearn() = withSettings({ translateOnly(it).copy(ai = it.ai.copy(enabled = true)) }) {
        open(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) {
            tapTranslate()
            assertFalse("the panel opened in a no-learning field", device.wait(Until.hasObject(By.text(s(com.yuanzhe.kotomo.R.string.translate_from))), SHORT_MS) == true)
        }
    }

    /**
     * Positive: with AI on the panel opens on the field's text; a missing model waits for
     * Download (nothing is fetched by itself). After Download the translation is previewed,
     * Replace puts it into the field and Undo brings the original back.
     */
    @Test
    fun translatesOnlyAfterDownloadAndReplace() = withSettings({ translateOnly(it).copy(ai = it.ai.copy(enabled = true)) }) {
        open(0) { scenario ->
            // A notice from an earlier case may still cover the toolbar: tap again until the panel opens.
            val from = By.text(s(com.yuanzhe.kotomo.R.string.translate_from))
            repeat(ATTEMPTS) { if (!device.hasObject(from)) { tapTranslate(); device.wait(Until.hasObject(from), SHORT_MS * 2) } }
            checkNotNull(device.wait(Until.findObject(from), WAIT_MS)) { "the translate panel did not open" }
            val notice = s(com.yuanzhe.kotomo.R.string.translate_download_notice)
            val replace = s(com.yuanzhe.kotomo.R.string.ai_replace)
            // Either the models are missing (then the panel waits for Download) or already there.
            val waiting = device.wait(Until.hasObject(By.text(notice)), WAIT_MS) == true
            if (waiting) {
                Thread.sleep(SHORT_MS)
                assertEquals("the field changed before anything was confirmed", "hello world", text(scenario))
                assertFalse("a translation appeared without Download", device.hasObject(By.text(replace)))
                device.findObject(By.text(s(com.yuanzhe.kotomo.R.string.ai_download))).click()
            }
            val ready = device.wait(Until.findObject(By.text(replace)), DOWNLOAD_MS)
            assumeTrue("ML Kit's models could not be downloaded here", ready != null || !device.hasObject(By.text(s(com.yuanzhe.kotomo.R.string.translate_fail_download))))
            checkNotNull(ready) { "no translation after the download" }
            assertEquals("hello world", text(scenario))
            ready.click()
            Thread.sleep(SHORT_MS)
            val translated = text(scenario)
            assertNotEquals("Replace did not change the field", "hello world", translated)
            assertFalse("the translation is not Chinese: $translated", translated.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
            // The bar offers Undo as its first candidate (drawn, so tapped by position).
            device.click(device.displayWidth / 8, barY())
            Thread.sleep(SHORT_MS)
            assertEquals("hello world", text(scenario))
        }
    }

    /** Puts Translate alone on the toolbar (it then sits at the bar's end) and English mode's keys. */
    private fun translateOnly(s: Settings) = s.copy(toolOrder = listOf(Tool.TRANSLATE) + (Tool.configurable - Tool.TRANSLATE), hiddenTools = Tool.configurable.toSet() - Tool.TRANSLATE)

    /** Opens a text field with [imeOptions] holding "hello world" and runs [block] in it. */
    private fun open(imeOptions: Int, block: (ActivityScenario<FieldTestActivity>) -> Unit) {
        val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, imeOptions)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            checkNotNull(device.wait(Until.findObject(By.descStartsWith(SPACE)), WAIT_MS)) { "Kotomo's keys did not appear" }
            // The keyboard may still settle the field it just started in; set the text until it stays.
            repeat(ATTEMPTS) {
                if (text(scenario) == "hello world") return@repeat
                scenario.onActivity { a -> a.findViewById<EditText>(FieldTestActivity.FIELD_ID).apply { setText("hello world"); setSelection(11) } }
                Thread.sleep(SHORT_MS)
            }
            check(text(scenario) == "hello world") { "the field did not keep its text" }
            block(scenario)
        }
    }

    /** Taps the toolbar's last button, where the only tool on the bar sits. */
    private fun tapTranslate() {
        // The quick-paste strip of a clip an earlier test copied covers the bar for up to a minute.
        device.wait(Until.gone(By.text(s(com.yuanzhe.kotomo.R.string.quick_copied))), QUICK_PASTE_MS)
        val pad = res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.toolbar_pad) + res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.toolbar_button) / 2
        device.click(device.displayWidth - pad, barY())
        device.waitForIdle()
    }

    /** The middle of the candidate bar, above the letter keys. */
    private fun barY(): Int {
        val q = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }
        return q.visibleBounds.top - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.kb_pad_top) - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_height) / 2
    }

    private fun s(id: Int) = res.getString(id)

    private fun text(scenario: ActivityScenario<FieldTestActivity>): String {
        var t = ""
        scenario.onActivity { t = it.findViewById<EditText>(FieldTestActivity.FIELD_ID).text.toString() }
        return t
    }

    /** Runs [block] with [change] applied to the settings, in English mode, and puts both back. */
    private fun withSettings(change: (Settings) -> Settings, block: () -> Unit) {
        val context = instrumentation.targetContext
        val store = SettingsStore.get(context)
        val before = store.current
        val memory = KeyboardMemory.get(context)
        memory.update { m -> m.withApp(context.packageName) { it.copy(pinned = Mode.ENGLISH) } }
        runBlocking { store.update(change) }
        Thread.sleep(SHORT_MS)
        try {
            block()
        } finally {
            runBlocking { store.update { before } }
            memory.update { m -> m.withApp(context.packageName) { it.copy(pinned = null) } }
        }
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        /** The space key's description starts with this (R.string.key_space, English). */
        const val SPACE = "Space"
        const val WAIT_MS = 10_000L
        const val SHORT_MS = 1_500L
        const val ATTEMPTS = 3
        const val QUICK_PASTE_MS = 65_000L
        /** Two or three language models of about 30 MB each. */
        const val DOWNLOAD_MS = 240_000L
    }
}
