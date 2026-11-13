package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.engine.SnapshotCandidate
import com.yuanzhe.kotomo.engine.Origin
import com.yuanzhe.kotomo.engine.Lang
import com.yuanzhe.kotomo.engine.English
import com.yuanzhe.kotomo.engine.Mozc
import com.yuanzhe.kotomo.engine.Piece
import com.yuanzhe.kotomo.engine.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the candidate bar's lists (PLAN §8.2) and English tap positions (PLAN §9.7). */
class CandidatesTest {
    @Test
    fun chineseOffersTheTypedLettersAfterTheTwoBestWords() {
        val list = Candidates.chinese(listOf("中国", "种过", "种果"), "zhongguo", listOf("🐉"))
        assertEquals(listOf("中国" to 0, "种过" to 1, "zhongguo" to Candidates.TYPED, "🐉" to Candidates.EMOJI, "种果" to 2), list)
    }

    @Test
    fun chineseLeavesOutTypedLettersThatAreAlreadyACandidate() { // negative
        val list = Candidates.chinese(listOf("ok", "哦"), "ok", emptyList())
        assertEquals(listOf("ok" to 0, "哦" to 1), list)
        assertTrue(Candidates.chinese(emptyList(), "", emptyList()).isEmpty())
    }

    @Test
    fun englishKeepsTheTypedWordFirstAndItsCapital() {
        assertEquals(listOf("Helo", "Hello", "Help"), Candidates.english("Helo", listOf("hello", "help")))
        assertEquals(listOf("first", "most"), Candidates.english("", listOf("first", "most", "first")))
    }

    private fun seg(reading: String, vararg values: String, pinned: Boolean = false) =
        Mozc.Segment(reading, pinned, values.map { Mozc.Candidate(it, reading) })

    private val sentence = listOf(seg("わたし", "私", "渡し"), seg("は", "は"), seg("にほんじん", "日本人"))

    @Test
    fun japaneseListsSentenceSegmentPredictionsAndKana() { // T03, PLAN §8.2
        val list = Candidates.japanese(sentence, listOf(Mozc.Candidate("私たちは日本人です", "わたしたちはにほんじんです"), Mozc.Candidate("私", "わたし")))
        // A completion longer than the reading comes right after the sentence; a shorter one later.
        assertEquals(listOf("私は日本人", "私たちは日本人です", "渡しは日本人", "私", "わたしはにほんじん", "ワタシハニホンジン"), list.map { it.text })
        // Each candidate carries its own reading, not the whole composition's.
        assertEquals("わたし", list.first { it.text == "私" }.reading)
        assertEquals("わたしはにほんじん", list.first().reading)
    }

    @Test
    fun pickingASegmentPinsItAndKeepsWhatCameBefore() { // PLAN §5.6
        val spans = Candidates.pick(sentence, 1, "わ")!!
        assertEquals(listOf(Mozc.Span("わたし", fixed = true), Mozc.Span("は", "わ"), Mozc.Span("にほんじん")), spans)
    }

    @Test
    fun pickingKeepsPinnedSegmentsAfterIt() { // negative: a chosen segment is never rewritten
        val chosen = listOf(seg("わたし", "私"), seg("は", "は"), seg("にほんじん", "二本人", pinned = true))
        val spans = Candidates.pick(chosen, 0, "渡し")!!
        assertEquals(Mozc.Span("にほんじん", "二本人"), spans.last())
        assertEquals(Mozc.Span("わたし", "渡し"), spans.first())
    }

    @Test
    fun resizingFixesTheNewBoundary() { // PLAN §8.2 文节调整
        assertEquals(listOf(Mozc.Span("わた", fixed = true), Mozc.Span("しはにほんじん")), Candidates.resize(sentence, 0, -1))
        assertEquals(listOf(Mozc.Span("わたし", fixed = true), Mozc.Span("はに", fixed = true), Mozc.Span("ほんじん")), Candidates.resize(sentence, 1, 1))
    }

    @Test
    fun resizingCannotEmptyOrOverrunASegment() { // negative
        assertNull(Candidates.resize(sentence, 1, -1))
        assertNull(Candidates.resize(sentence, 2, 1))
        assertNull(Candidates.resize(sentence, 5, 1))
    }

