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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
        return copy(readings = capped(next, nowUtcMs))
    }

    /**
     * Adds the readings of an imported file ([other]) at [nowUtcMs]: a reading this device does
     * not have is added, and one both have keeps the copy used later, so an import never
     * replaces newer learning with older; this file's unknown members stay.
     * Notes: at most [MAX_READINGS] are kept, as in [committed].
     */
    fun merged(other: Learned, nowUtcMs: Long): Learned {
        val next = readings.toMutableMap()
        for ((key, theirs) in other.readings) {
            val ours = next[key]
            if (ours == null || theirs.usedUtcMs > ours.usedUtcMs) next[key] = theirs
        }
        return copy(readings = capped(next, nowUtcMs))
    }

    /** Drops the weakest readings the person did not rank until at most [MAX_READINGS] are left. */
    private fun capped(next: MutableMap<Pair<String, String>, LearnedReading>, nowUtcMs: Long): Map<Pair<String, String>, LearnedReading> {
        if (next.size > MAX_READINGS) {
            next.values.filter { it.rank == LearnedRank.NORMAL }.sortedBy { it.strength(nowUtcMs) }
                .take(next.size - MAX_READINGS).forEach { next -= it.input to it.text }
        }
        return next
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
            val scope = if (!candidate.sentence && candidate.pieces.isNotEmpty()) {
                raw.substring(0, candidate.pieces.last().end.coerceAtMost(raw.length))
            } else {
                raw
            }
            letters(scope).takeIf { it.isNotEmpty() && candidate.text.isNotEmpty() }?.let { keys += it to candidate.text }
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
 * Frames the learned readings' JSON text for the optional encryption at rest (PLAN §14;
 * `doc/en-us/settings-format.md`, "Learned readings"): magic, container version, a fresh IV,
 * then AES-256-GCM over the text with the first five bytes as associated data.
 *
 * Notes: the same layout as the clipboard history's container, with its own magic and key.
 */
internal object LearnedSeal {
    /** The container layout this build reads and writes. */
    const val CONTAINER = 1
    private val MAGIC = "KLRN".toByteArray(Charsets.US_ASCII)
    private const val IV = 12
    private const val TAG_BITS = 128
    private const val CIPHER = "AES/GCM/NoPadding"

    /** Returns the container version of [bytes], or null when they are not a sealed file. */
    fun container(bytes: ByteArray): Int? =
        if (bytes.size > MAGIC.size + 1 + IV && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) bytes[MAGIC.size].toInt() else null

    /** Encrypts and frames [text]; the cipher picks a fresh random IV (required by a Keystore key). */
    fun seal(text: String, key: SecretKey): ByteArray {
        val head = MAGIC + byteArrayOf(CONTAINER.toByte())
        val cipher = Cipher.getInstance(CIPHER).apply { init(Cipher.ENCRYPT_MODE, key) }
        cipher.updateAAD(head)
        val body = cipher.doFinal(text.toByteArray())
        return head + cipher.iv + body
    }

    /**
     * Decrypts a container of [CONTAINER] layout.
     * Notes: throws GeneralSecurityException when the key does not fit or the bytes were changed.
     */
    fun open(bytes: ByteArray, key: SecretKey): String {
        val head = MAGIC.size + 1
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, head, IV))
        cipher.updateAAD(bytes, 0, head)
        return String(cipher.doFinal(bytes, head + IV, bytes.size - head - IV))
    }
}

/**
 * Keeps Mixed mode's learned readings in memory and in `files/keyboard/learned.json`, or sealed
 * in `learned.kenc` while the person turned encryption on (`privacy.learned_encrypted`), shared
 * by the keyboard and the settings app (one instance per process, [get]).
 *
 * Inputs: [file] the plain file, [sealed] the encrypted one, [key] gives the AES key (the
 * Keystore key in the app, a plain key in tests).
 * Notes: the keyboard records a commit only where the field allows learning (never in a
 * sensitive field, one that asks for no personalized learning, incognito mode or before the
 * first unlock, PLAN §14); the file never leaves the device; Privacy → Clear learning data
 * empties it ([LearningData]). [current] never touches the disk. Each change writes the whole
 * file on one background thread through an atomic replace. The form on disk is the one found
 * when the store opened until [setEncrypted] changes it; the other form is deleted only after
 * the new one was written, so a crash in between loses nothing (the newer file is read).
 */
