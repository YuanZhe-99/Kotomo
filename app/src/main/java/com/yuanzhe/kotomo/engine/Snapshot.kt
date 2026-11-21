package com.yuanzhe.kotomo.engine

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Names a language: the one a span was typed in, or the one a candidate is written in. */
enum class Lang { CHINESE, JAPANESE, ENGLISH }

/** Names where a piece's text came from (the contract's origin codes, in order). */
enum class Origin { RIME, MOZC, ENGLISH, PINYIN_JA, EN_JA, RULE }

/**
 * Holds one piece of a candidate: the byte range of the composition it stands for, what it
 * commits, its [reading] (hiragana for a Japanese piece, otherwise the letters typed), the
 * language the span was [typed] in, the [origin] of the text, whether it entered Mozc
 * [pinned] and whether the person [locked] it (PLAN §5.6).
 */
data class Piece(
    val begin: Int,
    val end: Int,
    val text: String,
    val reading: String,
    val typed: Lang,
    val origin: Origin,
    val pinned: Boolean,
    val locked: Boolean = false,
) {
    /**
     * Whether the piece is letters still being typed, kept as typed after a Japanese sentence
     * (`shur` → しゅ + r, doc/en-us/engine-contract.md): a rule piece that is its own letters.
     */
    val pending: Boolean get() = origin == Origin.RULE && text == reading && text.all { it in 'a'..'z' }
}

/**
 * Holds one candidate: the [text] choosing it commits, the language it is written in, the
 * engine's [cost] (lower first, comparable within one snapshot only), its flags and [pieces].
 */
data class SnapshotCandidate(
    val text: String,
    val target: Lang,
    val cost: Int,
    val flags: Int,
    val pieces: List<Piece>,
) {
    /** Whether a piece of it was typed in another language than it is written in. */
    val crossLanguage: Boolean get() = flags and FLAG_CROSS != 0

    /** Whether the person's earlier choices made it cheaper (PLAN §9.9). */
    val learned: Boolean get() = flags and FLAG_LEARNED != 0

    /** Whether it was reached through fuzzy pinyin or a spelling correction. */
    val fuzzy: Boolean get() = flags and FLAG_FUZZY != 0

    /** Whether this candidate covers the entire composition (`FLAG_SENTENCE`). */
    val sentence: Boolean get() = flags and FLAG_SENTENCE != 0

    /** Whether both Chinese and Japanese engines produced this exact surface (`FLAG_SHARED_ZH_JA`). */
    val sharedZhJa: Boolean get() = flags and FLAG_SHARED_ZH_JA != 0

    companion object {
        const val FLAG_CROSS = 1
        const val FLAG_FUZZY = 2
        const val FLAG_EMOJI = 4
        const val FLAG_SENTENCE = 8
        const val FLAG_LEARNED = 16
        const val FLAG_SHARED_ZH_JA = 32
    }
}

/**
 * Holds what the engine answers to one request (doc/en-us/engine-contract.md): the composition
 * as typed, the cursor's byte offset in it, text to [commit] first, and the candidates, best
 * first, for one [generation] and [stage]. When it answers a request for one span's readings,
 * [focusBegin] and [focusEnd] name that span of the composition and the candidates stand for it
 * alone; both are 0 otherwise. [more] says a later stage of the same generation will list more
 * ([Core.refine], PLAN §5.5).
 */
data class Snapshot(
    val generation: Long,
    val stage: Int,
    val input: String,
    val cursor: Int,
    val commit: String,
    val candidates: List<SnapshotCandidate>,
    val focusBegin: Int = 0,
    val focusEnd: Int = 0,
    val more: Boolean = false,
) {
    /** Whether the candidates stand for one span of the composition rather than all of it. */
    val focused: Boolean get() = focusEnd != 0

    companion object {
        /** The contract version this decoder reads (`kContractVersion` in core/mixed/snapshot.h). */
        const val VERSION = 10

        /**
         * Decodes the bytes the engine returned.
         * Returns: null for another contract version or any buffer that fails a check: the magic,
         * a length that runs past the end, bytes left over, text that is not UTF-8, a language or
         * origin outside its range, a cursor, focus or piece range outside the composition. A snapshot
         * is dropped whole, never half-read.
         * Notes: the app's side of the JNI boundary, so everything is checked.
         */
        fun decode(bytes: ByteArray): Snapshot? = try {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (b.get() != 'K'.code.toByte() || b.get() != 'S'.code.toByte()) return null
            if (b.short.toInt() and 0xffff != VERSION) return null
            val generation = b.long
            val stage = b.get().toInt() and 0xff
            val more = b.get().toInt().also { if (it !in 0..1) return null } == 1
            val inputBytes = b.bytes()
            val input = inputBytes.text()
            val cursor = b.short.toInt() and 0xffff
            if (cursor > inputBytes.size) return null
            val focusBegin = b.short.toInt() and 0xffff
            val focusEnd = b.short.toInt() and 0xffff
            if (focusBegin > focusEnd || focusEnd > inputBytes.size) return null
            val commit = b.bytes().text()
            val candidates = List(b.short.toInt() and 0xffff) {
                val text = b.bytes().text()
                val target = Lang.entries.getOrNull(b.get().toInt() and 0xff) ?: return null
                val cost = b.int
                val flags = b.get().toInt() and 0xff
                val pieces = List(b.short.toInt() and 0xffff) {
                    val begin = b.short.toInt() and 0xffff
                    val end = b.short.toInt() and 0xffff
                    if (begin > end || end > inputBytes.size) return null
                    val pieceText = b.bytes().text()
                    val reading = b.bytes().text()
                    val typed = Lang.entries.getOrNull(b.get().toInt() and 0xff) ?: return null
                    val origin = Origin.entries.getOrNull(b.get().toInt() and 0xff) ?: return null
                    val marks = b.get().toInt().also { if (it !in 0..3) return null }
                    Piece(begin, end, pieceText, reading, typed, origin, pinned = marks and 1 != 0, locked = marks and 2 != 0)
                }
                SnapshotCandidate(text, target, cost, flags, pieces)
            }
            if (b.hasRemaining()) null else Snapshot(generation, stage, input, cursor, commit, candidates, focusBegin, focusEnd, more)
        } catch (_: BufferUnderflowException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }

        /**
         * Decides whether an update may replace the one on screen: only a newer generation, or a
         * later stage of the same generation. A stale update never replaces a newer one, in
         * every language alike (AGENTS.md, "The key path").
         */
        fun replaces(generation: Long, stage: Int, shownGeneration: Long, shownStage: Int): Boolean =
            generation > shownGeneration || (generation == shownGeneration && stage > shownStage)

        /** Reads a string's bytes: a u16 length, then that many bytes. */
        private fun ByteBuffer.bytes(): ByteArray {
            val size = short.toInt() and 0xffff
            if (size > remaining()) throw BufferUnderflowException()
            return ByteArray(size).also { get(it) }
        }

        /** Decodes strict UTF-8; malformed bytes throw instead of becoming replacement characters. */
        private fun ByteArray.text(): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this)).toString()
    }
}
