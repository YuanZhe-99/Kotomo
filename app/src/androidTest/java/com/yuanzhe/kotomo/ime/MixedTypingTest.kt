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

    /**
     * PLAN §14, ADR-013 (`privacy.learned_encrypted`): with the switch on, what a commit teaches
     * is written sealed with the Keystore key and the plain file is gone; with it off again the
     * plain file is back and the sealed one gone, and the reading is still known.
     */
    @Test
    fun learnedReadingsAreSealedWhileThePersonChoseSo() {
        val learning = MixedLearning.get(context)
        val plain = java.io.File(context.filesDir, "keyboard/learned.json")
        val sealed = java.io.File(context.filesDir, "keyboard/learned.kenc")
        change { it.copy(learnedEncrypted = true) }
        assertEquals("你好", type("nihao", expected = "你好"))
        learning.flush()
        assertTrue("the readings are not sealed", sealed.isFile && !plain.exists())
        assertFalse("the sealed file shows the reading", "nihao" in String(sealed.readBytes()))
        change { it.copy(learnedEncrypted = false) }
        Thread.sleep(WAIT_MS)
        learning.flush()
        assertTrue("the plain file did not come back", plain.isFile && !sealed.exists())
        assertEquals(setOf("nihao" to "你好"), learning.current.readings.keys)
    }

    /** T04 positive: pinyin typed in Mixed mode converts to Chinese; Enter commits the best candidate. */
    @Test
    fun enterCommitsTheBestCandidate() {
        assertEquals("你好", type("nihao", expected = "你好"))
    }

    /**
     * Feedback C3: the separator key's apostrophe is the same boundary as a space for the Japanese
     * reading (corpus C3-001…003), at every word or at some only. Negative: に and かい are not
     * joined into 二階 across it (C3-N01).
     */
    @Test
    fun separatorKeyIsABoundaryLikeSpace() {
        assertEquals(SENTENCE, type(SENTENCE_KEYS.replace(' ', '\''), expected = SENTENCE))
        assertEquals(SENTENCE, type("chidai'ni'kainiikou", expected = SENTENCE))
        val part = type("chidai'ni'kai", expected = "", attempts = 1)
        assertTrue("got $part", part.startsWith("池袋") && part != "池袋二階")
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

    /** Feedback B6 positive: initials between whole syllables are pinyin (`woxhni` → 我喜欢你). */
    @Test
    fun abbreviatedPinyinConverts() {
        assertEquals("我喜欢你", type("woxhni", expected = "我喜欢你"))
    }

    /** Feedback B6 negative: with `mixed.abbreviation` off the same keys are not read as pinyin. */
    @Test
    fun abbreviatedPinyinOffStopsTheConversion() {
        change { it.copy(mixed = it.mixed.copy(abbreviation = false)) }
        val text = type("woxhni", expected = "", attempts = 1)
        assertTrue("something is committed", text.isNotEmpty())
        assertNotEquals("我喜欢你", text)
    }

    /** Feedback B4 positive: with `japanese.fuzzy.voiced` on, Mixed mode reads `wadashi` as 私. */
    @Test
    fun japaneseFuzzyRuleFindsTheWordInMixedMode() {
        change { it.copy(japaneseFuzzy = setOf(JapaneseFuzzyRule.VOICED)) }
        assertEquals("私", type("wadashi", expected = "私"))
    }

    /** Feedback B4 negative: the rules are off by default, and then `wadashi` is not 私. */
    @Test
    fun japaneseFuzzyRuleOffLeavesTheReadingAsTyped() {
        val text = type("wadashi", expected = "", attempts = 1)
        assertTrue("something is committed", text.isNotEmpty())
        assertNotEquals("私", text)
    }

    /**
     * Feedback B4 (B3) positive: with `japanese.fuzzy.cu` on, Japanese mode offers 月 for `cuki`
     * among its first three candidates; Mozc's own reading (くき) stays first.
     */
    @Test
    fun japaneseFuzzyRuleFindsTheWordInJapaneseMode() {
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = Mode.JAPANESE) } }
        change { it.copy(japaneseFuzzy = setOf(JapaneseFuzzyRule.CU)) }
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            val firstThree = (1..3).map { presses ->
                clear(scenario)
                keys("cuki" + " ".repeat(presses))
                enter()
                read(scenario)
            }
            assertTrue("月 is not among $firstThree", "月" in firstThree)
            assertNotEquals("the reading as typed stays first", "月", firstThree.first())
        }
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

    /**
     * Feedback A2 positive (PLAN §2.3, T03-021): Japanese mode reads an English word as its
     * loanword; `coffee` has コーヒー among its first three candidates although Mozc reads the
     * letters as kana (こっふぇえ). Space walks the candidates and Enter commits the one shown.
     */
    @Test
    fun japaneseModeReadsEnglishAsLoanword() {
        KeyboardMemory.get(context).update { m -> m.withApp(context.packageName) { it.copy(pinned = Mode.JAPANESE) } }
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            val firstThree = (1..3).map { presses ->
                clear(scenario)
                keys("coffee" + " ".repeat(presses))
                enter()
                read(scenario)
            }
            assertTrue("コーヒー is not among $firstThree", "コーヒー" in firstThree)
        }
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
     * changed (池袋 → 地代), the panel moves on to the next piece (C6) and the rest, に買いに行こう,
     * is not rewritten; the sentence commits as shown; with `keys.backspace_restores_commit` on,
     * backspace right after takes the commit back with the choice still in place, and Enter
     * commits it again.
     */
    @Test
    fun onePieceChangesAndTheRestStays() {
        change { it.copy(backspaceRestores = true) }
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            keys(SENTENCE_KEYS)
            openSegments()
            checkNotNull(device.wait(Until.findObject(By.text("池袋")), WAIT_MS)) { "no piece 池袋" }.click()
            checkNotNull(device.wait(Until.findObject(By.text("地代")), WAIT_MS)) { "the piece's other reading is not offered" }.click()
            checkNotNull(device.wait(Until.findObject(By.textStartsWith(SEGMENT_2)), WAIT_MS)) { "the panel did not move on to the next piece" }
            device.findObject(By.text(WHOLE)).click()
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
     * B9 (device feedback 2026-11-13): by default Backspace right after a commit deletes one
     * character (大家 → 大, nothing composed); with `keys.backspace_restores_commit` on, it brings
     * `dajia` back as a composition.
     */
    @Test
    fun backspaceAfterACommitDeletesUnlessChosen() {
        change { it.copy(mixed = it.mixed.copy(keys = it.mixed.keys.copy(space = SpaceKey.COMMIT_FIRST))) }
        assertEquals("大", commitThenBackspace("dajia", "大家", composed = false))
        change { it.copy(backspaceRestores = true) }
        assertEquals("dajia", commitThenBackspace("dajia", "大家", composed = true))
    }

    /**
     * Types [keys], commits the first candidate (expected to be [word]) with Space, presses
     * Backspace once and returns the field's text, checking whether it is still [composed].
     */
    private fun commitThenBackspace(keys: String, word: String, composed: Boolean): String {
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            keys(keys)
            Thread.sleep(READ_MS * 3)
            keys(" ")
            assertEquals(word, read(scenario))
            device.findObject(By.desc(BACKSPACE)).click()
            val text = read(scenario)
            assertEquals("whether the field is composing after Backspace", composed, composing(scenario))
            return text
        }
    }

    /**
     * C6 (device feedback 2026-11-14): choosing the last piece's reading in the segment panel
     * brings the keys back; the pieces before it stay as they are.
     */
    @Test
    fun theLastPieceReturnsToTheKeys() {
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            keys(SENTENCE_KEYS)
            openSegments()
            checkNotNull(device.wait(Until.findObject(By.text("行こう")), WAIT_MS)) { "no piece 行こう" }.click()
            checkNotNull(device.wait(Until.findObject(By.text("ikou")), WAIT_MS)) { "the piece's letters are not offered" }.click()
            assertTrue("the panel stayed open after the last piece", device.wait(Until.gone(By.text(WHOLE)), WAIT_MS))
            checkNotNull(device.wait(Until.findObject(By.desc(BACKSPACE)), WAIT_MS)) { "the keys did not come back" }
            enter()
            val text = read(scenario)
            assertTrue("the last piece is not kept as typed: $text", text.endsWith("ikou") && text.startsWith("池袋"))
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
            checkNotNull(device.wait(Until.findObject(By.textStartsWith(SEGMENT_2)), WAIT_MS)) { "the panel did not move on to the next piece" }
            device.findObject(By.text(WHOLE)).click()
            // The letters stay as typed in every sentence; the rest converts again around them.
            checkNotNull(device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("chidai.+"))), WAIT_MS)) { "the piece did not change" }
            Thread.sleep(READ_MS) // the panel has just been redrawn; tap it where it now is
            checkNotNull(device.findObject(By.text("Restore input"))) { "no way back to the input" }.click()
            checkNotNull(device.wait(Until.findObject(By.text(SENTENCE)), WAIT_MS)) { "the input did not convert afresh" }
            checkNotNull(device.wait(Until.findObject(By.text("池袋")), WAIT_MS)) { "no piece 池袋 after restoring" }.click()
            checkNotNull(device.wait(Until.findObject(By.textStartsWith("Segment 1 of")), WAIT_MS)) { "piece 池袋 did not focus" }
            checkNotNull(device.wait(Until.findObject(By.text("Commit up to here")), WAIT_MS)) { "no partial commit" }.click()
            assertTrue("池袋 stayed in the panel", device.wait(Until.gone(By.text("池袋")), WAIT_MS))
            // The rest is still composed, now a sentence of its own: the field holds its letters
            // and choosing its conversion commits it after the piece.
            assertEquals("池袋" + SENTENCE_KEYS.removePrefix("chidai "), read(scenario))
            // The panel may still be redrawn for the rest's conversion: a view that went away
            // while the list was read means "look again", until the usual wait has passed.
            var text = ""
            val rest = By.text(java.util.regex.Pattern.compile(".*に行こう"))
            val deadline = android.os.SystemClock.uptimeMillis() + WAIT_MS
            while (text.isEmpty() && android.os.SystemClock.uptimeMillis() < deadline) {
                runCatching { device.wait(Until.findObject(rest), READ_MS)?.also { val shown = it.text; it.click(); text = shown } }
            }
            assertTrue("the rest is not offered", text.isNotEmpty())
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

    /**
     * Selecting a Mixed Pinyin prefix inside the expanded panel commits only the prefix, keeps
     * the panel open on the remaining composition, and returns to the keys after the remainder
     * commits.
     */
    @Test
    fun mixedPrefixCommitKeepsPanelOpenForRemainder() {
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            keys("yuanzhe")
            openSegments()
            checkNotNull(device.wait(Until.findObject(By.text("元")), WAIT_MS)) { "prefix 元 not offered" }.click()
            checkNotNull(device.wait(Until.findObject(By.text("哲")), WAIT_MS)) { "remainder zhe did not refresh in panel" }
            assertNotEquals("panel closed after prefix commit", null, device.findObject(By.text(WHOLE)))
            assertTrue("composition cleared prematurely", composing(scenario))
            device.findObject(By.text("哲")).click()
            assertTrue("panel stayed open after committing remainder", device.wait(Until.gone(By.text(WHOLE)), WAIT_MS))
            assertEquals("元哲", read(scenario))
            assertFalse("composition still active", composing(scenario))
        }
    }

    /** Long-pressing a candidate inside the expanded segment panel shows its reading and source above the panel. */
    @Test
    fun expandedPanelLongPressShowsCandidateDetails() {
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use {
            keys("coffee")
            openSegments()
            val candidate = checkNotNull(device.wait(Until.findObject(By.text("コーヒー")), WAIT_MS)) { "coffee loanword not offered" }
            candidate.longClick()
            checkNotNull(device.wait(Until.findObject(By.descStartsWith("こーひー")), WAIT_MS)) { "details did not appear on long press" }
        }
    }

    /** Mixed mode renders `中` / `EN` / `日` candidate badges when `mixed.language_tags` is on and hides them when off. */
    @Test
    fun mixedLanguageBadgesShowAndToggle() {
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use {
            keys("ai")
            openSegments()
            checkNotNull(device.wait(Until.findObject(By.text("中")), WAIT_MS)) { "Chinese badge missing" }
            checkNotNull(device.wait(Until.findObject(By.text("EN")), WAIT_MS)) { "English badge missing" }
            checkNotNull(device.wait(Until.findObject(By.text("日")), WAIT_MS)) { "Japanese badge missing" }
            change { it.copy(mixed = it.mixed.copy(languageTags = false)) }
            assertTrue("English badge stayed after disabling switch", device.wait(Until.gone(By.text("EN")), WAIT_MS))
            assertTrue("Chinese badge stayed after disabling switch", device.wait(Until.gone(By.text("中")), WAIT_MS))
        }
    }

    /**
     * Stage 1 (v0.1.3): live segment strip appears for single words and sentences, tapping a chip
     * opens the grouped panel with `Kana & original`, and tapping katakana locks it via kLockText.
     */
    @Test
    fun segmentStripAndGroupedPanelLockKatakana() {
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            keys("chidai")
            checkNotNull(device.wait(Until.findObject(By.text("痴呆")), WAIT_MS)) { "single-word strip chip missing" }.click()
            checkNotNull(device.wait(Until.findObject(By.text("By pinyin chidai")), WAIT_MS)) { "group header missing" }
            checkNotNull(device.wait(Until.findObject(By.text("Chinese & Japanese · いけぶくろ")), WAIT_MS)) { "card subtitle missing" }
            checkNotNull(device.wait(Until.findObject(By.text("Kana & original")), WAIT_MS)) { "kana group missing" }
            checkNotNull(device.wait(Until.findObject(By.text("イケブクロ")), WAIT_MS)) { "katakana pill missing" }.click()
            checkNotNull(device.wait(Until.findObject(By.desc(BACKSPACE)), WAIT_MS)) { "keys did not return on single word" }
            enter()
            assertEquals("イケブクロ", read(scenario))
        }
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use {
            keys(SENTENCE_KEYS)
            checkNotNull(device.wait(Until.findObject(By.text("池袋")), WAIT_MS)) { "sentence strip chip 池袋 missing" }.click()
            checkNotNull(device.wait(Until.findObject(By.textStartsWith("Segment 1 of")), WAIT_MS)) { "focused subtitle missing" }
            checkNotNull(device.wait(Until.findObject(By.text("イケブクロ")), WAIT_MS)) { "sentence katakana pill missing" }.click()
            checkNotNull(device.wait(Until.findObject(By.textStartsWith(SEGMENT_2)), WAIT_MS)) { "panel did not advance to segment 2" }
            device.findObject(By.text(WHOLE)).click()
            checkNotNull(device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("イケブクロ.+"))), WAIT_MS)) { "locked katakana missing from whole sentence" }
        }
    }

    /** Stage 1 (v0.1.3): `中日` badge appears on shared Han candidates like 日本 while 你好 stays 中. */
    @Test
    fun sharedZhJaBadgeAppearsOnSharedKanjiOnly() {
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use {
            keys("nihao")
            openSegments()
            checkNotNull(device.wait(Until.findObject(By.text("中")), WAIT_MS)) { "你好 badge 中 missing" }
            assertEquals(null, device.findObject(By.text("中日")))
        }
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use {
            keys("riben")
            openSegments()
            checkNotNull(device.wait(Until.findObject(By.text("日本")), WAIT_MS)) { "日本 candidate missing" }
            checkNotNull(device.wait(Until.findObject(By.text("中日")), WAIT_MS)) { "shared 中日 badge missing for 日本" }
        }
    }

    /**
     * Stage 1 (v0.1.3): long-pressing a candidate with differing Chinese/Japanese Han glyph opens
     * the conflict-free action bar with `→ 日` or `→ 中` and switches script on tap.
     */
    @Test
    fun longPressSwitchesBetweenChineseAndJapaneseScripts() {
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            val q = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }.visibleBounds
            keys("tianqi")
            Thread.sleep(READ_MS * 3)
            val dp = context.resources.displayMetrics.density
            val y = (q.top - 32 * dp).toInt()
            device.swipe((q.left + 40 * dp).toInt(), y, (q.left + 40 * dp).toInt(), y, 120)
            checkNotNull(device.wait(Until.findObject(By.text("→ 日 天気")), WAIT_MS)) { "missing → 日 天気 pill" }.click()
            assertEquals("天気", read(scenario))
        }
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            keys("tenki")
            openSegments()
            checkNotNull(device.wait(Until.findObject(By.text("天気")), WAIT_MS)) { "天気 candidate missing" }
            device.findObjects(By.text("天気")).last().longClick()
            checkNotNull(device.wait(Until.findObject(By.text("→ 中 天气")), WAIT_MS)) { "missing → 中 天气 pill" }.click()
            assertEquals("天气", read(scenario))
        }
    }

    /**
     * Stage 2 (v0.1.5): Mixed whole-sentence forms via SegmentStrip's trailing Forms chip (`Humble`
     * -> `池袋に買いに参ります`) and per-segment locking inside SegmentPanel (`Polite` -> `行きます`).
     */
    @Test
    fun mixedFormsWholeSentenceAndSegmentLock() {
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            var matched = false
            repeat(ATTEMPTS) {
                if (matched) return@repeat
                keys(SENTENCE_KEYS)
                Thread.sleep(READ_MS * 3)
                if (device.wait(Until.findObject(By.text("池袋")), WAIT_MS) != null &&
                    device.wait(Until.findObject(By.text("Forms")), WAIT_MS) != null
                ) {
                    matched = true
                } else {
                    clear(scenario)
                }
            }
            check(matched) { "Mixed SegmentStrip 池袋 / Forms missing" }
            checkNotNull(device.wait(Until.findObject(By.text("Forms")), WAIT_MS)) { "Mixed SegmentStrip Forms chip missing" }.click()
            checkNotNull(device.wait(Until.findObject(By.text("行く")), WAIT_MS)) { "Mixed forms header missing" }
            checkNotNull(device.wait(Until.findObject(By.text("Humble")), WAIT_MS)) { "Humble tab missing" }.click()
            checkNotNull(device.wait(Until.findObject(By.text("参ります")), WAIT_MS)) { "参ります card missing" }.click()
            assertEquals("池袋に買いに参ります", read(scenario))
            assertFalse("whole-sentence form left composing", composing(scenario))
        }
        launch(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION).use { scenario ->
            keys(SENTENCE_KEYS)
            openSegments()
            checkNotNull(device.wait(Until.findObject(By.text("行こう")), WAIT_MS)) { "piece 行こう missing" }.click()
            checkNotNull(device.wait(Until.findObject(By.text("Forms")), WAIT_MS)) { "SegmentPanel Forms button missing" }.click()
            checkNotNull(device.wait(Until.findObject(By.text("Polite")), WAIT_MS)) { "Polite tab missing" }.click()
            checkNotNull(device.wait(Until.findObject(By.text("行きます")), WAIT_MS)) { "行きます card missing" }.click()
            checkNotNull(device.wait(Until.findObject(By.desc(BACKSPACE)), WAIT_MS)) { "keys did not return after final segment lock" }
            enter()
            assertEquals("池袋に買いに行きます", read(scenario))
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
            // ' is the separator key, in Shift's place while a composition is open (B1).
            val target = if (key == ' ') By.descStartsWith(SPACE) else By.desc(if (key == '\'') Layout.SEPARATOR else key.toString())
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
        /** The segment panel's position line for the second piece (R.string.segment_position, English). */
        const val SEGMENT_2 = "Segment 2 of"
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
