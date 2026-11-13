package com.yuanzhe.kotomo.ime

import android.content.Intent
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.yuanzhe.kotomo.engine.Core
import com.yuanzhe.kotomo.engine.English
import com.yuanzhe.kotomo.engine.Rime
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import com.yuanzhe.kotomo.settings.EnterKey
import com.yuanzhe.kotomo.settings.SpaceKey
import com.yuanzhe.kotomo.settings.Settings
import com.yuanzhe.kotomo.settings.SettingsStore
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
 * Mixed mode on an emulator or device (PLAN §8.3, §9.1, §9.2, §9.10; T04, T05, T07, T12): the
 * keyboard types through the coordinator in `libkotomo_core.so`, which reaches librime, Mozc and
 * the English dictionary through the function tables their libraries hand over (ADR-008). This
 * is where that hand-over is proven on Android: a conversion that needs all of them only comes
 * out when every table was reached across the libraries.
 *
 * Notes: needs Kotomo installed as a debug build with the prebuilt Mozc and the compiled
 * lexicon; selects it as FieldTypingTest does and fixes Mixed mode for the test's own field.
 * Enter is the plain Enter key (the fields ask for no Enter action). Emulator results cover
 * behavior only, never latency.
 */
@RunWith(AndroidJUnit4::class)
class MixedTypingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val store = SettingsStore.get(context)
    private lateinit var before: Settings
    private lateinit var learnedBefore: Learned

    @Before
    fun selectKotomoInMixedMode() {
        device.executeShellCommand("ime enable $IME")
        device.executeShellCommand("ime set $IME")
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        before = store.current
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = Mode.MIXED) } }
        // Each test starts without history, so what one commits does not reorder the next.
        learnedBefore = MixedLearning.get(context).current
        MixedLearning.get(context).update { Learned() }
    }

    @After
    fun restore() = runBlocking {
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = null) } }
        store.update { before }
        MixedLearning.get(context).update { learnedBefore }
    }

    /**
     * T11, T12 (PLAN §9.9, §14): a commit teaches its reading, a field that asks for no
     * personalized learning teaches nothing, and what the person ranks reaches the engine: a
     * reading kept first is what Enter commits, until it is deleted.
     */
    @Test
    fun learnsWhereTheFieldAllowsAndFollowsThePersonsRanking() {
        val learning = MixedLearning.get(context)
        assertEquals("你好", type("nihao", expected = "你好", imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertEquals("a field that asks for no learning taught something", emptySet<Pair<String, String>>(), learning.current.readings.keys)
        assertEquals("你好", type("nihao", expected = "你好"))
        assertEquals(setOf("nihao" to "你好"), learning.current.readings.keys)
        val plain = type("ai", expected = "", attempts = 1)
        assertTrue("without history Enter commits $plain", plain.isNotEmpty() && plain != "愛")
        val reading = LearnedReading("ai", "愛")
        learning.update { Learned().ranked(reading, LearnedRank.PINNED) }
        assertEquals("愛", type("ai", expected = "愛"))
        learning.update { it.without(reading) }
        assertEquals(plain, type("ai", expected = plain))
    }

    /** T04 positive: pinyin typed in Mixed mode converts to Chinese; Enter commits the best candidate. */
    @Test
    fun enterCommitsTheBestCandidate() {
        assertEquals("你好", type("nihao", expected = "你好"))
    }

    /** ADR-010 negative: space only marks a boundary; the letters stay uncommitted in the field. */
    @Test
    fun spaceSeparatesAndDoesNotCommit() {
        assertEquals("nihao ", type("nihao ", expected = "nihao ", enter = false))
    }

    /** `mixed.space` = commit_first: space commits the first candidate, as in Chinese mode. */
    @Test
    fun spaceCommitsWhenThePersonChoseSo() {
        change { it.copy(mixed = it.mixed.copy(keys = it.mixed.keys.copy(space = SpaceKey.COMMIT_FIRST))) }
        assertEquals("你好", type("nihao ", expected = "你好", enter = false))
    }

    /**
     * `mixed.space` = next (ADR-010): space shows the first candidate in the field without
     * committing it, backspace returns to the letters, and Enter commits the candidate shown.
     */
    @Test
    fun spaceChoosesACandidateWhenThePersonChoseSo() {
        change { it.copy(mixed = it.mixed.copy(keys = it.mixed.keys.copy(space = SpaceKey.NEXT))) }
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            keys("nihao ")
            assertEquals("你好", read(scenario))
            assertTrue("space committed the candidate", composing(scenario))
            device.findObject(By.descStartsWith(BACKSPACE)).click()
            assertEquals("nihao", read(scenario))
            keys(" ")
            enter()
            assertEquals("你好", read(scenario))
            assertFalse("Enter left the candidate uncommitted", composing(scenario))
        }
    }

    /** `mixed.enter` = shown: Enter commits the letters as the field shows them, not a candidate. */
    @Test
    fun enterCommitsTheLettersWhenThePersonChoseSo() {
        change { it.copy(mixed = it.mixed.copy(keys = it.mixed.keys.copy(enter = EnterKey.SHOWN))) }
        assertEquals("nihao", type("nihao", expected = "nihao"))
    }

    /**
     * T07-001 positive: one composition with pinyin for a Japanese place name and romaji converts
     * as a whole sentence. It needs the lexicon (pinyin → Japanese), librime's spelling table,
     * Mozc's romaji table and Mozc's converter with a pinned span, all through the hand-over.
     */
    @Test
    fun wholeSentenceAcrossLanguages() {
        assertEquals(SENTENCE, type(SENTENCE_KEYS, expected = SENTENCE))
    }

    /** PLAN §9.10 negative: with pinyin → Japanese switched off the same keys give another sentence. */
    @Test
    fun crossLanguageSwitchOffStopsTheConversion() {
        change { it.copy(mixed = it.mixed.copy(cross = it.mixed.cross.copy(pinyinToKanji = false))) }
        val text = type(SENTENCE_KEYS, expected = "", attempts = 1)
        assertTrue("something is committed", text.isNotEmpty())
        assertNotEquals(SENTENCE, text)
    }

    /**
     * T03-020 positive (PLAN §2.3): Japanese mode reads pinyin as a Japanese word through the
     * coordinator; `dongjing` is not romaji, so 東京 leads and space commits it.
     */
    @Test
    fun japaneseModeReadsPinyinAsJapanese() {
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = Mode.JAPANESE) } }
        assertEquals("東京", type("dongjing ", expected = "東京", enter = false))
    }

    /** PLAN §9.10 negative: with `japanese.cross.pinyin_to_kanji` off Japanese mode does not. */
    @Test
    fun japaneseModeCrossSwitchOffStopsTheConversion() {
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = Mode.JAPANESE) } }
        change { it.copy(japanese = it.japanese.copy(pinyinToKanji = false)) }
        val text = type("dongjing ", expected = "", enter = false, attempts = 1)
        assertTrue("something is committed", text.isNotEmpty())
        assertNotEquals("東京", text)
    }

    /**
     * T09 on the emulator (PLAN §5.6, §9.5): in the segment panel one piece of the sentence is
     * changed (池袋 kept as the letters typed) and the rest, に買いに行こう, is not rewritten; the
     * sentence commits as shown; backspace right after takes the commit back with the choice
     * still in place, and Enter commits it again.
     */
    @Test
    fun onePieceChangesAndTheRestStays() {
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            keys(SENTENCE_KEYS)
            openSegments()
            checkNotNull(device.wait(Until.findObject(By.text("池袋")), WAIT_MS)) { "no piece 池袋" }.click()
            checkNotNull(device.wait(Until.findObject(By.text("地代")), WAIT_MS)) { "the piece's other reading is not offered" }.click()
            val changed = "地代" + SENTENCE.removePrefix("池袋")
            checkNotNull(device.wait(Until.findObject(By.text(changed)), WAIT_MS)) { "the rest of the sentence did not stay" }.click()
            assertEquals(changed, read(scenario))
            // Undo: the letters are composed again; the chosen piece is still chosen.
            checkNotNull(device.wait(Until.findObject(By.desc(BACKSPACE)), WAIT_MS)) { "the keys did not come back" }.click()
            assertEquals(SENTENCE_KEYS, read(scenario))
            enter()
            assertEquals(changed, read(scenario))
        }
    }

    /**
     * T09 (PLAN §5.6, §9.5): "Restore input" drops the chosen piece and the sentence converts as
     * before; "Commit up to here" sends the pieces up to the chosen one to the field and the
     * rest stays composed.
     */
    @Test
    fun restoresTheInputAndCommitsUpToAPiece() {
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            keys(SENTENCE_KEYS)
            openSegments()
            checkNotNull(device.wait(Until.findObject(By.text("池袋")), WAIT_MS)) { "no piece 池袋" }.click()
            checkNotNull(device.wait(Until.findObject(By.text("chidai")), WAIT_MS)) { "the piece's letters are not offered" }.click()
            // The letters stay as typed in every sentence; the rest converts again around them.
            checkNotNull(device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("chidai.+"))), WAIT_MS)) { "the piece did not change" }
            Thread.sleep(READ_MS) // the panel has just been redrawn; tap it where it now is
            checkNotNull(device.findObject(By.text("Restore input"))) { "no way back to the input" }.click()
            checkNotNull(device.wait(Until.findObject(By.text(SENTENCE)), WAIT_MS)) { "the input did not convert afresh" }
            checkNotNull(device.wait(Until.findObject(By.text("池袋")), WAIT_MS)) { "no piece 池袋 after restoring" }.click()
            checkNotNull(device.wait(Until.findObject(By.text("Commit up to here")), WAIT_MS)) { "no partial commit" }.click()
            // The rest is still composed, now a sentence of its own: the field holds its letters
            // and choosing its conversion commits it after the piece.
            assertEquals("池袋" + SENTENCE_KEYS.removePrefix("chidai "), read(scenario))
            // The panel has settled by now (the read above waits): what is found is what is tapped.
            val offered = checkNotNull(device.findObjects(By.clazz("android.widget.TextView")).firstOrNull { it.text.orEmpty().endsWith("に行こう") }) { "the rest is not offered" }
            val text = offered.text
            offered.click()
            assertEquals("池袋$text", read(scenario))
        }
    }

    /**
     * PLAN §5.5 on the emulator: a long sentence is answered in two stages, and the later one
     * arrives: the first holds at most one sentence per language, the panel lists more.
     */
    @Test
    fun aLongSentenceGetsItsLaterStage() {
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use {
            keys(SENTENCE_KEYS)
            openSegments()
            checkNotNull(device.wait(Until.findObject(By.text(SENTENCE)), WAIT_MS)) { "the sentence is not offered" }
            // The field, the clock, "Whole sentence", the piece chips, the candidates, two actions:
            // with three candidates at most there are fewer than these.
            val pieces = 4
            val shown = device.findObjects(By.clazz("android.widget.TextView")).size
            assertTrue("only $shown texts: the later stage did not arrive", shown > 3 + pieces + 3 + 2)
        }
    }

    /** Opens the segment panel from the candidate bar's expand arrow once the conversion is shown. */
    private fun openSegments() {
        Thread.sleep(READ_MS * 3)
        val q = device.findObject(By.desc("q"))
        val res = context.resources
        val barY = q.visibleBounds.top - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.kb_pad_top) -
            res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_height) / 2
        device.click(device.displayWidth - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_expand) / 2, barY)
        checkNotNull(device.wait(Until.findObject(By.text(WHOLE)), WAIT_MS)) { "the segment panel did not open" }
    }

    /** T04 positive: an English word typed in Mixed mode stays English (original language first). */
    @Test
    fun englishWordStaysEnglish() {
        assertEquals("coffee", type("coffee", expected = "coffee"))
    }

    /** T12 positive: a field that asks for no personalized learning still converts in Mixed mode. */
    @Test
    fun noLearningFieldStillConverts() {
        assertEquals("你好", type("nihao", expected = "你好", imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
    }

    /** T12 negative: a password field types letters directly, whatever the mode. */
    @Test
    fun passwordFieldTypesLettersDirectly() {
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertEquals("nihao", type("nihao", expected = "nihao", enter = false, inputType = password))
    }

    /** Commits follow the keys in order: two words typed and committed back to back both arrive whole. */
    @Test
    fun commitsKeepTheirOrder() {
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            var text = ""
            repeat(ATTEMPTS) {
                keys("nihao")
                enter()
                keys("coffee")
                enter()
                text = read(scenario)
                if (text == "你好coffee") return
                clear(scenario)
            }
            assertEquals("你好coffee", text)
        }
    }

    /**
     * ADR-008's reopening condition, checked directly in the app's process: the coordinator opens
     * over the tables of the libraries the APK ships, maps the lexicon out of the APK and gets an
     * answer from the English dictionary's table (the keyboard's own librime session is left alone).
     */
    @Test
    fun coordinatorOpensOverTheShippedLibraries() {
        val english = context.assets.openFd(ENGLISH_DICT).use { English.open(context.applicationInfo.sourceDir, it.startOffset, it.length) }
        checkNotNull(english) { "the English dictionary did not open" }
        try {
            assertTrue("librime hands a table over", Rime.api() != 0L)
            val lexicon = context.assets.openFd(LEXICON)
            val core = lexicon.use { Core.open(rime = false, mozc = null, english = english, context.applicationInfo.sourceDir, it.startOffset, it.length) }
            checkNotNull(core) { "the coordinator did not open" }
            core.use {
                assertTrue("the lexicon is mapped from the APK", it.lexiconEntries > 0)
                val snapshot = checkNotNull(it.set(1, "coffee")) { "no snapshot" }
                assertEquals("coffee", snapshot.candidates.firstOrNull()?.text)
                // The lexicon alone (no Mozc here) cannot write Japanese; nothing may crash for it.
                assertTrue(snapshot.candidates.none { c -> c.text.isEmpty() })
            }
        } finally {
            english.close()
        }
    }

    /** Applies [update] to the settings and gives the keyboard a moment to follow the store. */
    private fun change(update: (Settings) -> Settings) = runBlocking {
        store.update(update)
        Thread.sleep(READ_MS)
    }

    /**
     * Opens a field, taps [keys] (a space taps the space bar) and then Enter unless [enter] is
     * false, and returns the field's text.
     * Notes: the first field of a run may come up before the engines opened; the typing is then
     * repeated a few times, as in FieldTypingTest.
     */
    private fun type(
        keys: String, expected: String, enter: Boolean = true,
        inputType: Int = InputType.TYPE_CLASS_TEXT, imeOptions: Int = 0, attempts: Int = ATTEMPTS,
    ): String {
        launch(inputType, imeOptions or EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            var text = ""
            repeat(attempts) {
                keys(keys)
                if (enter) enter()
                text = read(scenario)
                if (text == expected) return text
                clear(scenario)
            }
            return text
        }
    }

    private fun launch(inputType: Int, imeOptions: Int): ActivityScenario<FieldTestActivity> {
        val intent = Intent(context, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, inputType)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, imeOptions)
        return ActivityScenario.launch(intent)
    }

    /** Taps [keys] on Kotomo's keyboard once it shows. */
    private fun keys(keys: String) {
        checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }
        for (key in keys) {
            val target = if (key == ' ') By.descStartsWith(SPACE) else By.desc(key.toString())
            device.findObject(target).click()
        }
    }

    /** Lets the last key's candidates arrive, then taps Enter. */
    private fun enter() {
        Thread.sleep(READ_MS)
        device.findObject(By.descStartsWith(ENTER)).click()
        device.waitForIdle()
    }

    /** Empties the field between attempts and lets the keyboard settle. */
    private fun clear(scenario: ActivityScenario<FieldTestActivity>) {
        scenario.onActivity { it.findViewById<EditText>(FieldTestActivity.FIELD_ID).setText("") }
        Thread.sleep(WAIT_MS)
    }

    /** Whether the field still holds an uncommitted composition. */
    private fun composing(scenario: ActivityScenario<FieldTestActivity>): Boolean {
        var start = -1
        scenario.onActivity { start = BaseInputConnection.getComposingSpanStart(it.findViewById<EditText>(FieldTestActivity.FIELD_ID).text) }
        return start >= 0
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
        /** The space and Enter keys' descriptions start with these (R.string.key_space, key_enter; English). */
        const val SPACE = "Space"
        const val ENTER = "Enter"
        const val BACKSPACE = "Delete"
        const val WHOLE = "Whole sentence"
        /** The assets the keyboard maps in place (KotomoService.ENGLISH_DICT, LEXICON). */
        const val ENGLISH_DICT = "main_en_us.dict"
        const val LEXICON = "cross.klx"
        /** Corpus line T07-001. */
        const val SENTENCE_KEYS = "chidai ni kai ni ikou"
        const val SENTENCE = "池袋に買いに行こう"
        const val ATTEMPTS = 3
        const val WAIT_MS = 3000L
        const val READS = 3
        const val READ_MS = 300L
    }
}