    @Test
    fun everyModePlacesEmojiByTheirWeight() { // PLAN §10.6: their own weight, after the words by default
        val texts = listOf("一", "二", "三", "四", "五", "六")
        val at = Candidates.emojiPosition(30)
        // Chinese: the letters as typed stay after the two best; the emoji come after five words.
        val chinese = Candidates.chinese(texts, "yi", listOf("🙂"), at).map { it.first }
        assertEquals(listOf("一", "二", "yi", "三", "四", "五", "🙂", "六"), chinese)
        // At 100 they come right after the two best, as before; fewer words: they follow them all.
        assertEquals(listOf("一", "二", "yi", "🙂", "三"), Candidates.chinese(texts.take(3), "yi", listOf("🙂"), Candidates.emojiPosition(100)).map { it.first })
        assertEquals(listOf("一", "🙂"), Candidates.chinese(listOf("一"), "", listOf("🙂"), at).map { it.first })
        // English and Japanese share withEmoji.
        assertEquals(listOf("a", "b", "c", "d", "e", "🙂", "f"), Candidates.withEmoji(listOf("a", "b", "c", "d", "e", "f"), listOf("🙂"), at))
        // Negative: no emoji, nothing moves.
        assertEquals(texts, Candidates.chinese(texts, "", emptyList(), at).map { it.first })
    }

    @Test
    fun mixedKeepsTheEnginesOrderAndPlacesEmojiByWeight() { // T04, PLAN §2.3
        val texts = listOf("一", "二", "三", "四", "五", "六")
        // The default emoji weight (30) puts emoji after five candidates; the letters come last.
        val list = Candidates.mixed(texts, "yi er", listOf("🙂"), Candidates.emojiPosition(30))
        assertEquals(listOf("一", "二", "三", "四", "五", "🙂", "六", "yi er"), list.map { it.first })
        assertEquals(listOf(0, 1, 2, 3, 4, Candidates.MIXED_EMOJI, 5, Candidates.MIXED_TYPED), list.map { it.second })
        assertEquals(2, Candidates.emojiPosition(100))
        assertEquals(7, Candidates.emojiPosition(0))
        // Fewer candidates than the position: the emoji follow them all.
        assertEquals(listOf("一", "🙂", "yi"), Candidates.mixed(listOf("一"), "yi", listOf("🙂"), 5).map { it.first })
    }

    @Test
    fun mixedNeverListsATextTwice() { // negative
        // The letters are already a candidate (an English word), and an emoji equals a candidate.
        val list = Candidates.mixed(listOf("book", "本", "🙂"), "book", listOf("🙂"), 1)
        assertEquals(listOf("book", "🙂", "本"), list.map { it.first })
        // Nothing typed and nothing found lists nothing.
        assertEquals(emptyList<Pair<String, Int>>(), Candidates.mixed(emptyList(), " ", emptyList(), 2))
    }

    @Test
    fun tapsMapIntoTheEnglishEnginesLayout() {
        // The centre of r is (350, 75); a tap at its left edge lands next to e.
        assertEquals(350 to 75, English.touchPoint('r', 0f, 0f))
        assertEquals(300 to 75, English.touchPoint('r', -0.5f, 0f))
        // Second and third rows start half a key and one and a half keys in.
        assertEquals(200 to 225, English.touchPoint('s', 0f, 0f))
        assertEquals(200 to 375, English.touchPoint('Z', 0f, 0f))
        // Taps far outside stay inside the layout; characters without a key map to nothing.
        assertEquals(0 to 0, English.touchPoint('q', -5f, -5f))
        assertNull(English.touchPoint('1', 0f, 0f))
    }

