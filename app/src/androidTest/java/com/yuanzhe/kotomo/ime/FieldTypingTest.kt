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
        inMode(Mode.JAPANESE) { assertEquals("日本", typeInto(InputType.TYPE_CLASS_TEXT, 0, "nihon", expected = "日本")) }
    }

    /**
     * T03 negative: Enter keeps the kana as typed instead of converting.
     * Notes: the field asks for no Enter action, so the key is the plain Enter (a single-line
     * field would make it Done).
     */
    @Test
    fun japaneseEnterKeepsTheKana() {
        inMode(Mode.JAPANESE) {
            val text = typeInto(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION, "nihon", expected = "にほん", space = false, enter = true)
            assertEquals("にほん", text)
        }
    }

    /**
     * PLAN §2.3: letters typed in Chinese stay uncommitted when the mode changes, and Japanese
     * converts them. The switch comes from hiding the mode in use (the keyboard then moves to the
     * default mode), the same path as choosing a mode from the language choice.
     */
    @Test
    fun switchingModeKeepsTheLetters() {
        inMode(Mode.CHINESE) {
            withSettings({ it.copy(defaultMode = Mode.JAPANESE) }) {
                val text = typeInto(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION, "nihon", expected = "にほん", space = false, enter = true) {
                    changeSettings { it.copy(hiddenModes = setOf(Mode.CHINESE)) }
                }
                assertEquals("にほん", text)
            }
        }
    }

    /** PLAN §2.3, the other way: romaji typed in Japanese becomes a pinyin composition in Chinese. */
    @Test
    fun switchingFromJapaneseKeepsTheLetters() {
        inMode(Mode.JAPANESE) {
            withSettings({ it.copy(defaultMode = Mode.CHINESE) }) {
                val text = typeInto(InputType.TYPE_CLASS_TEXT, 0, "nihao", expected = "你好") {
                    changeSettings { it.copy(hiddenModes = setOf(Mode.JAPANESE)) }
                }
                assertEquals("你好", text)
            }
        }
    }

    /**
     * PLAN §8.8: clearing the learning data while the keyboard runs closes and reopens librime,
     * and Chinese still converts afterwards.
     */
    @Test
    fun typingWorksAfterClearingLearningData() {
        assertEquals("你好", typeInto(InputType.TYPE_CLASS_TEXT, 0, "nihao", expected = "你好"))
        val cleared = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync { LearningData.clear(instrumentation.targetContext) { cleared.countDown() } }
        check(cleared.await(WAIT_MS * 3, java.util.concurrent.TimeUnit.MILLISECONDS)) { "clearing did not finish" }
        assertEquals("你好", typeInto(InputType.TYPE_CLASS_TEXT, 0, "nihao", expected = "你好"))
    }

    /**
     * PLAN §8.6: the emoji panel's search types "cat" into its own query, never into the field,
     * and Enter sends the first result.
     */
    @Test
    fun emojiSearchSendsTheFirstResult() {
        val expected = Candidates.emojiSearch(runBlocking { EmojiIndex.load(instrumentation.targetContext) }, "cat").first()
        val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            checkNotNull(device.wait(Until.findObject(By.descStartsWith(EMOJI)), WAIT_MS)) { "Kotomo's keys did not appear" }.click()
            checkNotNull(device.wait(Until.findObject(By.desc(SEARCH)), WAIT_MS)) { "no emoji search" }.click()
            checkNotNull(device.wait(Until.findObject(By.desc("c")), WAIT_MS)) { "the keys did not come back" }
            "cat".forEach { device.findObject(By.desc(it.toString())).click() }
            // Negative: the query stays in the keyboard.
            assertEquals("", read(scenario))
            device.findObject(By.descStartsWith(ENTER)).click()
            assertEquals(expected, read(scenario))
        }
    }

    /**
     * Runs [block] with the current app fixed to [mode] in Kotomo's memory (the quick panel's
     * "Fix this mode here"), and releases it afterwards.
     */
    private fun inMode(mode: Mode, block: () -> Unit) {
        val context = instrumentation.targetContext
        val memory = KeyboardMemory.get(context)
        val app = context.packageName
        memory.update { m -> m.withApp(app) { it.copy(pinned = mode) } }
        try {
            block()
        } finally {
            memory.update { m -> m.withApp(app) { it.copy(pinned = null) } }
        }
    }

    /** Runs [block] with [change] applied to the settings, and puts the settings back afterwards. */
    private fun withSettings(change: (Settings) -> Settings, block: () -> Unit) {
        val before = SettingsStore.get(instrumentation.targetContext).current
        changeSettings(change)
        try {
            block()
        } finally {
            changeSettings { before }
        }
    }

    /** Changes the settings and waits until the change is stored (the keyboard follows the store). */
    private fun changeSettings(change: (Settings) -> Settings) = runBlocking {
        SettingsStore.get(instrumentation.targetContext).update(change)
        Thread.sleep(READ_MS)
    }

    /**
     * Opens a field of the given kind, taps [letters] (then runs [afterLetters], then space unless
     * [space] is false, then Enter if [enter]) on Kotomo's keyboard and returns the field's text.
     * Notes: the first field of a run may come up before librime has opened its data; then the
     * letters type directly, so the field is cleared and the typing repeated a few times.
     */
    private fun typeInto(
        inputType: Int, imeOptions: Int, letters: String, expected: String, space: Boolean = true, enter: Boolean = false,
        afterLetters: () -> Unit = {},
    ): String {
        val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, inputType)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, imeOptions)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            var text = ""
            repeat(ATTEMPTS) {
                checkNotNull(device.wait(Until.findObject(By.desc(letters.first().toString())), WAIT_MS)) { "Kotomo's keys did not appear" }
                letters.forEach { device.findObject(By.desc(it.toString())).click() }
                Thread.sleep(READ_MS)
                afterLetters()
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
        /** The Emoji key's description starts with this (R.string.key_emoji, English). */
        const val EMOJI = "Emoji"
        /** The emoji panel's search tab (R.string.emoji_search, English). */
        const val SEARCH = "Search emoji"
        const val ATTEMPTS = 3
        const val WAIT_MS = 3000L
        const val READS = 3
        const val READ_MS = 300L
    }
}
