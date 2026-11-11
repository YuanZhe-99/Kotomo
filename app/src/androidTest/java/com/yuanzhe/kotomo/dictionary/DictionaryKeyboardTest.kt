package com.yuanzhe.kotomo.dictionary

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
import com.yuanzhe.kotomo.ime.KeyboardMemory
import com.yuanzhe.kotomo.ime.Mode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T17 on an emulator or device (PLAN §10.5): a word added to the person's dictionary reaches the
 * keyboard at once (the store rewrites librime's tables, the keyboard re-selects the schema), and
 * is gone again once deleted or switched off.
 *
 * Notes: needs Kotomo installed as a debug build; selects it as FieldTypingTest does and types in
 * Chinese mode, fixed for the test's own field (the default mode, Mixed, commits with Enter). Runs in the app's process, so it changes the same [DictionaryStore]
 * the keyboard watches. The test word is made up, on a reading no built-in word has.
 */
@RunWith(AndroidJUnit4::class)
class DictionaryKeyboardTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val store = DictionaryStore.get(instrumentation.targetContext)
    private val memory = KeyboardMemory.get(instrumentation.targetContext)
    private val app = instrumentation.targetContext.packageName
    private val word = Entry(Lang.ZH, "考拓莫", "kao tuo mo", 90, Source.MANUAL)

    @Before
    fun selectKotomo() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        memory.update { m -> m.withApp(app) { it.copy(pinned = Mode.CHINESE) } }
    }

    @After
    fun cleanUp() = runBlocking {
        memory.update { m -> m.withApp(app) { it.copy(pinned = null) } }
        if (store.state.value.locked) store.unlock(PASSWORD.toCharArray())
        if (store.state.value.protected) store.unprotect()
        store.delete(word)
        store.setSources(custom = true)
        Unit
    }

    /** Positive: an added word is the first candidate for its reading; space commits it. */
    @Test
    fun addedWordIsTyped() {
        withField { scenario ->
            runBlocking { store.put(word) }
            assertEquals("考拓莫", type(scenario, "kaotuomo"))
        }
    }

    /** Negative: with the person's dictionary switched off, the word is not offered. */
    @Test
    fun switchedOffWordIsNotTyped() {
        withField { scenario ->
            runBlocking { store.put(word); store.setSources(custom = false) }
            assertNotEquals("考拓莫", type(scenario, "kaotuomo"))
        }
    }

    /**
     * T17-protect (ADR-012): with password protection on, Lock now stops the keyboard offering the
     * person's words (their tables are emptied); unlocking brings them back.
     */
    @Test
    fun lockedWordsAreNotTypedUntilUnlocked() {
        withField { scenario ->
            runBlocking { store.put(word); assertTrue(store.protect(PASSWORD.toCharArray())) }
            assertEquals("protected and unlocked: typed", "考拓莫", type(scenario, "kaotuomo"))
            runBlocking { assertTrue(store.lock()) }
            assertNotEquals("locked: not typed", "考拓莫", type(scenario, "kaotuomo"))
            runBlocking { assertTrue(store.unlock(PASSWORD.toCharArray())) }
            assertEquals("unlocked again: typed", "考拓莫", type(scenario, "kaotuomo"))
            runBlocking { assertTrue(store.unprotect()) }
        }
    }

    /** Opens an ordinary text field with Kotomo's keys showing and runs [block] on it. */
    private fun withField(block: (ActivityScenario<FieldTestActivity>) -> Unit) {
        val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            checkNotNull(device.wait(Until.findObject(By.desc("k")), WAIT_MS)) { "Kotomo's keys did not appear" }
            block(scenario)
        }
    }

    /** Clears the field, taps [letters] and space on Kotomo's keys and returns the field's text. */
    private fun type(scenario: ActivityScenario<FieldTestActivity>, letters: String): String {
        scenario.onActivity { it.findViewById<EditText>(FieldTestActivity.FIELD_ID).setText("") }
        // The schema is re-selected on the engine thread after the tables change.
        Thread.sleep(SETTLE_MS)
        letters.forEach { device.findObject(By.desc(it.toString())).click() }
        device.findObject(By.descStartsWith(SPACE)).click()
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
        var text = ""
        scenario.onActivity { text = it.findViewById<EditText>(FieldTestActivity.FIELD_ID).text.toString() }
        return text
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        /** The space key's description starts with this (R.string.key_space, English). */
        const val SPACE = "Space"
        const val WAIT_MS = 5000L
        const val SETTLE_MS = 1000L
        /** Made up for the test; the protection is turned off again at its end. */
        const val PASSWORD = "kotomo-test-password"
    }
}
