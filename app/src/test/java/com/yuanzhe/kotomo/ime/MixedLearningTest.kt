package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.engine.Lang
import com.yuanzhe.kotomo.engine.Origin
import com.yuanzhe.kotomo.engine.Piece
import com.yuanzhe.kotomo.engine.SnapshotCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * T10 (PLAN §9.8, §9.9): what Mixed mode learns from a commit, how it fades, what the person can
 * decide about it, and the versioned file it is kept in.
 */
class MixedLearningTest {
    @get:Rule val folder = TemporaryFolder()

    private val day = 86_400_000L
    private val now = 1_000 * day

    private fun candidate(text: String, vararg pieces: Triple<Int, Int, String>, sentence: Boolean = true) = SnapshotCandidate(
        text, Lang.JAPANESE, 0, if (sentence) SnapshotCandidate.FLAG_SENTENCE else 0,
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

    /**
     * Import and export (user decision 2026-11-13): an export is the plain file also while the
     * store is encrypted; an import adds what is missing and keeps the copy used later; text
     * that is not a learned readings file changes nothing.
     */
    @Test
    fun exportIsPlainAndImportNeverReplacesNewerLearning() {
        val key = aesKey(3)
        val source = MixedLearning(File(folder.root, "a.json"), File(folder.root, "a.kenc")) { key }
        source.setEncrypted(true)
        source.update { it.committed("shu", candidate("書"), now).committed("ben", candidate("本"), now) }
        val exported = source.export()
        assertTrue("書" in exported && "\"format_version\"" in exported)
        val target = MixedLearning(File(folder.root, "b.json"), File(folder.root, "b.kenc")) { key }
        target.update { l -> l.committed("shu", candidate("書"), now + day).let { it.ranked(it.readings.values.single(), LearnedRank.PINNED) } }
        assertEquals(2, target.import(exported))
        assertEquals(setOf("shu" to "書", "ben" to "本"), target.current.readings.keys)
        // The copy used later (this device's, pinned) stays.
        assertEquals(LearnedRank.PINNED, target.current.readings.getValue("shu" to "書").rank)
        val before = target.current
        assertNull(target.import("[1, 2"))
        assertEquals(before, target.current)
    }

    private fun aesKey(seed: Int): SecretKey = SecretKeySpec(ByteArray(32) { (it * seed).toByte() }, "AES")

    /**
     * PLAN §14 (`privacy.learned_encrypted`): turning encryption on seals the readings and
     * removes the plain file, a new process reads them back, and turning it off writes the plain
     * file again and removes the sealed one.
     */
    @Test
    fun encryptionSealsTheFileAndTurnsOffAgain() {
        val file = File(folder.root, "learned.json")
        val sealed = File(folder.root, "learned.kenc")
        val key = aesKey(3)
        val store = MixedLearning(file, sealed) { key }
        store.update { it.committed("shu", candidate("書"), now) }
        store.flush()
        assertTrue(file.isFile && !sealed.exists() && "書" in file.readText())
        store.setEncrypted(true)
        store.flush()
        assertTrue("the plain file stayed", sealed.isFile && !file.exists())
        assertFalse("the sealed file shows the text", "書" in String(sealed.readBytes()) || "shu" in String(sealed.readBytes()))
        // A later change stays sealed, and a new process reads it without being told the setting.
        store.update { it.committed("ben", candidate("本"), now) }
        store.flush()
        assertFalse(file.exists())
        val reopened = MixedLearning(file, sealed) { key }
        assertEquals(setOf("shu" to "書", "ben" to "本"), reopened.current.readings.keys)
        reopened.setEncrypted(false)
        reopened.flush()
        assertTrue(file.isFile && !sealed.exists())
        assertEquals(reopened.current.readings, MixedLearning(file, sealed) { key }.current.readings)
    }

    /**
     * Negative: a sealed file the key does not open is moved aside and nothing of it is read; a
     * sealed file of a newer container version is left untouched and never replaced.
     */
    @Test
    fun aSealedFileThatCannotBeReadIsNeverOverwritten() {
        val file = File(folder.root, "learned.json")
        val sealed = File(folder.root, "learned.kenc")
        MixedLearning(file, sealed) { aesKey(3) }.run {
            setEncrypted(true)
            update { it.committed("shu", candidate("書"), now) }
            flush()
        }
        val bytes = sealed.readBytes()
        val other = MixedLearning(file, sealed) { aesKey(5) }
        assertTrue(other.current.readings.isEmpty())
        assertTrue(!sealed.exists() && File(sealed.path + ".unreadable").readBytes().contentEquals(bytes))
        val newer = bytes.copyOf().also { it[4] = (LearnedSeal.CONTAINER + 1).toByte() }
        sealed.writeBytes(newer)
        val later = MixedLearning(file, sealed) { aesKey(3) }
        later.update { it.committed("ben", candidate("本"), now) }
        later.setEncrypted(false)
        later.flush()
        assertTrue("a newer file was replaced", sealed.readBytes().contentEquals(newer) && !file.exists())
    }

    /** A partial prefix commit teaches only the consumed span, never the unconsumed remainder. */
    @Test
    fun prefixCommitTeachesOnlyConsumedSpan() {
        val prefix = candidate("元", Triple(0, 4, "元"), sentence = false)
        val learned = Learned().committed("yuanzhe", prefix, now)
        assertEquals(setOf("yuan" to "元"), learned.readings.keys)
        assertFalse(learned.readings.containsKey("yuanzhe" to "元"))
    }
}
