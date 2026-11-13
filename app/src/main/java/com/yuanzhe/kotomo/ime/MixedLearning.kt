package com.yuanzhe.kotomo.ime

import android.content.Context
import com.yuanzhe.kotomo.dictionary.Json
import com.yuanzhe.kotomo.dictionary.list
import com.yuanzhe.kotomo.dictionary.long
import com.yuanzhe.kotomo.dictionary.str
import com.yuanzhe.kotomo.engine.SnapshotCandidate
import com.yuanzhe.kotomo.settings.readVersioned
import com.yuanzhe.kotomo.settings.writeAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.pow

/** Names what the person decided about a learned reading (PLAN §9.8). */
enum class LearnedRank { NORMAL, DEMOTED, BLOCKED, PINNED }

/**
 * Holds one reading Mixed mode learned (PLAN §9.9): the letters typed ([input], lower case,
 * without spaces), the [text] they were committed as, how often ([count]), when last
 * ([usedUtcMs]) and what the person decided about it ([rank]).
 *
 * Notes: [extra] keeps members this version does not know.
 */
data class LearnedReading(
    val input: String,
    val text: String,
    val count: Int = 0,
    val usedUtcMs: Long = 0,
    val rank: LearnedRank = LearnedRank.NORMAL,
    val extra: Map<String, Any?> = emptyMap(),
) {
    /**
     * Returns what this reading adds to its candidates' cost in the engine at [nowUtcMs]: a pinned
     * one always leads, a blocked one is hidden, a demoted one sinks; otherwise each use is worth
     * [STEP], halved every [HALF_LIFE_DAYS] since the last use and capped at [MAX_USES] uses; 0
     * once it has faded below [FADED] of a use.
     */
    fun adjust(nowUtcMs: Long): Int = when (rank) {
        LearnedRank.PINNED -> PINNED_ADJUST
        LearnedRank.BLOCKED -> BLOCKED_ADJUST
        LearnedRank.DEMOTED -> DEMOTED_ADJUST
        LearnedRank.NORMAL -> strength(nowUtcMs).let { if (it < FADED) 0 else -(it.coerceAtMost(MAX_USES) * STEP).toInt() }
    }

    /** The uses still counted at [nowUtcMs], after decay. */
    fun strength(nowUtcMs: Long): Double {
        val days = (nowUtcMs - usedUtcMs).coerceAtLeast(0) / DAY_MS.toDouble()
        return count * 0.5.pow(days / HALF_LIFE_DAYS)
    }

    companion object {
        const val STEP = 3000
        const val MAX_USES = 3.0
        const val HALF_LIFE_DAYS = 30.0
        const val FADED = 0.2
        const val PINNED_ADJUST = -20000
        const val DEMOTED_ADJUST = 3000

        /** The engine's value for a hidden reading (`kBlocked`, core/mixed/request.h). */
        const val BLOCKED_ADJUST = 32767
        private const val DAY_MS = 86_400_000L
    }
}

/**
 * Holds every learned reading, keyed by input and text, and the rules that change them.
 *
 * Notes: pure Kotlin; unit-tested (MixedLearningTest). [extra] keeps the file's unknown members.
 */
data class Learned(val readings: Map<Pair<String, String>, LearnedReading> = emptyMap(), val extra: Map<String, Any?> = emptyMap()) {
    /**
     * Records that [candidate] was committed for the composition [raw] at [nowUtcMs]: the whole
     * input's reading and, when the candidate has several pieces, each piece's reading.
     * Returns: the new state; the same state when there is nothing to learn (no letters).
     * Notes: at most [MAX_READINGS] are kept; the weakest readings the person did not rank go first.
     */
    fun committed(raw: String, candidate: SnapshotCandidate, nowUtcMs: Long): Learned {
        val keys = keys(raw, candidate)
        if (keys.isEmpty()) return this
        val next = readings.toMutableMap()
        for (key in keys) {
            val old = next[key] ?: LearnedReading(key.first, key.second)
            // Faded uses are not counted again in full: the count restarts from what is left.
            val kept = old.strength(nowUtcMs).coerceAtMost(old.count.toDouble())
            next[key] = old.copy(count = (kept + 1).toInt().coerceIn(1, MAX_COUNT), usedUtcMs = nowUtcMs)
        }
        if (next.size > MAX_READINGS) {
            next.values.filter { it.rank == LearnedRank.NORMAL }.sortedBy { it.strength(nowUtcMs) }
                .take(next.size - MAX_READINGS).forEach { next -= it.input to it.text }
        }
        return copy(readings = next)
    }

    /**
     * Takes a commit back (backspace right after it): the readings [committed] changed for
     * [raw] and [candidate] return to what they were in [before], the rest stays as it is now.
     */
    fun uncommitted(raw: String, candidate: SnapshotCandidate, before: Learned): Learned {
        val keys = keys(raw, candidate)
        return copy(readings = readings - keys + before.readings.filterKeys { it in keys })
    }

    /** Returns the state with [reading]'s rank set to [rank]. */
    fun ranked(reading: LearnedReading, rank: LearnedRank): Learned {
        val key = reading.input to reading.text
        return copy(readings = readings + (key to (readings[key] ?: reading).copy(rank = rank)))
    }

    /** Returns the state without [reading]. */
    fun without(reading: LearnedReading): Learned = copy(readings = readings - (reading.input to reading.text))

    /** Returns what the engine is told at [nowUtcMs]: each reading's key and its non-zero adjustment. */
    fun adjustments(nowUtcMs: Long): Map<Pair<String, String>, Int> =
        readings.mapValues { it.value.adjust(nowUtcMs) }.filterValues { it != 0 }