    @Test
    fun mixedReadingShowsHowACrossLanguageJapaneseCandidateIsRead() { // PLAN §9.3
        fun piece(text: String, reading: String, typed: Lang, origin: Origin) =
            com.yuanzhe.kotomo.engine.Piece(0, 1, text, reading, typed, origin, origin != Origin.MOZC)
        val cross = SnapshotCandidate("池袋", Lang.JAPANESE, 0, SnapshotCandidate.FLAG_CROSS,
            listOf(piece("池袋", "いけぶくろ", Lang.CHINESE, Origin.PINYIN_JA)))
        assertEquals("いけぶくろ", Candidates.mixedReading(cross))
        // Negative: typed as romaji, written in Chinese, or a reading that equals the text.
        val romaji = SnapshotCandidate("猫", Lang.JAPANESE, 0, 0, listOf(piece("猫", "ねこ", Lang.JAPANESE, Origin.MOZC)))
        assertEquals("", Candidates.mixedReading(romaji))
        val chinese = SnapshotCandidate("电脑", Lang.CHINESE, 0, 0, listOf(piece("电脑", "diannao", Lang.CHINESE, Origin.RIME)))
        assertEquals("", Candidates.mixedReading(chinese))
        val kana = SnapshotCandidate("コーヒー", Lang.JAPANESE, 0, SnapshotCandidate.FLAG_CROSS,
            listOf(piece("コーヒー", "コーヒー", Lang.ENGLISH, Origin.EN_JA)))
        assertEquals("", Candidates.mixedReading(kana))
    }

    @Test
    fun japaneseModePlacesCrossLanguageCandidatesAfterRomaji() { // PLAN §2.3, §9.10
        val own = listOf(Candidates.Japanese("会", "かい"), Candidates.Japanese("回", "かい"), Candidates.Japanese("かい", "かい"))
        val cross = listOf(
            Candidates.Japanese("開", "かい", cross = true, leads = true),
            Candidates.Japanese("回", "かい", cross = true, leads = true),
            Candidates.Japanese("キット", "きっと", cross = true),
        )
        // Romaji throughout: Mozc's best stays first, a leading cross-language candidate is next,
        // one the coordinator ranks below the romaji reading goes last; no text twice.
        assertEquals(listOf("会", "開", "回", "かい", "キット"), Candidates.japaneseWithCross(own, cross, "かい").map { it.text })
        // Not romaji (the reading keeps letters): the cross-language reading leads.
        val guess = listOf(Candidates.Japanese("どんじんg", "どんじんg"))
        val tokyo = listOf(Candidates.Japanese("東京", "とうきょう", cross = true, leads = true))
        assertEquals(listOf("東京", "どんじんg"), Candidates.japaneseWithCross(guess, tokyo, "どんじんg").map { it.text })
        // Negative: nothing to add leaves the list as it is, and at most three are added.
        assertEquals(own, Candidates.japaneseWithCross(own, emptyList(), "かい"))
        val many = (1..5).map { Candidates.Japanese("語$it", "ご", cross = true) }
        assertEquals(own.size + Candidates.JAPANESE_CROSS, Candidates.japaneseWithCross(own, many, "かい").size)
        assertEquals(emptyList<Candidates.Japanese>(), Candidates.japaneseCross(null))
    }

    @Test
    fun japaneseModeTakesTheParticleReadingFirst() { // T03-001, PLAN §5.4
        fun piece(begin: Int, end: Int, text: String, reading: String, origin: Origin) =
            Piece(begin, end, text, reading, Lang.JAPANESE, origin, pinned = false)
        val particle = SnapshotCandidate("本は", Lang.JAPANESE, 10, 0, listOf(piece(0, 3, "本", "ほん", Origin.MOZC), piece(3, 5, "は", "は", Origin.RULE)))
        val plain = SnapshotCandidate("本羽", Lang.JAPANESE, 20, 0, listOf(piece(0, 5, "本羽", "ほんわ", Origin.MOZC)))
        val own = listOf(Candidates.Japanese("本羽", "ほんわ"), Candidates.Japanese("ほんわ", "ほんわ"))
        val read = Candidates.japaneseCross(Snapshot(1, 0, "honwa", 5, "", listOf(particle, plain)))
        assertEquals(listOf("本は", "本羽", "ほんわ"), Candidates.japaneseWithCross(own, read, "ほんわ").map { it.text })
        // Negative: the coordinator's best romaji sentence has no particle; nothing is added.
        assertEquals(own, Candidates.japaneseWithCross(own, Candidates.japaneseCross(Snapshot(1, 0, "honwa", 5, "", listOf(plain, particle))), "ほんわ"))
    }
}
