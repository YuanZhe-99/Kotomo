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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Types through Kotomo's own keys into fields of each kind (T12, T13; PLAN §8.7, §14) on an
 * emulator or device, and checks what the field ends up holding.
 *
 * Notes: needs Kotomo installed as a debug build; the test enables and selects it, and lets
 * on-screen keyboards show next to the emulator's hardware keyboard. Keys are found through the
 * keyboard's accessibility nodes (their content descriptions are the key labels). Emulator results
 * cover behavior only, never latency.
 */
@RunWith(AndroidJUnit4::class)
class FieldTypingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    /** Makes Kotomo the keyboard. */
    @Before
    fun selectKotomo() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
    }

    /** Positive: an ordinary field converts pinyin; space commits the first candidate. */
    @Test
    fun ordinaryFieldConvertsPinyin() {
        assertEquals("你好", typeInto(InputType.TYPE_CLASS_TEXT, 0, "nihao", expected = "你好"))
    }

    /** T12 positive: a field asking for no personalized learning still converts Chinese. */
    @Test
    fun noLearningFieldStillConvertsPinyin() {
        val text = typeInto(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, "nihao", expected = "你好")
        assertEquals("你好", text)
    }

    /** T12 negative: a password field gets direct typing, never a conversion. */
    @Test
    fun passwordFieldTypesLettersDirectly() {
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertEquals("nihao", typeInto(password, 0, "nihao", expected = "nihao", space = false))
    }

    /** T03 positive: Japanese mode converts romaji; space commits the first candidate. */
    @Test
    fun japaneseConvertsOnSpace() {
        inMode("JAPANESE") { assertEquals("日本", typeInto(InputType.TYPE_CLASS_TEXT, 0, "nihon", expected = "日本")) }
    }

    /**
     * T03 negative: Enter keeps the kana as typed instead of converting.
     * Notes: the field asks for no Enter action, so the key is the plain Enter (a single-line
     * field would make it Done).
     */
    @Test
    fun japaneseEnterKeepsTheKana() {
        inMode("JAPANESE") {
            val text = typeInto(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION, "nihon", expected = "にほん", space = false, enter = true)
            assertEquals("にほん", text)
        }
    }

    /**
     * Runs [block] with Kotomo starting in [mode] in this app (the per-app mode Kotomo keeps in its
     * preferences), and removes it again afterwards.
     */
    private fun inMode(mode: String, block: () -> Unit) {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val key = "mode." + context.packageName
        prefs.edit().putString(key, mode).commit()
        try {
            block()
        } finally {
            prefs.edit().remove(key).commit()
        }
    }

    /**
     * Opens a field of the given kind, taps [letters] (then space unless [space] is false, then
     * Enter if [enter]) on
     * Kotomo's keyboard and returns the field's text.
     * Notes: the first field of a run may come up before librime has opened its data; then the
     * letters type directly, so the field is cleared and the typing repeated a few times.
     */
    private fun typeInto(inputType: Int, imeOptions: Int, letters: String, expected: String, space: Boolean = true, enter: Boolean = false): String {
        val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, inputType)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, imeOptions)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            var text = ""
            repeat(ATTEMPTS) {
                checkNotNull(device.wait(Until.findObject(By.desc(letters.first().toString())), WAIT_MS)) { "Kotomo's keys did not appear" }
                letters.forEach { device.findObject(By.desc(it.toString())).click() }
                if (space) device.findObject(By.descStartsWith(SPACE)).click()
                if (enter) {
                    Thread.sleep(READ_MS)
                    device.findObject(By.descStartsWith(ENTER)).click()
                }
                device.waitForIdle()
                text = read(scenario)
                if (text == expected) return text
                device.wait(Until.hasObject(By.desc(letters.first().toString())), WAIT_MS)
                scenario.onActivity { it.findViewById<EditText>(FieldTestActivity.FIELD_ID).setText("") }
                Thread.sleep(WAIT_MS)
            }
            return text
        }
    }

    /** Reads the field's text, waiting briefly for the keyboard's last commit to land. */
    private fun read(scenario: ActivityScenario<FieldTestActivity>): String {
        var text = ""
        repeat(READS) {
            Thread.sleep(READ_MS)
            scenario.onActivity { text = it.findViewById<EditText>(FieldTestActivity.FIELD_ID).text.toString() }
        }
        return text
    }

    private companion object {
        const val IME = "com.yuanzhe.kotomo/.ime.KotomoService"
        /** The space key's description starts with this (R.string.key_space, English). */
        const val SPACE = "Space"
        /** The Enter key's description starts with this (R.string.key_enter, English). */
        const val ENTER = "Enter"
        /** Kotomo's own preferences file (KotomoService.PREFS). */
        const val PREFS = "ime"
        const val ATTEMPTS = 3
        const val WAIT_MS = 3000L
        const val READS = 3
        const val READ_MS = 300L
    }
}
