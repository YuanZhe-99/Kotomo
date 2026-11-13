package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.engine.Lang
import com.yuanzhe.kotomo.engine.Origin
import com.yuanzhe.kotomo.engine.Piece
import com.yuanzhe.kotomo.engine.SnapshotCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T10 (PLAN §9.8, §9.9): what Mixed mode learns from a commit, how it fades, what the person can
 * decide about it, and the versioned file it is kept in.
 */
class MixedLearningTest {
    private val day = 86_400_000L
    private val now = 1_000 * day

    private fun candidate(text: String, vararg pieces: Triple<Int, Int, String>) = SnapshotCandidate(
        text, Lang.JAPANESE, 0, 0,
        pieces.map { (begin, end, t) -> Piece(begin, end, t, "", Lang.JAPANESE, Origin.MOZC, pinned = false) },
    )

    private val sentence = candidate("本を読む", Triple(0, 3, "本"), Triple(4, 6, "を"), Triple(7, 11, "読む"))

    @Test
    fun aCommitTeachesTheSentenceAndItsPieces() {
        val learned = Learned().committed("hon wo yomu", sentence, now)
        assertEquals(
            setOf("honwoyomu" to "本を読む", "hon" to "本", "wo" to "を", "yomu" to "読む"),
            learned.readings.keys,
        )
        assertEquals(-LearnedReading.STEP, learned.adjustments(now)["hon" to "本"])
        // One piece: only the whole input.
        assertEquals(setOf("shu" to "書"), Learned().committed("shu", candidate("書", Triple(0, 3, "書")), now).readings.keys)
        // Negative: nothing typed, nothing learned.
        assertTrue(Learned().committed("  ", candidate(""), now).readings.isEmpty())
    }

    @Test
    fun usesAddUpToACapAndFade() {
        var learned = Learned()
        repeat(5) { learned = learned.committed("shu", candidate("書"), now) }
        val reading = learned.readings.getValue("shu" to "書")
        assertEquals(5, reading.count)
        assertEquals(-(LearnedReading.MAX_USES * LearnedReading.STEP).toInt(), reading.adjust(now))
        // One half-life later half of it counts; long after, nothing does.
        assertEquals(-(2.5 * LearnedReading.STEP).toInt(), reading.adjust(now + 30 * day))
        assertEquals(0, reading.adjust(now + 365 * day))
        // A use after the fade starts from what is left, not from the old count.
        assertEquals(1, learned.committed("shu", candidate("書"), now + 365 * day).readings.getValue("shu" to "書").count)
    }

    @Test
    fun thePersonRanksAndDeletes() {
        val learned = Learned().committed("shu", candidate("書"), now)
        val reading = learned.readings.values.single()
        assertEquals(LearnedReading.DEMOTED_ADJUST, learned.ranked(reading, LearnedRank.DEMOTED).adjustments(now).values.single())
        assertEquals(LearnedReading.BLOCKED_ADJUST, learned.ranked(reading, LearnedRank.BLOCKED).adjustments(now).values.single())
        assertEquals(LearnedReading.PINNED_ADJUST, learned.ranked(reading, LearnedRank.PINNED).adjustments(now + 3650 * day).values.single())
        assertTrue(learned.without(reading).readings.isEmpty())
    }

    @Test
    fun undoTakesBackOnlyWhatTheCommitTaught() {
        val before = Learned().committed("hon", candidate("本"), now)
        val after = before.committed("hon wo yomu", sentence, now)
        // Meanwhile the person ranked another reading in the settings.
        val other = LearnedReading("ai", "愛", 1, now)
        val undone = after.ranked(other, LearnedRank.PINNED).uncommitted("hon wo yomu", sentence, before)
        assertEquals(setOf("hon" to "本", "ai" to "愛"), undone.readings.keys)
        assertEquals(1, undone.readings.getValue("hon" to "本").count)
    }

    @Test
    fun theFileRoundTripsAndKeepsWhatItDoesNotKnow() {
        val learned = Learned().committed("shu", candidate("書"), now).let { it.ranked(it.readings.values.single(), LearnedRank.DEMOTED) }
        assertEquals(learned.readings, LearnedFormat.read(LearnedFormat.write(learned, now))!!.readings)
        val newer = """{"format_version": 9, "later": true, "readings": [
            {"input": "shu", "text": "書", "count": 2, "used_utc_ms": 5, "rank": "sideways", "note": "x"},
            {"input": "Not Letters", "text": "x"}, {"input": "a"}, 7]}"""
        val read = assertNotNull(LearnedFormat.read(newer)).let { LearnedFormat.read(newer)!! }
        val reading = read.readings.values.single()
        assertEquals(LearnedRank.NORMAL, reading.rank)
        val written = LearnedFormat.write(read, now)
        // Never a lower version, and the unknown members are still there.
        assertTrue("\"format_version\": 9" in written && "\"later\": true" in written)
        assertTrue("\"note\": \"x\"" in written && "\"rank\": \"sideways\"" in written)
        // Negative: a damaged file is not read.
        assertNull(LearnedFormat.read("[1, 2"))
    }
}