    companion object {
        const val MAX_READINGS = 2000
        const val MAX_COUNT = 1000

        /**
         * Names the readings a commit of [candidate] for [raw] teaches: the whole input's and,
         * when the candidate has several pieces, each piece's.
         */
        fun keys(raw: String, candidate: SnapshotCandidate): Set<Pair<String, String>> {
            val keys = LinkedHashSet<Pair<String, String>>()
            letters(raw).takeIf { it.isNotEmpty() && candidate.text.isNotEmpty() }?.let { keys += it to candidate.text }
            if (candidate.pieces.size > 1) {
                for (piece in candidate.pieces) {
                    val typed = letters(raw.substring(piece.begin.coerceAtMost(raw.length), piece.end.coerceAtMost(raw.length)))
                    if (typed.isNotEmpty() && piece.text.isNotEmpty()) keys += typed to piece.text
                }
            }
            return keys
        }

        /** The letters of [raw] in lower case: what the engine keys a reading by. */
        fun letters(raw: String): String = raw.filter { it in 'a'..'z' || it in 'A'..'Z' }.lowercase()
    }
}

/**
 * Maps [Learned] to and from its JSON text (`doc/en-us/settings-format.md`, "Learned readings").
 *
 * Notes: unknown members are kept and written back; timestamps are UTC milliseconds; a file of a
 * newer format version is read for what this version knows and never written with a lower version.
 */
internal object LearnedFormat {
    const val VERSION = 1
    private const val FORMAT = "format_version"
    private const val UPDATED = "updated_utc_ms"
    private const val READINGS = "readings"
    private const val INPUT = "input"
    private const val TEXT = "text"
    private const val COUNT = "count"
    private const val USED = "used_utc_ms"
    private const val RANK = "rank"
    private const val MAX_TEXT = 256

    /** Reads a file. Returns: null when the text is not a JSON object (a damaged file). */
    fun read(text: String): Learned? {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: return null
        val readings = o.list(READINGS).orEmpty().mapNotNull { value ->
            @Suppress("UNCHECKED_CAST")
            val r = value as? Map<String, Any?> ?: return@mapNotNull null
            val input = r.str(INPUT)?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT && it == Learned.letters(it) } ?: return@mapNotNull null
            val written = r.str(TEXT)?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT } ?: return@mapNotNull null
            val rank = r.str(RANK)?.let { raw -> LearnedRank.entries.firstOrNull { it.name.lowercase() == raw } }
            LearnedReading(
                input, written,
                count = r.long(COUNT)?.coerceIn(0, Learned.MAX_COUNT.toLong())?.toInt() ?: 0,
                usedUtcMs = r.long(USED)?.coerceAtLeast(0) ?: 0,
                rank = rank ?: LearnedRank.NORMAL,
                extra = r - setOfNotNull(INPUT, TEXT, COUNT, USED, RANK.takeIf { rank != null }),
            )
        }.take(Learned.MAX_READINGS)
        return Learned(readings.associateBy { it.input to it.text }, o - setOf(UPDATED, READINGS))
    }

    /** Writes [learned] as JSON, stamped [nowUtcMs]. */
    fun write(learned: Learned, nowUtcMs: Long): String {
        val out = LinkedHashMap<String, Any?>()
        out[FORMAT] = maxOf((learned.extra[FORMAT] as? Long)?.toInt() ?: 0, VERSION)
        out[UPDATED] = nowUtcMs
        out[READINGS] = learned.readings.values.sortedWith(compareBy({ it.input }, { it.text })).map { r ->
            LinkedHashMap<String, Any?>(r.extra).apply {
                put(INPUT, r.input)
                put(TEXT, r.text)
                put(COUNT, r.count)
                put(USED, r.usedUtcMs)
                // A rank this version could not read stays as it was until the person sets one.
                if (r.rank != LearnedRank.NORMAL) put(RANK, r.rank.name.lowercase())
            }
        }
        learned.extra.forEach { (k, v) -> if (k != FORMAT) out[k] = v }
        return Json.write(out, indent = true)
    }
}

/**
 * Keeps Mixed mode's learned readings in memory and in `files/keyboard/learned.json`, shared by
 * the keyboard and the settings app (one instance per process, [get]).
 *
 * Notes: the keyboard records a commit only where the field allows learning (never in a
 * sensitive field, one that asks for no personalized learning, incognito mode or before the
 * first unlock, PLAN §14); the file never leaves the device; Privacy → Clear learning data
 * empties it ([LearningData]). [current] never touches the disk. Each change writes the whole
 * file on one background thread through an atomic replace.
 */
class MixedLearning internal constructor(private val file: File) {
    private val state = MutableStateFlow(readVersioned(file, LearnedFormat::read) ?: Learned())
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "kotomo-learned").apply { isDaemon = true } }

    /** Emits the readings after every change (the settings page and the keyboard follow it). */
    val flow: StateFlow<Learned> get() = state

    /** Returns the readings as they are now, without I/O. */
    val current: Learned get() = state.value

    /** Applies [transform] and queues a write of the file when it changed something. */
    fun update(transform: (Learned) -> Learned) {
        var changed: Learned? = null
        state.update { old -> transform(old).also { if (it != old) changed = it } }
        changed?.let { snapshot ->
            val now = System.currentTimeMillis()
            writer.execute { writeAtomically(file, LearnedFormat.write(snapshot, now)) }
        }
    }

    /** Forgets every reading, keeping only the file's unknown members. */
    fun clear() = update { it.copy(readings = emptyMap()) }

    companion object {
        private const val FILE = "keyboard/learned.json"

        @Volatile private var instance: MixedLearning? = null

        /** Returns the process's store, reading the file on first use (never on a key). */
        fun get(context: Context): MixedLearning = instance ?: synchronized(this) {
            instance ?: MixedLearning(File(context.applicationContext.filesDir, FILE)).also { instance = it }
        }
    }
}
