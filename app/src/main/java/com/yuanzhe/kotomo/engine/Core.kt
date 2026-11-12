package com.yuanzhe.kotomo.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The app's one facade over the mixed-input coordinator in `libkotomo_core.so` (core/mixed): a
 * request goes in, a [Snapshot] comes out, one JNI crossing per key
 * (doc/en-us/engine-contract.md, contract version [Snapshot.VERSION]).
 *
 * The coordinator links no engine: [open] hands it the function tables of librime, Mozc and the
 * English core, read from their own libraries, so it calls them without another JNI crossing.
 *
 * Notes: not thread-safe; only the engine thread calls it (AGENTS.md, "The key path"). It shares
 * librime's one session: a conversion replaces and clears that session's composition, so a
 * Chinese-mode composition must not be open at the same time. Nothing here learns. Close it
 * before closing the [Mozc] or [English] it was opened with.
 */
class Core private constructor(private var handle: Long) : AutoCloseable {

    /** Holds the switches and weights of one request ([options]); the defaults change nothing. */
    data class Options(
        /** 0 conservative, 1 balanced, 2 aggressive (PLAN §2.3). */
        val level: Int = 1,
        val pinyinToJapanese: Boolean = true,
        val englishToKanji: Boolean = true,
        val englishToKatakana: Boolean = true,
        /** False: a cross-language word is offered only when it is the whole composition. */
        val wholeSentence: Boolean = true,
        /** The field must leave no trace: Mozc converts incognito. */
        val incognito: Boolean = false,
        /** How far each language's weight was moved from its default, −100…100. */
        val chineseWeight: Int = 0,
        val japaneseWeight: Int = 0,
        val englishWeight: Int = 0,
        /** Whether candidates written in each language are wanted; Japanese mode asks for Japanese only. */
        val chinese: Boolean = true,
        val japanese: Boolean = true,
        val english: Boolean = true,
    )

    /** The number of entries of the cross-language lexicon in use; 0 without one. */
    val lexiconEntries: Int get() = nativeEntries(checkOpen())

    /** Replaces the whole composition with [composition] (a mode switch hands its letters over). */
    fun set(generation: Long, composition: String): Snapshot? =
        call(generation, SET) { it.string(composition.take(MAX_COMPOSITION)) }

    /**
     * Types one character: a letter, or a space or apostrophe as a boundary hint; anything else
     * changes nothing.
     * Inputs: [x] and [y], where the key was touched in the English engine's layout space
     * ([English.touchPoint]), or [NO_COORDINATE].
     */
    fun key(generation: Long, char: Char, x: Int = NO_COORDINATE, y: Int = NO_COORDINATE): Snapshot? =
        call(generation, KEY) { it.putInt(char.code).putShort(x.toShort()).putShort(y.toShort()) }

    /** Deletes the last character of the composition. */
    fun backspace(generation: Long): Snapshot? = call(generation, BACKSPACE) {}

    /** Drops the composition without committing. */
    fun clear(generation: Long): Snapshot? = call(generation, CLEAR) {}

    /** Sets the level, the cross-language switches, the target languages, the weights and the field's privacy. */
    fun options(generation: Long, options: Options): Snapshot? = call(generation, OPTIONS) {
        val switches = (if (options.pinyinToJapanese) 1 else 0) or
            (if (options.englishToKanji) 2 else 0) or
            (if (options.englishToKatakana) 4 else 0) or
            (if (options.wholeSentence) 8 else 0) or
            (if (options.incognito) 16 else 0)
        val targets = (if (options.chinese) 1 else 0) or (if (options.japanese) 2 else 0) or (if (options.english) 4 else 0)
        it.put(options.level.coerceIn(0, 2).toByte()).put(switches.toByte()).put(targets.toByte())
        for (weight in intArrayOf(options.chineseWeight, options.japaneseWeight, options.englishWeight)) {
            it.put(weight.coerceIn(-100, 100).toByte())
        }
    }

