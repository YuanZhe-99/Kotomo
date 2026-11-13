package com.yuanzhe.kotomo.engine

/**
 * Wraps Kotomo's Mozc glue in `libkotomo_mozc.so` (core/mozc): Japanese conversion through a Mozc
 * session, and whole-sentence conversion with pinned spans at Mozc's converter layer.
 *
 * Notes: not thread-safe; only the engine thread calls it (AGENTS.md, "The key path"). Nothing is
 * committed to Mozc, so its history learns nothing; `incognito` also switches off every mutable
 * operation inside Mozc and is set for sensitive fields.
 */
class Mozc private constructor(private var handle: Long) : AutoCloseable {

    /** Holds one candidate: its surface and the hiragana reading it covers. */
    data class Candidate(val value: String, val reading: String)

    /** Holds one segment (bunsetsu) with its candidates, best first; `pinned` marks a caller's span. */
    data class Segment(val reading: String, val pinned: Boolean, val candidates: List<Candidate>)

    /**
     * Describes one span of a reading: a non-null [surface] pins the span to it; otherwise a true
     * [fixed] keeps the span one segment (bunsetsu shrinking and extending) and Mozc converts it.
     */
    data class Span(val reading: String, val surface: String? = null, val fixed: Boolean = false)

    /** Returns the version of the loaded Mozc data set. */
    val dataVersion: String
        get() = checkNotNull(nativeDataVersion(checkOpen()))

    /**
     * Converts typed romaji or hiragana as a Mozc session would.
     *
     * Returns: the segments, of which only the first carries alternatives; null when nothing
     * converts.
     */
    fun sessionConvert(typed: String, incognito: Boolean): List<Segment>? =
        nativeSessionConvert(checkOpen(), typed, incognito)?.let(::decode)

    /**
     * Converts a hiragana reading whose pinned spans keep their surface; Mozc converts the rest
     * around them (PLAN.md §5.4).
     *
     * Returns: every segment with its candidates; null for no spans, an empty reading or no result.
     */
    fun pinnedConvert(spans: List<Span>, incognito: Boolean): List<Segment>? =
        nativePinnedConvert(
            checkOpen(),
            Array(spans.size) { spans[it].reading },
            Array(spans.size) { spans[it].surface.orEmpty() },
            BooleanArray(spans.size) { spans[it].fixed },
            incognito,
        )?.let(::decode)

    /**
     * Predicts words and phrases that start with a hiragana [reading].
     * Returns: candidates best first with the reading each covers; empty when there is none.
     * Notes: nothing is learned.
     */
    fun predict(reading: String, incognito: Boolean): List<Candidate> {
        val flat = nativePredict(checkOpen(), reading, incognito) ?: return emptyList()
        return List(flat.size / 2) { Candidate(flat[2 * it], flat[2 * it + 1]) }
    }

    /**
     * The engine's native handle, for [Core]: the mixed-input coordinator converts through this
     * engine with [api]. Notes: valid until [close]; close the coordinator first.
     */
    internal val nativeHandle: Long get() = checkOpen()

    /** Releases the native engine; later calls fail. Side effects: frees Mozc's memory. */
    /** What Mozc's dictionary says about a word: the [entry] and [reading] that matched, and id.def's [pos] fields. */
    data class Description(val entry: String, val reading: String, val pos: String)

    /**
     * Describes [surface] read as [reading] from Mozc's dictionary (PLAN §10.1, §10.2): its part of
     * speech and, for a verb or adjective, its conjugation type and form. Trailing kana the two
     * share are dropped until an entry matches (行きます → 行き).
     * Returns: the description, or null when Mozc has no such word.
     * Notes: engine thread; nothing is learned.
     */
    fun describe(surface: String, reading: String): Description? =
        nativeDescribe(checkOpen(), surface, reading)?.takeIf { it.size == 3 }?.let { Description(it[0], it[1], it[2]) }

    override fun close() {
        nativeClose(handle)
        handle = 0
    }

    private fun checkOpen(): Long = handle.also { check(it != 0L) { "Mozc is closed" } }

    companion object {
        init {
            System.loadLibrary("kotomo_mozc")
        }

        /**
         * Opens Mozc with its data file.
         *
         * Inputs: [dataPath] is the Mozc data set (mozc.data); [profileDir] is a private writable
         * directory for Mozc's user files.
         * Returns: null when the data cannot be loaded.
         */
        fun open(dataPath: String, profileDir: String): Mozc? =
            nativeOpen(dataPath, profileDir).takeIf { it != 0L }?.let(::Mozc)

        /**
         * Returns the address of the library's function table for the mixed-input coordinator
         * (`core/mozc/kotomo_mozc_api.h`). Notes: valid for the life of the process.
         */
        internal fun api(): Long = nativeApi()

        /** Decodes the flat array from the JNI layer: reading, pinned, n, then n (value, reading). */
        private fun decode(fields: Array<String>): List<Segment> = buildList {
            var i = 0
            while (i < fields.size) {
                val reading = fields[i]
                val pinned = fields[i + 1] == "1"
                val count = fields[i + 2].toInt()
                i += 3
                val candidates = List(count) { Candidate(fields[i + 2 * it], fields[i + 2 * it + 1]) }
                i += 2 * count
                add(Segment(reading, pinned, candidates))
            }
        }

        @JvmStatic private external fun nativeOpen(dataPath: String, profileDir: String): Long

        @JvmStatic private external fun nativeApi(): Long

        @JvmStatic private external fun nativeClose(handle: Long)

        @JvmStatic private external fun nativeDataVersion(handle: Long): String?

        @JvmStatic
        private external fun nativeSessionConvert(
            handle: Long,
            typed: String,
            incognito: Boolean,
        ): Array<String>?

        @JvmStatic
        private external fun nativePinnedConvert(
            handle: Long,
            readings: Array<String>,
            surfaces: Array<String>,
            fixed: BooleanArray,
            incognito: Boolean,
        ): Array<String>?

        @JvmStatic
        private external fun nativePredict(handle: Long, reading: String, incognito: Boolean): Array<String>?

        @JvmStatic
        private external fun nativeDescribe(handle: Long, surface: String, reading: String): Array<String>?
    }
}
