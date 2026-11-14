package com.yuanzhe.kotomo.ime

import android.content.Intent
import android.os.SystemClock
import android.text.InputType
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import com.yuanzhe.kotomo.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T08 on an emulator or device (PLAN §9.6): turning a fuzzy-pinyin switch on makes the keyboard
 * write the rule file and deploy in its deploy process; once the new build is swapped in, the
 * fuzzy spelling converts. Turning every switch off goes back to the prebuilt data.
 *
 * Notes: needs Kotomo installed as a debug build; selects it as FieldTypingTest does and types
 * through its keys in Chinese mode, fixed for the test's own field (the default mode, Mixed, commits
 * with Enter rather than space). The test runs in the app's
 * process, so it changes the settings through the same [SettingsStore] the keyboard watches.
 */
@RunWith(AndroidJUnit4::class)
class FuzzySettingsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val store = SettingsStore.get(context)
    private val files = RimeFiles(context)

    @Before
    fun selectKotomo() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = Mode.CHINESE) } }
    }

    @After
    fun rulesOff() = runBlocking {
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = null) } }
        store.update { it.copy(chineseFuzzy = emptySet(), chineseKeyCorrection = true) }
    }

    /** Positive: with z/zh on, `zongguo` commits 中国 once the deploy is done. */
    @Test
    fun switchOnDeploysAndConverts() {
        withField { scenario ->
            runBlocking { store.update { it.copy(chineseFuzzy = setOf(FuzzyRule.Z_ZH)) } }
            waitFor("the fuzzy build") { files.fuzzy.isFile && files.current }
            // The build is current once it is moved in; librime then reopens on the engine thread,
            // which on the emulator can outlast the settle time, so the first try may come too early.
            var text = ""
            repeat(ATTEMPTS) { if (text != "中国") text = type(scenario, "zongguo") }
            assertEquals("中国", text)
        }
    }

    /** Negative: with every switch off again, the build is dropped and `zongguo` no longer gives 中国. */
    @Test
    fun allOffReturnsToThePrebuiltData() {
        withField { scenario ->
            runBlocking { store.update { it.copy(chineseFuzzy = setOf(FuzzyRule.Z_ZH)) } }
            waitFor("the fuzzy build") { files.fuzzy.isFile && files.current }
            runBlocking { store.update { it.copy(chineseFuzzy = emptySet()) } }
            waitFor("the prebuilt data") { !files.fuzzy.isFile && !files.hasBuild }
            assertTrue(files.prebuilt)
            // Like the positive case: librime reopens on the engine thread after the build is
            // dropped, so the first try may still land on the old build; a real failure fails all.
            var text = "中国"
            repeat(ATTEMPTS) { if (text == "中国") text = type(scenario, "zongguo") }
            assertNotEquals("中国", text)
            assertEquals("你好", type(scenario, "nihao"))
            // A deploy that finishes after the switches went off must not bring its build back.
            assertFalse(files.hasBuild)
        }
    }

    /**
     * Feedback B2: with `chinese.key_correction` on, as it is by default and in the prebuilt data
     * (ADR-016), `nohao` (o for its neighbour i) offers 你好 after the exact reading, and picking
     * it commits it; `nihao` + space still commits 你好. Negative: with the switch off, a deploy
     * later, `nohao` no longer offers 你好.
     */
    @Test
    fun keyCorrectionOffersTheNeighbourReading() {
        withField { scenario ->
            waitFor("the prebuilt data") { !files.fuzzy.isFile && !files.hasBuild }
            var text = ""
            repeat(ATTEMPTS) {
                if (text == "你好") return@repeat
                scenario.onActivity { it.findViewById<EditText>(FieldTestActivity.FIELD_ID).setText("") }
                Thread.sleep(SETTLE_MS)
                "nohao".forEach { device.findObject(By.desc(it.toString())).click() }
                Thread.sleep(SETTLE_MS)
                // The bar draws its candidates; the expanded panel lists them as text.
                val q = device.findObject(By.desc("q"))
                val res = context.resources
                val barY = q.visibleBounds.top - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.kb_pad_top) -
                    res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_height) / 2
                device.click(device.displayWidth - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_expand) / 2, barY)
                val offered = device.wait(Until.findObject(By.desc(BACK)), WAIT_MS)?.let { device.wait(Until.findObject(By.text("你好")), WAIT_MS) }
                if (offered != null) {
                    // 弄好 is the sentence librime makes of the letters as typed; it makes none once
                    // 你好 was learned (the other tests commit it), and 你好 then leads.
                    device.findObject(By.text("弄好"))?.visibleBounds?.let { exact ->
                        val at = offered.visibleBounds
                        assertTrue("the exact reading stands before the corrected one", exact.top < at.top || (exact.top == at.top && exact.left < at.left))
                    }
                    offered.click()
                    device.wait(Until.gone(By.desc(BACK)), WAIT_MS)
                    Thread.sleep(SETTLE_MS)
                    scenario.onActivity { text = it.findViewById<EditText>(FieldTestActivity.FIELD_ID).text.toString() }
                } else {
                    // The deployed build was not loaded yet: leave the panel and the composition.
                    device.findObject(By.desc(BACK))?.click()
                    device.wait(Until.findObject(By.desc("q")), WAIT_MS)
                    device.findObject(By.descStartsWith(SPACE)).click()
                }
            }
            assertEquals("你好", text)
            assertEquals("你好", type(scenario, "nihao"))
            runBlocking { store.update { it.copy(chineseKeyCorrection = false) } }
            waitFor("the build without key correction") { files.fuzzy.isFile && files.current }
            var offered = true
            repeat(ATTEMPTS) {
                if (!offered) return@repeat
                scenario.onActivity { it.findViewById<EditText>(FieldTestActivity.FIELD_ID).setText("") }
                Thread.sleep(SETTLE_MS)
                "nohao".forEach { device.findObject(By.desc(it.toString())).click() }
                Thread.sleep(SETTLE_MS)
                val q = device.findObject(By.desc("q"))
                val res = context.resources
                val barY = q.visibleBounds.top - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.kb_pad_top) -
                    res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_height) / 2
                device.click(device.displayWidth - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_expand) / 2, barY)
                checkNotNull(device.wait(Until.findObject(By.desc(BACK)), WAIT_MS)) { "the candidate panel did not open" }
                device.wait(Until.findObject(By.text("弄好")), WAIT_MS)
                offered = device.findObject(By.text("你好")) != null
                device.findObject(By.desc(BACK)).click()
                device.wait(Until.findObject(By.desc("q")), WAIT_MS)
                device.findObject(By.descStartsWith(SPACE)).click()
            }
            assertFalse("the corrected reading is still offered with the switch off", offered)
        }
    }

    /**
     * Negative: a finished deploy for rules that are no longer on is discarded, not swapped in
     * (once a second deploy of the old rules ended after the switches were turned off, and the
     * keyboard typed with them again).
     */
    @Test
    fun aBuildStagedForOtherRulesIsDiscarded() {
        waitFor("the rules to be off") { !files.fuzzy.isFile && !files.hasBuild }
        File(files.staging, "build").mkdirs()
        File(files.staging, "fuzzy.hash").writeText(Fuzzy.hash(Fuzzy.yaml(setOf(FuzzyRule.Z_ZH))))
        assertFalse(files.promote())
        assertFalse(files.staging.exists())
        assertFalse(files.hasBuild)
    }

    /** Opens an ordinary text field with Kotomo's keys showing and runs [block] on it. */
    private fun withField(block: (ActivityScenario<FieldTestActivity>) -> Unit) {
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            checkNotNull(device.wait(Until.findObject(By.desc("n")), WAIT_MS)) { "Kotomo's keys did not appear" }
            block(scenario)
        }
    }

    /** Clears the field, taps [letters] and space on Kotomo's keys and returns the field's text. */
    private fun type(scenario: ActivityScenario<FieldTestActivity>, letters: String): String {
        scenario.onActivity { it.findViewById<EditText>(FieldTestActivity.FIELD_ID).setText("") }
        // A swap reopens librime on the engine thread; give it a moment before typing.
        Thread.sleep(SETTLE_MS)
        letters.forEach { device.findObject(By.desc(it.toString())).click() }
        device.findObject(By.descStartsWith(SPACE)).click()
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
        var text = ""
        scenario.onActivity { text = it.findViewById<EditText>(FieldTestActivity.FIELD_ID).text.toString() }
        return text
    }

    /** Polls [done] until it holds or [DEPLOY_MS] pass, then fails naming [what]. */
    private fun waitFor(what: String, done: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + DEPLOY_MS
        while (!done()) {
            check(SystemClock.uptimeMillis() < until) { "timed out waiting for $what (status ${files.readStatus()})" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        /** The space key's description starts with this (R.string.key_space, English). */
        const val SPACE = "Space"
        /** The expanded candidate panel's way back (R.string.candidates_collapse, English). */
        const val BACK = "Back to the keyboard"
        const val WAIT_MS = 5000L
        /** A deploy on a phone takes seconds; an emulator can be slower. */
        const val DEPLOY_MS = 120_000L
        const val POLL_MS = 500L
        const val SETTLE_MS = 1000L
        const val ATTEMPTS = 3
    }
}
