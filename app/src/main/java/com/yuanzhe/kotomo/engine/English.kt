package com.yuanzhe.kotomo.engine

/**
 * Suggests English words from a LatinIME binary dictionary through `libkotomo_english.so`.
 *
 * Notes: engine thread only — an instance is not thread-safe and every call runs the native
 * decoder synchronously. Touch points are in the engine's built-in QWERTY space
 * ([KEYBOARD_WIDTH] × [KEYBOARD_HEIGHT], keys [KEY_WIDTH] × [KEY_HEIGHT], rows `qwertyuiop`,
 * `asdfghjkl` offset half a key, `zxcvbnm` offset one and a half keys, then the space bar); the
 * caller scales its own key geometry into it. Nothing typed is logged.
 */
class English private constructor(private var handle: Long) : AutoCloseable {

    /** Pairs a suggested word with the engine's score for it (higher is better). */
    data class Suggestion(val word: String, val score: Int)

    /**
     * Suggests corrections and completions for [typed], or predicts the next word when [typed]
     * is empty.
     * Inputs: [xs] and [ys] — optional touch points, one per code point of [typed], in the
     * built-in layout's space; without them each letter counts as a tap on its key's centre.
     * [previous] — the word before this one, for bigram context, or null.
     * Returns: up to [MAX_RESULTS] suggestions, best first; empty for input longer than
     * [MAX_WORD_LENGTH] code points.
     */
    fun suggestions(
        typed: String,
        xs: IntArray? = null,
        ys: IntArray? = null,
        previous: String? = null,
    ): List<Suggestion> {
        check(handle != 0L) { "English engine is closed" }
        val scores = IntArray(MAX_RESULTS)
        val words = EnglishJni.suggest(handle, typed, xs, ys, previous, scores) ?: return emptyList()
        return words.mapIndexed { i, word -> Suggestion(word, scores[i]) }
    }

    /**
     * The engine's native handle, for [Core]: the mixed-input coordinator looks words up in this
     * dictionary through [api]. Notes: valid until [close]; close the coordinator first.
     */
    internal val nativeHandle: Long get() = handle

    /**
     * Releases the native dictionary and session.
     * Side effects: unmaps the dictionary; later calls to [suggestions] throw.
     */
    override fun close() {
        if (handle != 0L) {
            EnglishJni.close(handle)
            handle = 0L
        }
    }

    companion object {
        /** The most suggestions one call returns (LatinIME's MAX_RESULTS). */
        const val MAX_RESULTS = 18

        /** The longest word the engine accepts, in code points (LatinIME's MAX_WORD_LENGTH). */
        const val MAX_WORD_LENGTH = 48

        /** Width of the built-in layout's coordinate space. */
        const val KEYBOARD_WIDTH = 1000

        /** Height of the built-in layout's coordinate space (four rows). */
        const val KEYBOARD_HEIGHT = 600

        /** Width of one letter key in the built-in layout. */
        const val KEY_WIDTH = 100

        /** Height of one key row in the built-in layout. */
        const val KEY_HEIGHT = 150

        /** The built-in layout's letter rows and the offset of each row's first key. */
        private val rows = listOf("qwertyuiop" to 0, "asdfghjkl" to KEY_WIDTH / 2, "zxcvbnm" to KEY_WIDTH * 3 / 2)

        /**
         * Maps a tap on [letter]'s key to the built-in layout's space.
         * Inputs: [dx] and [dy], where the tap fell on the key as a fraction of its width and
         * height from its centre (−0.5…0.5; a little beyond for taps in the gaps).
         * Returns: the point, or null for a character the layout has no key for.
         * Notes: the keyboard's own key sizes vary with the screen; the fraction of the key keeps
         * what matters for correction, how close the tap came to the neighbouring key.
         */
        fun touchPoint(letter: Char, dx: Float, dy: Float): Pair<Int, Int>? {
            val c = letter.lowercaseChar()
            rows.forEachIndexed { row, (keys, offset) ->
                val i = keys.indexOf(c)
                if (i >= 0) {
                    val x = offset + i * KEY_WIDTH + KEY_WIDTH / 2 + (dx.coerceIn(-1f, 1f) * KEY_WIDTH).toInt()
                    val y = row * KEY_HEIGHT + KEY_HEIGHT / 2 + (dy.coerceIn(-1f, 1f) * KEY_HEIGHT).toInt()
                    return x.coerceIn(0, KEYBOARD_WIDTH - 1) to y.coerceIn(0, KEYBOARD_HEIGHT - 1)
                }
            }
            return null
        }

        /**
         * Returns the address of the library's function table for the mixed-input coordinator
         * (`core/english/jni/english_api.h`). Notes: valid for the life of the process.
         */
        internal fun api(): Long = EnglishJni.api()

        /**
         * Opens the dictionary stored in [path] from byte [offset] for [length] bytes (the rest
         * of the file when negative), so an uncompressed APK asset can be read in place.
         * Returns: the engine, or null when the file is missing or is not a LatinIME dictionary.
         * Side effects: loads `libkotomo_english.so` on first use; memory-maps the file.
         */
        fun open(path: String, offset: Long = 0L, length: Long = -1L): English? =
            EnglishJni.open(path, offset, length).takeIf { it != 0L }?.let(::English)
    }
}

/**
 * Declares the native entry points of `libkotomo_english.so` (core/english/jni/english_jni.cpp).
 * Notes: the class and method names are the JNI symbols, so they must not be renamed; R8 keeps
 * classes with native methods by default.
 */
private object EnglishJni {
    init {
        System.loadLibrary("kotomo_english")
    }

    /** Opens a dictionary; returns a native handle or 0. */
    @JvmStatic external fun open(path: String, offset: Long, length: Long): Long

    /** Fills [outScores] and returns the suggested words, best first; null on invalid input. */
    @JvmStatic external fun suggest(
        handle: Long,
        typed: String,
        xs: IntArray?,
        ys: IntArray?,
        previous: String?,
        outScores: IntArray,
    ): Array<String>?

    /** Returns the address of the coordinator's function table. */
    @JvmStatic external fun api(): Long

    /** Frees a native handle from [open]. */
    @JvmStatic external fun close(handle: Long)
}
