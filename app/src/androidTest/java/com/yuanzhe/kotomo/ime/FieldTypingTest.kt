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
import com.yuanzhe.kotomo.fieldtest.FieldTestActivity
import com.yuanzhe.kotomo.settings.EnterKey
import com.yuanzhe.kotomo.settings.OneHanded
import com.yuanzhe.kotomo.settings.SpaceKey
import com.yuanzhe.kotomo.settings.Settings
import com.yuanzhe.kotomo.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** Positive: an ordinary field converts pinyin in Chinese mode; space commits the first candidate. */
    @Test
    fun ordinaryFieldConvertsPinyin() {
        inMode(Mode.CHINESE) { assertEquals("你好", typeInto(InputType.TYPE_CLASS_TEXT, 0, "nihao", expected = "你好")) }
    }

    /**
     * B1 (device feedback 2026-11-13): while pinyin is composed in Chinese and Mixed mode, Shift
     * becomes the syllable separator, so `xi'an` gives 西安 on the full keyboard. Negative: with
     * `keys.shift_separator` off, the key stays Shift and no separator is offered.
     */
    @Test
    fun shiftSeparatesSyllablesWhileComposing() {
        inMode(Mode.CHINESE) { assertEquals("西安", typeInto(InputType.TYPE_CLASS_TEXT, 0, "xi'an", expected = "西安")) }
        // Mixed mode's space is a boundary mark: Enter commits the best candidate there.
        inMode(Mode.MIXED) { assertEquals("西安", typeInto(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION, "xi'an", expected = "西安", space = false, enter = true)) }
        inMode(Mode.CHINESE) {
            withSettings({ it.copy(shiftSeparator = false) }) {
                typeInto(InputType.TYPE_CLASS_TEXT, 0, "xi", expected = "xi", space = false) {
                    assertFalse("the separator replaced Shift with the switch off", device.hasObject(By.desc(Layout.SEPARATOR)))
                }
            }
        }
    }

    /**
     * A10 (device feedback 2026-11-13, second round): the separator takes Shift's place from the
     * first letter on and gives it back as the last letter is deleted, with no further key. Chinese
     * mode used to follow librime's previous answer, one key late. Chinese and Mixed: after `x`
     * the key is 分词; after one backspace it is Shift again. 9-key: the separator stays where it is.
     */
    @Test
    fun separatorFollowsTheCompositionAtOnce() {
        fun keyIs(desc: String, gone: String) {
            assertTrue("no $desc key", device.wait(Until.hasObject(By.desc(desc)), WAIT_MS))
            assertFalse("$gone is still shown", device.hasObject(By.desc(gone)))
        }
        withSettings({ it.copy(chineseNineKey = false) }) {
            for (mode in listOf(Mode.CHINESE, Mode.MIXED)) inMode(mode) {
                typeInto(InputType.TYPE_CLASS_TEXT, 0, "x", expected = "", space = false) {
                    keyIs(Layout.SEPARATOR, SHIFT)
                    device.findObject(By.desc(BACKSPACE)).click()
                    keyIs(SHIFT, Layout.SEPARATOR)
                }
            }
        }
        withSettings({ it.copy(chineseNineKey = true) }) {
            inMode(Mode.CHINESE) {
                val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
                    .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
                ActivityScenario.launch<FieldTestActivity>(intent).use {
                    checkNotNull(device.wait(Until.findObject(By.desc("WXYZ")), WAIT_MS)) { "no 9-key layout" }.click()
                    keyIs(Layout.SEPARATOR, SHIFT)
                    device.findObject(By.desc(BACKSPACE)).click()
                    keyIs(Layout.SEPARATOR, SHIFT)
                }
            }
        }
    }

    /**
     * A11 (device feedback 2026-11-13, second round, checklist 7.12): Mixed `yu`, expanded and
     * scrolled to the end, shows no candidate the font cannot draw (librime lists rare
     * characters from the CJK extensions). Positive: 语 is still offered and picking it types 语.
     */
    @Test
    fun expandedCandidatesShowOnlyWhatTheFontDraws() {
        val paint = android.graphics.Paint()
        val ideograph = java.util.regex.Pattern.compile(".*\\p{IsIdeographic}.*")
        inMode(Mode.MIXED) {
            expanded("yu") { scenario ->
                Thread.sleep(READ_MS * 3)
                val q = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "the keys are not shown" }
                val res = instrumentation.targetContext.resources
                val barY = q.visibleBounds.top - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.kb_pad_top) -
                    res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_height) / 2
                device.click(device.displayWidth - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_expand) / 2, barY)
                checkNotNull(device.wait(Until.findObject(By.desc(BACK)), WAIT_MS)) { "the expanded panel did not open" }
                checkNotNull(device.wait(Until.findObject(By.text("语")), WAIT_MS)) { "no 语" }
                val seen = LinkedHashSet<String>()
                Thread.sleep(READ_MS * 3)
                // The list may still be redrawn (the later stage): a cell gone meanwhile is skipped.
                val panel = device.findObjects(By.text(ideograph)).mapNotNull { runCatching { it.visibleBounds }.getOrNull() }
                val top = panel.minOf { it.top }
                val bottom = panel.maxOf { it.bottom }
                // Scrolls until three swipes in a row show nothing new: the end of the list.
                var idle = 0
                for (i in 0 until SCROLLS) {
                    val before = seen.size
                    seen += device.findObjects(By.text(ideograph)).mapNotNull { runCatching { it.text }.getOrNull() }
                    idle = if (seen.size == before) idle + 1 else 0
                    if (idle == 3) break
                    // From inside the cells (the collapse button sits below them) up by most of a page.
                    device.swipe(device.displayWidth / 2, bottom - (bottom - top) / 4, device.displayWidth / 2, top, SWIPE_STEPS)
                    Thread.sleep(READ_MS)
                }
                assertTrue("only ${seen.size} candidates were listed", seen.size > SCROLLED_PAST)
                val boxes = seen.filter { t -> t.codePoints().anyMatch { Character.isIdeographic(it) && !paint.hasGlyph(String(Character.toChars(it))) } }
                assertTrue("shown without a glyph: ${boxes.joinToString(" ") { t -> t.codePoints().toArray().joinToString("+") { "U+%X".format(it) } }}", boxes.isEmpty())
                device.findObject(By.desc(BACK)).click()
                checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS))
                pickExpanded("语")
                assertEquals("语", read(scenario))
            }
        }
    }

    /**
     * A1 (device feedback 2026-11-13, checklist 2.6): a pick in the panel the bar's expand arrow
     * opens returns to the keys. Chinese: 你 alone keeps 好's pinyin composed with the keys back,
     * then 好 finishes 你好; Mixed: the whole sentence commits with the keys back.
     */
    @Test
    fun expandedCandidatesReturnToTheKeysAfterAPick() {
        withSettings({ it.copy(chineseNineKey = false) }) {
            inMode(Mode.CHINESE) {
                expanded("nihao") { scenario ->
                    pickExpanded("你")
                    assertTrue("the composition ended after 你", composing(scenario))
                    pickExpanded("好")
                    assertEquals("你好", read(scenario))
                }
            }
            inMode(Mode.MIXED) {
                expanded("nihao") { scenario ->
                    pickExpanded("你好")
                    assertEquals("你好", read(scenario))
                }
            }
        }
    }

    /**
     * B8 (device feedback 2026-11-13, checklist 27.2): with 日本語 chosen as Kotomo's interface
     * language the keyboard opens in Japanese (削除 for backspace) on an English system; following
     * the system again, it opens in English.
     */
    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    fun keyboardFollowsTheChosenInterfaceLanguage() {
        val manager = instrumentation.targetContext.getSystemService(android.app.LocaleManager::class.java)
        val before = manager.applicationLocales
        fun opened(locales: android.os.LocaleList, delete: String) {
            instrumentation.runOnMainSync { manager.applicationLocales = locales }
            val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
                .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
                .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
            ActivityScenario.launch<FieldTestActivity>(intent).use {
                checkNotNull(device.wait(Until.findObject(By.desc(delete)), WAIT_MS)) { "the keyboard is not in ${locales.toLanguageTags()}" }
            }
        }
        try {
            opened(android.os.LocaleList.forLanguageTags("ja"), "削除")
            opened(android.os.LocaleList.getEmptyLocaleList(), BACKSPACE)
        } finally {
            instrumentation.runOnMainSync { manager.applicationLocales = before }
        }
    }

    /** Opens a plain text field, taps [letters] and runs [block] with the field. */
    private fun expanded(letters: String, block: (ActivityScenario<FieldTestActivity>) -> Unit) {
        val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }
            letters.forEach { device.findObject(By.desc(it.toString())).click() }
            block(scenario)
        }
    }

    /**
     * Opens the expand arrow's panel once the candidates are shown, picks [text] there and checks
     * that the keys came back in the panel's place.
     */
    private fun pickExpanded(text: String) {
        Thread.sleep(READ_MS * 3)
        val q = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "the keys are not shown" }
        val res = instrumentation.targetContext.resources
        val barY = q.visibleBounds.top - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.kb_pad_top) -
            res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_height) / 2
        device.click(device.displayWidth - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_expand) / 2, barY)
        checkNotNull(device.wait(Until.findObject(By.desc(BACK)), WAIT_MS)) { "the expanded panel did not open" }
        checkNotNull(device.wait(Until.findObject(By.text(text)), WAIT_MS)) { "no $text" }
        // The segment panel lists the sentence's pieces above its candidates: take the lowest.
        device.findObjects(By.text(text)).maxBy { it.visibleBounds.top }.click()
        assertTrue("the panel stayed open after picking $text", device.wait(Until.gone(By.desc(BACK)), WAIT_MS))
        checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "the keys did not come back after picking $text" }
    }

    /**
     * T13-quick (PLAN §10.4, §10.8): v then rq offers today's date first (Space types it); with the
     * quick input off, vrq goes to librime and the date is not typed.
     */
    @Test
    fun quickInputTypesTheDate() {
        val today = java.time.LocalDate.now().let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" }
        inMode(Mode.CHINESE) {
            assertEquals(today, typeInto(InputType.TYPE_CLASS_TEXT, 0, "vrq", expected = today))
            withSettings({ it.copy(quickInput = false) }) {
                assertTrue(typeInto(InputType.TYPE_CLASS_TEXT, 0, "vrq", expected = today) != today)
            }
        }
    }

    /**
     * T13-quick, C1 (PLAN §10.4): the digits after v come from the symbol page or from a long press
     * on a letter, and the query survives both: `v1234` → 一千二百三十四, `v1+2*3` → 7. Negative:
     * a long-pressed digit with no v before it is typed as a digit.
     */
    @Test
    fun quickInputTakesDigitsFromTheSymbolPageAndLongPress() {
        inMode(Mode.CHINESE) {
            val viaPage = typeInto(InputType.TYPE_CLASS_TEXT, 0, "v", expected = "一千二百三十四") {
                key("Symbols")
                "1234".forEach { key(it.toString()) }
                key("Letters")
            }
            assertEquals("一千二百三十四", viaPage)
            val sum = typeInto(InputType.TYPE_CLASS_TEXT, 0, "v", expected = "7") {
                key("Symbols")
                "1+2".forEach { key(it.toString()) }
                key("Letters")
                // The Chinese symbol page has no *: it is z's long press.
                "ze".forEach { device.findObject(By.desc(it.toString())).longClick() }
                Thread.sleep(READ_MS)
            }
            assertEquals("7", sum)
            // q w e r carry 1 2 3 4 in their corners.
            val held = typeInto(InputType.TYPE_CLASS_TEXT, 0, "v", expected = "一千二百三十四") {
                "qwer".forEach { device.findObject(By.desc(it.toString())).longClick() }
                Thread.sleep(READ_MS)
            }
            assertEquals("一千二百三十四", held)
            val plain = typeInto(InputType.TYPE_CLASS_TEXT, 0, "n", expected = "你1", space = false) {
                device.findObject(By.descStartsWith(SPACE)).click()
                Thread.sleep(READ_MS)
                device.findObject(By.desc("q")).longClick()
                Thread.sleep(READ_MS)
            }
            assertEquals("你1", plain)
        }
    }

    /** T13-strokes (PLAN §10.4): u then h s looks up 十 by its strokes. */
    @Test
    fun strokeLookupTypesACharacter() {
        inMode(Mode.CHINESE) { assertEquals("十", typeInto(InputType.TYPE_CLASS_TEXT, 0, "uhs", expected = "十")) }
    }

    /** T12 positive: a field asking for no personalized learning still converts Chinese. */
    @Test
    fun noLearningFieldStillConvertsPinyin() {
        // Such a field reads nothing remembered per app, not even a fixed mode, so Chinese mode
        // comes from the default mode here.
        withSettings({ it.copy(defaultMode = Mode.CHINESE) }) {
            val text = typeInto(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, "nihao", expected = "你好")
            assertEquals("你好", text)
        }
    }

    /** T12 negative: a password field gets direct typing, never a conversion. */
    @Test
    fun passwordFieldTypesLettersDirectly() {
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertEquals("nihao", typeInto(password, 0, "nihao", expected = "nihao", space = false))
    }

    /**
     * T03 positive (ADR-010, `japanese.space` = next): space converts without committing, a
     * second space shows another candidate, backspace returns to the kana, and Enter commits the
     * candidate shown.
     */
    @Test
    fun japaneseSpaceConvertsAndEnterCommits() {
        inMode(Mode.JAPANESE) {
            val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
                .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
                .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
                checkNotNull(device.wait(Until.findObject(By.desc("n")), WAIT_MS)) { "Kotomo's keys did not appear" }
                "nihon".forEach { device.findObject(By.desc(it.toString())).click() }
                Thread.sleep(READ_MS * 3)
                device.findObject(By.descStartsWith(SPACE)).click()
                assertEquals("日本", read(scenario))
                assertTrue("space committed the candidate", composing(scenario))
                device.findObject(By.descStartsWith(SPACE)).click()
                val second = read(scenario)
                assertTrue("a second space showed $second", second.isNotEmpty() && second != "日本" && composing(scenario))
                device.findObject(By.desc(BACKSPACE)).click()
                assertEquals("にほん", read(scenario))
                device.findObject(By.descStartsWith(SPACE)).click()
                device.findObject(By.descStartsWith(ENTER)).click()
                assertEquals("日本", read(scenario))
                assertFalse("Enter left the candidate uncommitted", composing(scenario))
            }
        }
    }

    /** `japanese.space` = commit_first: space commits the first candidate at once. */
    @Test
    fun japaneseSpaceCommitsWhenThePersonChoseSo() {
        inMode(Mode.JAPANESE) {
            withSettings({ it.copy(japaneseKeys = it.japaneseKeys.copy(space = SpaceKey.COMMIT_FIRST)) }) {
                assertEquals("日本", typeInto(InputType.TYPE_CLASS_TEXT, 0, "nihon", expected = "日本", committed = true))
            }
        }
    }

    /** `japanese.enter` = first: Enter commits the first candidate, not the kana. */
    @Test
    fun japaneseEnterCommitsTheFirstCandidateWhenThePersonChoseSo() {
        inMode(Mode.JAPANESE) {
            withSettings({ it.copy(japaneseKeys = it.japaneseKeys.copy(enter = EnterKey.FIRST)) }) {
                val text = typeInto(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_ENTER_ACTION, "nihon", expected = "日本", space = false, enter = true)
                assertEquals("日本", text)
            }
        }
    }

    /**
     * `chinese.space` = next and `chinese.enter` (ADR-010): space shows the first candidate
     * without committing it and Enter commits it; without a space Enter commits the letters, or
     * the first candidate when the person chose so.
     */
    @Test
    fun chineseSpaceAndEnterFollowTheSettings() {
        inMode(Mode.CHINESE) {
            val enter = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            assertEquals("nihao", typeInto(InputType.TYPE_CLASS_TEXT, enter, "nihao", expected = "nihao", space = false, enter = true))
            withSettings({ it.copy(chineseKeys = it.chineseKeys.copy(enter = EnterKey.FIRST)) }) {
                assertEquals("你好", typeInto(InputType.TYPE_CLASS_TEXT, enter, "nihao", expected = "你好", space = false, enter = true, committed = true))
            }
            withSettings({ it.copy(chineseKeys = it.chineseKeys.copy(space = SpaceKey.NEXT)) }) {
                assertEquals("你好", typeInto(InputType.TYPE_CLASS_TEXT, enter, "nihao", expected = "你好", committed = false))
                assertEquals("你好", typeInto(InputType.TYPE_CLASS_TEXT, enter, "nihao", expected = "你好", enter = true, committed = true))
            }
        }
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
     * PLAN §8.2, §5.6 (T03): the segment panel. `watashihanihonjin` converts as 私は + 日本人; the
     * expand arrow opens the segments; Shorter splits 私は into 私 + は, Longer joins them again,
     * Shorter once more and choosing 渡し for 私 keeps the panel open on the next segment, は (C6),
     * and keeps は and 日本人 (a chosen segment never rewrites the others); choosing the last
     * segment's candidate brings the keys back, and Enter commits the conversion as shown.
     */
    @Test
    fun japaneseSegmentPanelShortensLengthensAndCommits() {
        inMode(Mode.JAPANESE) {
            val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
                .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
                .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
                val q = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }
                "watashihanihonjin".forEach { device.findObject(By.desc(it.toString())).click() }
                Thread.sleep(READ_MS * 3)
                // The expand arrow is drawn at the candidate bar's end, right above the keys.
                val res = instrumentation.targetContext.resources
                val barY = q.visibleBounds.top - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.kb_pad_top) -
                    res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_height) / 2
                val arrow = device.displayWidth - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_expand) / 2
                device.click(arrow, barY)
                checkNotNull(device.wait(Until.findObject(By.text(WHOLE)), WAIT_MS)) { "the segment panel did not open" }
                tap("私は")
                tap(SHORTER)
                checkNotNull(device.wait(Until.findObject(By.text("私")), WAIT_MS)) { "Shorter did not split 私は" }
                tap(LONGER)
                checkNotNull(device.wait(Until.findObject(By.text("私は")), WAIT_MS)) { "Longer did not join 私 and は again" }
                tap(SHORTER)
                checkNotNull(device.wait(Until.findObject(By.text("私")), WAIT_MS))
                tap("渡し")
                checkNotNull(device.wait(Until.findObject(By.textStartsWith(SEGMENT_2)), WAIT_MS)) { "the panel did not move on to は" }
                assertTrue("the panel closed after picking 渡し", device.hasObject(By.desc(BACK)))
                checkNotNull(device.wait(Until.findObject(By.text("渡し")), WAIT_MS)) { "渡し was not kept" }
                assertTrue("は and 日本人 stay", device.hasObject(By.text("は")) && device.hasObject(By.text("日本人")))
                tap("日本人")
                checkNotNull(device.wait(Until.findObject(By.textStartsWith("Segment 3 of")), WAIT_MS)) { "the panel did not move to 日本人" }
                // The last segment's own first candidate: the keys come back after it.
                device.findObjects(By.text("日本人")).maxBy { it.visibleBounds.top }.click()
                assertTrue("the panel stayed open after the last segment", device.wait(Until.gone(By.desc(BACK)), WAIT_MS))
                checkNotNull(device.wait(Until.findObject(By.descStartsWith(ENTER)), WAIT_MS)).click()
                assertEquals("渡しは日本人", read(scenario))
            }
        }
    }

    /**
     * PLAN §10.8 (T13-punct): with both switches on, an opening bracket and a quote type their
     * closing marks, and backspace right away removes each empty pair; with them off the same keys
     * type as they are.
     */
    @Test
    fun smartPunctuationPairsAndUnpairs() {
        inMode(Mode.ENGLISH) {
            withSettings({ it.copy(smartQuotes = true, autoPair = true) }) {
                assertEquals("(“”)", punctuate("(\"") {})
                assertEquals("()", punctuate("(\"") { key(BACKSPACE) })
                assertEquals("", punctuate("(\"") { key(BACKSPACE); key(BACKSPACE) })
            }
            withSettings({ it.copy(smartQuotes = false, autoPair = false) }) {
                assertEquals("(\"", punctuate("(\"") {})
            }
        }
    }

    /**
     * T14-toolbar-3 (PLAN §10.10): with Edit put on the bar, its panel moves the cursor, selects with
     * the arrows and cuts and pastes; hidden tools wait under More and tools on the bar do not.
     */
    @Test
    fun toolbarEditPanelSelectsCutsAndPastes() {
        inMode(Mode.ENGLISH) {
            withSettings({ it.copy(toolOrder = listOf(Tool.EDIT) + (Tool.configurable - Tool.EDIT), hiddenTools = Tool.configurable.toSet() - Tool.EDIT) }) {
                val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
                    .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
                    .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
                ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
                    val q = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }
                    scenario.onActivity { a -> a.findViewById<EditText>(FieldTestActivity.FIELD_ID).apply { setText("hello"); setSelection(5) } }
                    val res = instrumentation.targetContext.resources
                    val barY = q.visibleBounds.top - res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.kb_pad_top) -
                        res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.bar_height) / 2
                    val pad = res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.toolbar_pad) + res.getDimensionPixelSize(com.yuanzhe.kotomo.R.dimen.toolbar_button) / 2
                    // More sits at the start; with one tool on the bar, Edit sits at the end.
                    device.click(pad, barY)
                    checkNotNull(device.wait(Until.findObject(By.text(CLIPBOARD_TOOL)), WAIT_MS)) { "the hidden Clipboard tool is not under More" }
                    assertFalse("Edit is on the bar, not under More", device.hasObject(By.text(EDIT_TOOL)))
                    device.findObject(By.desc(BACK)).click()
                    checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS))
                    device.click(device.displayWidth - pad, barY)
                    checkNotNull(device.wait(Until.findObject(By.desc(EDIT_LEFT)), WAIT_MS)) { "the edit panel did not open" }
                    listOf(EDIT_SELECT, EDIT_LEFT, EDIT_LEFT, EDIT_CUT).forEach { key(it) }
                    assertEquals("hel", read(scenario))
                    listOf(EDIT_HOME, EDIT_PASTE).forEach { key(it) }
                    assertEquals("lohel", read(scenario))
                    // T14-undo (PLAN §10.8): Undo takes the paste back, Redo brings it again.
                    key(EDIT_UNDO)
                    assertEquals("hel", read(scenario))
                    key(EDIT_REDO)
                    assertEquals("lohel", read(scenario))
                }
            }
        }
    }

    /**
     * T13-app (PLAN §10.10): with "Code in this app" on, the app's ordinary text fields type plain
     * quotes and no pairs even with smart punctuation on; with it off they pair again.
     */
    @Test
    fun codeInThisAppKeepsPunctuationPlain() {
        val context = instrumentation.targetContext
        val memory = KeyboardMemory.get(context)
        inMode(Mode.ENGLISH) {
            withSettings({ it.copy(smartQuotes = true, autoPair = true) }) {
                memory.update { m -> m.withApp(context.packageName) { it.copy(code = true) } }
                try {
                    assertEquals("(\"", punctuate("(\"") {})
                } finally {
                    memory.update { m -> m.withApp(context.packageName) { it.copy(code = false) } }
                }
                assertEquals("(“”)", punctuate("(\"") {})
            }
        }
    }

    /** Opens a text field, taps [marks] on the first symbol page, runs [then] and returns the text. */
    private fun punctuate(marks: String, then: () -> Unit): String {
        val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, 0)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            key(SYMBOLS)
            marks.forEach { key(it.toString()) }
            then()
            device.waitForIdle()
            return read(scenario)
        }
    }

    /** Taps the key described as [desc] once it is there. */
    private fun key(desc: String) {
        checkNotNull(device.wait(Until.findObject(By.desc(desc)), WAIT_MS)) { "no key $desc" }.click()
        Thread.sleep(READ_MS)
    }

    /** Taps the first view whose text is [text] once it is there (Compose panel content). */
    private fun tap(text: String) {
        checkNotNull(device.wait(Until.findObject(By.text(text)), WAIT_MS)) { "no $text" }.click()
        device.waitForIdle()
        Thread.sleep(READ_MS)
    }

    /**
     * PLAN §8.10: one-handed mode on this screen and orientation, at the width kept for it, puts
     * the keys on the right side; back to full width they span the screen.
     */
    @Test
    fun oneHandedWidthIsKeptPerScreen() {
        val context = instrumentation.targetContext
        val config = context.resources.configuration
        val screen = Posture.screenKey(config.smallestScreenWidthDp, config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        val memory = KeyboardMemory.get(context)
        val before = memory.current.oneHanded[screen]
        fun qLeft(): Int {
            val intent = Intent(context, FieldTestActivity::class.java).putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            ActivityScenario.launch<FieldTestActivity>(intent).use {
                return checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }.visibleBounds.left
            }
        }
        try {
            memory.update { m -> m.withOneHanded(screen, OneHanded.OFF) { OneHandedMemory(OneHanded.RIGHT, 70) } }
            val narrow = qLeft()
            memory.update { m -> m.withOneHanded(screen, OneHanded.OFF) { OneHandedMemory(OneHanded.OFF, 70) } }
            val full = qLeft()
            val width = device.displayWidth
            assertTrue("one-handed q at $narrow", narrow > width * 0.25)
            assertTrue("full-width q at $full", full < width * 0.05)
        } finally {
            memory.update { m -> m.copy(oneHanded = if (before == null) m.oneHanded - screen else m.oneHanded + (screen to before)) }
        }
    }

    /**
     * PLAN §8.8: clearing the learning data while the keyboard runs closes and reopens librime,
     * and Chinese still converts afterwards.
     */
    @Test
    fun typingWorksAfterClearingLearningData() = inMode(Mode.CHINESE) {
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
     * T16 (PLAN §10.1, §10.2): a long press on 食べます shows the Forms button; its panel commits
     * 食べない in place of the composition.
     */
    @Test
    fun japaneseFormsReplaceTheComposition() {
        inMode(Mode.JAPANESE) {
            val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
                .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
                .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
                val q = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }.visibleBounds
                "tabemasu".forEach { device.findObject(By.desc(it.toString())).click() }
                Thread.sleep(READ_MS * 3)
                // The candidate bar is drawn, not a view tree: the first candidate sits at its start,
                // the details' button at its end, in the row above the keys.
                val dp = instrumentation.targetContext.resources.displayMetrics.density
                val y = (q.top - 32 * dp).toInt()
                device.swipe((q.left + 40 * dp).toInt(), y, (q.left + 40 * dp).toInt(), y, 120)
                Thread.sleep(READ_MS)
                device.click((device.displayWidth - 36 * dp).toInt(), y)
                checkNotNull(device.wait(Until.findObject(By.text("食べない")), WAIT_MS)) { "no forms panel" }.click()
                assertEquals("食べない", read(scenario))
                assertFalse("the form was left composing", composing(scenario))
            }
        }
    }

    /**
     * C4: a candidate's details (a long press) close on the next key and after a commit; they
     * stayed in the bar after Space converted or committed in Japanese mode.
     */
    @Test
    fun candidateDetailsCloseOnTheNextKey() {
        inMode(Mode.JAPANESE) {
            val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
                .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
                .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
                val q = checkNotNull(device.wait(Until.findObject(By.desc("q")), WAIT_MS)) { "Kotomo's keys did not appear" }.visibleBounds
                val dp = instrumentation.targetContext.resources.displayMetrics.density
                val x = (q.left + 40 * dp).toInt()
                val y = (q.top - 32 * dp).toInt()
                fun detailsAfter(key: String) {
                    device.swipe(x, y, x, y, 120)
                    checkNotNull(device.wait(Until.findObject(By.descStartsWith("とうきょうは")), WAIT_MS)) { "no details" }
                    device.findObject(By.descStartsWith(key)).click()
                    assertTrue("the details stayed after $key", device.wait(Until.gone(By.descStartsWith("とうきょうは")), WAIT_MS))
                }
                "toukyouha".forEach { device.findObject(By.desc(it.toString())).click() }
                Thread.sleep(READ_MS * 3)
                detailsAfter(SPACE)
                detailsAfter(ENTER)
                assertFalse("Enter left the candidate uncommitted", composing(scenario))
                assertTrue(read(scenario).isNotEmpty())
            }
        }
    }

    /**
     * T14-emoji-panel (PLAN §10.6): a favorite is sent from Favorites, the long-press card removes
     * it, and a kaomoji is sent from Kaomoji.
     */
    @Test
    fun emojiPanelFavoritesAndKaomoji() {
        val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        withSettings({ it.copy(emojiFavorites = "🍎") }) {
            ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
                checkNotNull(device.wait(Until.findObject(By.descStartsWith(EMOJI)), WAIT_MS)) { "Kotomo's keys did not appear" }.click()
                checkNotNull(device.wait(Until.findObject(By.desc(FAVORITES)), WAIT_MS)) { "no Favorites tab" }.click()
                checkNotNull(device.wait(Until.findObject(By.text("🍎")), WAIT_MS)) { "the favorite is not shown" }.click()
                assertEquals("🍎", read(scenario))
                // The field shows the sent emoji too: the panel's cell is the last match.
                device.findObjects(By.text("🍎")).last().longClick()
                checkNotNull(device.wait(Until.findObject(By.desc(FAVORITE_REMOVE)), WAIT_MS)) { "no favorite switch" }.click()
                Thread.sleep(READ_MS)
                assertEquals("", SettingsStore.get(instrumentation.targetContext).current.emojiFavorites)
                // The section tabs scroll: swipe them to the end, where Kaomoji is.
                val bar = device.findObject(By.desc(FAVORITES)).visibleBounds
                repeat(3) { device.swipe(device.displayWidth * 4 / 5, bar.centerY(), device.displayWidth / 5, bar.centerY(), 20) }
                checkNotNull(device.wait(Until.findObject(By.desc(KAOMOJI)), WAIT_MS)) { "no Kaomoji tab" }.click()
                checkNotNull(device.wait(Until.findObject(By.text(EmojiExtras.KAOMOJI.first())), WAIT_MS)) { "no kaomoji" }.click()
                assertEquals("🍎" + EmojiExtras.KAOMOJI.first(), read(scenario))
            }
        }
    }

    /**
     * C7 (checklist 13.2, 13.4): a long press on an emoji with skin tones (👋, from Favorites)
     * opens the tone card with its five tones; choosing one sends that tone.
     */
    @Test
    fun emojiLongPressOffersSkinTones() {
        val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        withSettings({ it.copy(emojiFavorites = "👋") }) {
            ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
                checkNotNull(device.wait(Until.findObject(By.descStartsWith(EMOJI)), WAIT_MS)) { "Kotomo's keys did not appear" }.click()
                checkNotNull(device.wait(Until.findObject(By.desc(FAVORITES)), WAIT_MS)) { "no Favorites tab" }.click()
                checkNotNull(device.wait(Until.findObject(By.text("👋")), WAIT_MS)) { "the favorite is not shown" }.longClick()
                checkNotNull(device.wait(Until.findObject(By.desc(TONES)), WAIT_MS)) { "no tone card" }
                assertTrue("not every tone is offered", listOf("👋🏻", "👋🏼", "👋🏽", "👋🏾", "👋🏿").all { device.hasObject(By.text(it)) })
                device.findObject(By.text("👋🏽")).click()
                assertEquals("👋🏽", read(scenario))
            }
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
     * With [committed] set, the text only counts when the field's composition is over (true) or
     * still open (false); otherwise "" is returned.
     * Notes: the first field of a run may come up before librime has opened its data; then the
     * letters type directly, so the field is cleared and the typing repeated a few times.
     */
    private fun typeInto(
        inputType: Int, imeOptions: Int, letters: String, expected: String, space: Boolean = true, enter: Boolean = false,
        committed: Boolean? = null, afterLetters: () -> Unit = {},
    ): String {
        val intent = Intent(instrumentation.targetContext, FieldTestActivity::class.java)
            .putExtra(FieldTestActivity.EXTRA_INPUT_TYPE, inputType)
            .putExtra(FieldTestActivity.EXTRA_IME_OPTIONS, imeOptions)
        ActivityScenario.launch<FieldTestActivity>(intent).use { scenario ->
            var text = ""
            repeat(ATTEMPTS) {
                checkNotNull(device.wait(Until.findObject(By.desc(letters.first().toString())), WAIT_MS)) { "Kotomo's keys did not appear" }
                // ' is the syllable separator, in Shift's place while pinyin is composed (B1).
                letters.forEach { device.findObject(By.desc(if (it == '\'') Layout.SEPARATOR else it.toString())).click() }
                Thread.sleep(READ_MS)
                afterLetters()
                if (space) device.findObject(By.descStartsWith(SPACE)).click()
                if (enter) {
                    Thread.sleep(READ_MS)
                    device.findObject(By.descStartsWith(ENTER)).click()
                }
                device.waitForIdle()
                text = read(scenario)
                if (committed != null && composing(scenario) == committed) text = ""
                if (text == expected) return text
                device.wait(Until.hasObject(By.desc(letters.first().toString())), WAIT_MS)
                scenario.onActivity { it.findViewById<EditText>(FieldTestActivity.FIELD_ID).setText("") }
                Thread.sleep(WAIT_MS)
            }
            return text
        }
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
        /** The space key's description starts with this (R.string.key_space, English). */
        const val SPACE = "Space"
        /** The Enter key's description starts with this (R.string.key_enter, English). */
        const val ENTER = "Enter"
        /** The Emoji key's description starts with this (R.string.key_emoji, English). */
        const val EMOJI = "Emoji"
        /** The segment panel's labels (R.string.segment_whole, segment_shorter, segment_longer, candidates_collapse; English). */
        const val WHOLE = "Whole sentence"
        const val SHORTER = "Shorter"
        const val LONGER = "Longer"
        const val BACK = "Back to the keyboard"
        /** The segment panel's position line for the second segment (R.string.segment_position, English). */
        const val SEGMENT_2 = "Segment 2 of"
        /** The emoji panel's search tab (R.string.emoji_search, English). */
        const val SEARCH = "Search emoji"
        /** The emoji panel's Favorites and Kaomoji tabs and the card's switch (R.string.emoji_*, English). */
        const val FAVORITES = "Favorites"
        const val KAOMOJI = "Kaomoji"
        const val FAVORITE_REMOVE = "Remove from favorites"
        /** The tone card's description (R.string.emoji_tones, English). */
        const val TONES = "Choose a skin tone"
        /** The symbols and backspace keys' descriptions (R.string.key_symbols, key_backspace; English). */
        const val SYMBOLS = "Symbols"
        const val BACKSPACE = "Delete"
        /** The Shift key's description (R.string.key_shift, English). */
        const val SHIFT = "Shift"
        /** Tool labels and edit keys (R.string.tool_*, edit_*; English). */
        const val CLIPBOARD_TOOL = "Clipboard"
        const val EDIT_TOOL = "Edit text"
        const val EDIT_LEFT = "Left"
        const val EDIT_SELECT = "Select"
        const val EDIT_CUT = "Cut"
        const val EDIT_HOME = "Line start"
        const val EDIT_PASTE = "Paste"
        const val EDIT_UNDO = "Undo"
        const val EDIT_REDO = "Redo"
        const val ATTEMPTS = 3
        const val WAIT_MS = 3000L
        const val READS = 3
        const val READ_MS = 300L
        /** A11: the most swipes down the expanded list, steps per swipe, and how many candidates `yu` lists at least. */
        const val SCROLLS = 120
        const val SWIPE_STEPS = 30
        const val SCROLLED_PAST = 200
    }
}
