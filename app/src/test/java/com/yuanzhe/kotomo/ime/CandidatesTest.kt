package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.engine.English
import com.yuanzhe.kotomo.engine.Mozc
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
}