class MixedLearning internal constructor(private val file: File, private val sealed: File, private val key: () -> SecretKey) {
    /** Whether changes are written sealed; follows the file found at start, then [setEncrypted]. */
    @Volatile private var encrypted = false
    /** A sealed file of a newer container version was found: it is never read or replaced. */
    @Volatile private var newer = false
    private val state = MutableStateFlow(load())
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
            writer.execute { save(snapshot, now) }
        }
    }

    /**
     * Turns the encryption of the file on or off (`privacy.learned_encrypted`): the readings are
     * written in the new form and the other file is deleted after that write succeeded.
     * Notes: called with the setting whenever it is read or changed; nothing happens when the
     * file already has that form. If the Keystore gives no key the plain file stays as it is.
     */
    fun setEncrypted(on: Boolean) {
        if (on == encrypted) return
        encrypted = on
        val now = System.currentTimeMillis()
        writer.execute { save(state.value, now) }
    }

    /**
     * Returns the readings as the text of an export file: the plain versioned JSON of
     * `learned.json`, also while the file on this device is encrypted (its key cannot leave the
     * device, so only a plain file can be read elsewhere).
     */
    fun export(): String = LearnedFormat.write(state.value, System.currentTimeMillis())

    /**
     * Merges an exported file's [text] into the readings ([Learned.merged]).
     * Returns: how many readings the file held, or null when it is not a learned readings file
     * (nothing changes then).
     * Notes: [text] comes from a file the person picked; [LearnedFormat.read] checks every entry.
     */
    fun import(text: String): Int? {
        val read = LearnedFormat.read(text) ?: return null
        update { it.merged(read, System.currentTimeMillis()) }
        return read.readings.size
    }

    /** Waits until every write asked for so far is on disk (tests). */
    internal fun flush() = writer.submit {}.get()

    /**
     * Reads the readings at start: the sealed file when it is the only one or the newer one,
     * otherwise the plain file.
     * Side effects: a file that cannot be decrypted or parsed is moved aside to
     * `<name>.unreadable`; a sealed file of a newer container version is left untouched and
     * nothing is written for this run, so newer data is never overwritten.
     */
    private fun load(): Learned {
        if (!sealed.isFile || (file.isFile && file.lastModified() > sealed.lastModified())) {
            return readVersioned(file, LearnedFormat::read) ?: Learned()
        }
        encrypted = true
        val bytes = runCatching { sealed.readBytes() }.getOrNull() ?: return Learned()
        val container = LearnedSeal.container(bytes)
        if (container != null && container > LearnedSeal.CONTAINER) {
            newer = true
            return Learned()
        }
        val read = if (container == null) null else runCatching { LearnedFormat.read(LearnedSeal.open(bytes, key())) }.getOrNull()
        if (read == null) runCatching { Files.move(sealed.toPath(), File(sealed.path + ".unreadable").toPath(), StandardCopyOption.REPLACE_EXISTING) }
        return read ?: Learned()
    }

    /** Writes [snapshot] in the form in force and then deletes the other form (writer thread). */
    private fun save(snapshot: Learned, nowUtcMs: Long) {
        if (newer) return
        val text = LearnedFormat.write(snapshot, nowUtcMs)
        if (encrypted) {
            val bytes = runCatching { LearnedSeal.seal(text, key()) }.getOrNull() ?: return
            if (writeAtomically(sealed, bytes)) file.delete()
        } else if (writeAtomically(file, text)) {
            sealed.delete()
        }
    }

    /** Forgets every reading, keeping only the file's unknown members. */
    fun clear() = update { it.copy(readings = emptyMap()) }

    companion object {
        private const val FILE = "keyboard/learned.json"
        private const val SEALED = "keyboard/learned.kenc"
        private const val KEY_ALIAS = "kotomo_learned"

        @Volatile private var instance: MixedLearning? = null

        /** Returns the process's store, reading the file on first use (never on a key). */
        fun get(context: Context): MixedLearning = instance ?: synchronized(this) {
            val dir = context.applicationContext.filesDir
            instance ?: MixedLearning(File(dir, FILE), File(dir, SEALED), ::keystoreKey).also { instance = it }
        }

        /**
         * Returns the learned readings' AES-256-GCM key from the Android Keystore, creating it
         * when missing; the key never leaves the Keystore.
         */
        private fun keystoreKey(): SecretKey {
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