    /**
     * Tells the engine the text before the cursor.
     * Notes: the caller never sends it for a sensitive field (PLAN §14); nothing reads it yet.
     */
    fun context(generation: Long, before: String): Snapshot? =
        call(generation, CONTEXT) { it.string(before.takeLast(CONTEXT_CHARS)) }

    /**
     * Chooses candidate [index] of the snapshot of generation [shown].
     * Returns: a snapshot whose [Snapshot.commit] is the candidate's text and whose composition
     * is empty; when [shown] is no longer the engine's last snapshot or has no such candidate,
     * nothing is committed and the snapshot shows the composition as it is.
     */
    fun select(generation: Long, shown: Long, index: Int): Snapshot? =
        call(generation, SELECT) { it.putLong(shown).putShort(index.toShort()) }

    /** Releases the coordinator and unmaps the lexicon; later calls fail. */
    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0
    }

    /** Encodes one request ("KR", version, generation, command, fields) and decodes the answer. */
    private inline fun call(generation: Long, command: Int, fields: (ByteBuffer) -> Unit): Snapshot? {
        val buffer = ByteBuffer.allocate(REQUEST_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('K'.code.toByte()).put('R'.code.toByte()).putShort(Snapshot.VERSION.toShort())
        buffer.putLong(generation).put(command.toByte())
        fields(buffer)
        return nativeCall(checkOpen(), buffer.array().copyOf(buffer.position()))?.let(Snapshot::decode)
    }

    private fun checkOpen(): Long = handle.also { check(it != 0L) { "Core is closed" } }

    companion object {
        /** Marks a key without a touch position. */
        const val NO_COORDINATE = -1

        /** The longest composition the engine converts, in letters and delimiters. */
        const val MAX_COMPOSITION = 256

        /** How much of the text before the cursor is handed over. */
        private const val CONTEXT_CHARS = 64

        /** Room for the largest request: the header and a string of the longest composition. */
        private const val REQUEST_BYTES = 16 + 3 * MAX_COMPOSITION + 2

        private const val SET = 0
        private const val KEY = 1
        private const val BACKSPACE = 2
        private const val CLEAR = 3
        private const val OPTIONS = 4
        private const val CONTEXT = 5
        private const val SELECT = 6

        init {
            System.loadLibrary("kotomo_core")
        }

        /**
         * Opens the coordinator over the engines that are there.
         * Inputs: [rime] whether librime is initialized (its session is process-wide); [mozc] and
         * [english] the open engines, or null; [lexicon] the file that holds the compiled
         * cross-language lexicon at [lexiconOffset] for [lexiconLength] bytes (the rest of the
         * file when 0), or null for none.
         * Returns: null when a library's function table has another version than the coordinator
         * was built for (the libraries of one APK always match).
         * Side effects: loads `libkotomo_core.so` on first use; maps the lexicon read-only.
         */
        fun open(
            rime: Boolean,
            mozc: Mozc?,
            english: English?,
            lexicon: String? = null,
            lexiconOffset: Long = 0,
            lexiconLength: Long = 0,
        ): Core? = nativeOpen(
            if (rime) Rime.api() else 0,
            if (mozc != null) Mozc.api() else 0,
            mozc?.nativeHandle ?: 0,
            if (english != null) English.api() else 0,
            english?.nativeHandle ?: 0,
            lexicon,
            lexiconOffset,
            lexiconLength,
        ).takeIf { it != 0L }?.let(::Core)

        /** Writes a string as the contract does: a u16 byte length, then UTF-8. */
        private fun ByteBuffer.string(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            putShort(bytes.size.toShort()).put(bytes)
        }

        @JvmStatic
        private external fun nativeOpen(
            rimeApi: Long,
            mozcApi: Long,
            mozcEngine: Long,
            englishApi: Long,
            englishEngine: Long,
            lexiconPath: String?,
            offset: Long,
            length: Long,
        ): Long

        @JvmStatic private external fun nativeEntries(handle: Long): Int

        @JvmStatic private external fun nativeCall(handle: Long, request: ByteArray): ByteArray?

        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
