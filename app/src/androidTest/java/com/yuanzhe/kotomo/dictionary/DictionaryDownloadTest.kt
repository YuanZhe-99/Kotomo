package com.yuanzhe.kotomo.dictionary

import android.content.Intent
import android.os.Debug
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import kotlinx.coroutines.runBlocking
import com.yuanzhe.kotomo.ime.Mode
import com.yuanzhe.kotomo.settings.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T17-download on an emulator or device (PLAN §10.5, ADR-011): a dictionary downloaded from its
 * real source over the network with Kotomo's own transport, converted on the device and installed,
 * reaches the keyboard: a pack word that was not typed before is typed after. Also measures the
 * time and memory of converting the largest offered source (THUOCL place names) on the device, and
 * how long the keyboard then takes to type again while librime loads its table.
 *
 * Notes: needs the network; when the source cannot be reached the test is skipped (reported as
 * such), never passed. Logs only counts, times and memory (tag KotomoDownload), never words.
 */
@RunWith(AndroidJUnit4::class)
class DictionaryDownloadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val store = DictionaryStore.get(context)
    private val settings = SettingsStore.get(context)
    private var defaultMode = Mode.MIXED

    @Before
    fun selectKotomo() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        // Chinese mode: in Mixed mode space does not commit. The test's field asks for no
        // learning, so it reads nothing remembered per app and takes the default mode.
        defaultMode = settings.current.defaultMode
        runBlocking { settings.update { it.copy(defaultMode = Mode.CHINESE) } }
    }

    @After
    fun cleanUp() = runBlocking {
        settings.update { it.copy(defaultMode = defaultMode) }
        Sources.ALL.forEach { store.uninstall(it.id) }
        Unit
    }

    /** Installs [source] from the network, or skips the test when it cannot be reached. */
    private fun install(source: DictionarySource): Installed = runBlocking {
        try {
            Downloader(HttpsTransport()).install(source, store, File(context.cacheDir, "test-download")) {}
        } catch (e: DownloadException) {
            assumeTrue("the source could not be reached (${e.problem})", e.problem != DownloadProblem.NETWORK)
            throw e
        }
    }

    @Test
    fun downloadedWordsAreTyped() {
        val source = Sources.of("zh-thuocl-it")!!
        // Candidates: the pack's own words of four characters or more, which the built-in
        // dictionary does not type as a whole before the download.
        val started = System.nanoTime()
        val installed = install(source)
        Log.i(TAG, "zh-thuocl-it: ${installed.count} words in ${(System.nanoTime() - started) / 1_000_000} ms")
        val long = File(context.filesDir, "dictionaries/packs/${source.id}/entries.tsv").readLines().drop(1)
            .map { it.split('\t') }.filter { it[1].codePointCount(0, it[1].length) >= 4 }
        val entries = long.filterIndexed { i, _ -> i % (long.size / CANDIDATES + 1) == 0 }
        assertTrue(entries.isNotEmpty())
        runBlocking { store.setPack(source.id, false) }
        withField { scenario ->
            val before = entries.associateWith { type(scenario, it[2].replace(" ", ""), WAIT_MS) }
            val missing = entries.filter { before[it] != it[1] }
            assumeTrue("every candidate word is typed without the pack", missing.isNotEmpty())
            runBlocking { store.setPack(source.id, true) }
            // A pack word ranks after the built-in words (quality 0), so a reading librime also
            // makes into a sentence of its own can keep that sentence first; most are typed.
            val typed = missing.count { type(scenario, it[2].replace(" ", ""), WAIT_MS) == it[1] }
            Log.i(TAG, "zh-thuocl-it: $typed of ${missing.size} words not typed before the download are typed after it")
            assertTrue("downloaded words are typed ($typed of ${missing.size})", typed > 0)
        }
    }

    @Test
    fun theLargestOfferedSourceConvertsAndLoads() {
        val rt = Runtime.getRuntime()
        val started = System.nanoTime()
        val installed = install(Sources.of("zh-thuocl-places")!!)
        val ms = (System.nanoTime() - started) / 1_000_000
        val pss = Debug.getPss() / 1024
        Log.i(TAG, "zh-thuocl-places: ${installed.count} words in $ms ms; heap max ${rt.maxMemory() / MB} MB, used ${(rt.totalMemory() - rt.freeMemory()) / MB} MB, PSS $pss MB")
        assertTrue(installed.count > 10_000)
        withField { scenario ->
            val typed = System.nanoTime()
            val text = type(scenario, "nihao", LOAD_MS)
            Log.i(TAG, "zh-thuocl-places: the keyboard typed again ${(System.nanoTime() - typed) / 1_000_000} ms after the install")
            assertEquals("你好", text)
        }
    }

    /** Opens an ordinary text field with Kotomo's keys showing and runs [block] on it. */
    private fun withField(block: (ActivityScenario<FieldTestActivity>) -> Unit) {
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            // No learning: what the test types before the download must not teach librime
            // sentences that would then outrank the pack (the private schema reads the same tables).
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            checkNotNull(device.wait(Until.findObject(By.desc("k")), WAIT_MS)) { "Kotomo's keys did not appear" }
            block(scenario)
        }
    }

    /**
     * Clears the field, taps [letters] and space on Kotomo's keys and returns the field's text,
     * waiting up to [waitMs] for something to be committed (librime may still be loading a table).
     */
    private fun type(scenario: ActivityScenario<FieldTestActivity>, letters: String, waitMs: Long = SETTLE_MS): String {
        scenario.onActivity { it.findViewById<EditText>(FieldTestActivity.FIELD_ID).setText("") }
        Thread.sleep(SETTLE_MS)
        letters.forEach { device.findObject(By.desc(it.toString())).click() }
        device.findObject(By.descStartsWith(SPACE)).click()
        device.waitForIdle()
        var text = ""
        val until = System.currentTimeMillis() + waitMs
        do {
            Thread.sleep(POLL_MS)
            scenario.onActivity { text = it.findViewById<EditText>(FieldTestActivity.FIELD_ID).text.toString() }
        } while (text.isEmpty() && System.currentTimeMillis() < until)
        return text
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        const val SPACE = "Space"
        const val TAG = "KotomoDownload"
        const val WAIT_MS = 5000L
        const val SETTLE_MS = 1000L
        const val POLL_MS = 250L
        /** How long the keyboard may take to type again after a large pack (librime loads its table). */
        const val LOAD_MS = 180_000L
        /** How many of the pack's long words are tried before the download. */
        const val CANDIDATES = 30
        const val MB = 1024 * 1024
    }
}
