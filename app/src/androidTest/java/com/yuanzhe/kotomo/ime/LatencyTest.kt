package com.yuanzhe.kotomo.ime

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T15 (PLAN §13.4): measures key typed → candidates drawn and the engine's work per key while
 * typing through Kotomo's keys in each mode, with the diagnostics page's own probes ([Probe]).
 *
 * Notes: an emulator's numbers describe the emulator (software rendering, a shared host), never a
 * device's latency; they are reported as instrumentation status (`kotomo.latency.*`, timings only,
 * nothing typed) for platform-notes.md. The only assertion is that samples were taken.
 */
@RunWith(AndroidJUnit4::class)
class LatencyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext

    @Before
    fun selectKotomo() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
    }

    @After
    fun unpin() {
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = null) } }
    }

    @Test
    fun chinese() = measure(Mode.CHINESE, listOf("nihaoshijie", "womenyiqiqu", "jintiantianqihenhao"))

    @Test
    fun japanese() = measure(Mode.JAPANESE, listOf("watashiha", "kyouhaiitenki", "arigatou"))

    @Test
    fun mixed() = measure(Mode.MIXED, listOf("chidainikainiikou", "coffeeganomitai", "nihaosakura"))

    @Test
    fun english() = measure(Mode.ENGLISH, listOf("hello", "keyboard", "tomorrow"))

    /** Types [words] [ROUNDS] times in [mode], committing each with Space, and reports the probes. */
    private fun measure(mode: Mode, words: List<String>) {
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = mode) } }
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }
            Thread.sleep(SETTLE_MS)
            // The first words warm the engines up; the probes count from here.
            instrumentation.runOnMainSync { Probe.candidateDraw.clear(); Probe.engineWork.clear() }
            val keys = "abcdefghijklmnopqrstuvwxyz".associateWith { c -> checkNotNull(device.findObject(By.desc(c.toString()))).visibleCenter }
            val space = checkNotNull(device.findObject(By.descStartsWith(SPACE))).visibleCenter
            repeat(ROUNDS) {
                for (w in words) {
                    for (c in w) {
                        val p = keys.getValue(c)
                        device.click(p.x, p.y)
                        Thread.sleep(KEY_MS)
                    }
                    device.click(space.x, space.y)
                    Thread.sleep(KEY_MS * 2)
                }
                scenario.onActivity { it.findViewById<EditText>(FieldTestActivity.FIELD_ID).setText("") }
            }
            Thread.sleep(SETTLE_MS)
            val report = Bundle()
            instrumentation.runOnMainSync {
                fun put(name: String, s: LatencyStats) {
                    report.putString("kotomo.latency.${mode.name.lowercase()}.$name", "n=${s.count} p50=${s.percentile(50)} p95=${s.percentile(95)} max=${s.percentile(100)}")
                }
                put("candidates", Probe.candidateDraw)
                put("engine", Probe.engineWork)
                report.putString("kotomo.latency.cold_start_ms", Probe.coldStartMs.toString())
                report.putString("kotomo.latency.pss_kb", Probe.pssKb.toString())
            }
            instrumentation.sendStatus(0, report)
            android.util.Log.i("KotomoLatency", report.keySet().sorted().joinToString("; ") { "$it ${report.getString(it)}" })
            var n = 0
            instrumentation.runOnMainSync { n = Probe.candidateDraw.count }
            assertTrue("no candidate timings were taken", n > 0)
        }
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        const val SPACE = "Space"
        const val WAIT_MS = 8000L
        const val SETTLE_MS = 2000L
        /** Between keys: about a fast typist's pace. */
        const val KEY_MS = 150L
        const val ROUNDS = 4
    }
}
