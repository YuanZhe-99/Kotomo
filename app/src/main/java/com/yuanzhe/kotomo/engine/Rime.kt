package com.yuanzhe.kotomo.engine

/**
 * Bridges librime, the Chinese engine, through `libkotomo_rime.so` (spike S2).
 *
 * One librime instance with one session per process. librime is built without logging, so nothing
 * typed reaches a log.
 *
 * Notes: call every function on the engine thread only; none of them is thread-safe, and
 * [deploy] blocks for seconds on a fresh user directory.
 */
object Rime {
    init {
        System.loadLibrary("kotomo_rime")
    }

    /** Pairs a candidate's [text] with librime's [comment] for it (often empty). */
    data class Candidate(val text: String, val comment: String)

    /**
     * Starts librime and opens the session.
     *
     * Inputs: [sharedDataDir] holds the read-only schema data extracted from the assets;
     * [userDataDir] is writable and receives the deployed `build/`, the user dictionary and
     * `user.yaml`.
     * Returns: whether a session is open.
     * Side effects: calling it again restarts librime.
     */
    external fun initialize(sharedDataDir: String, userDataDir: String): Boolean

    /**
     * Compiles the schemas into `userDataDir/build` and reopens the session.
     *
     * Returns: whether every schema deployed.
     * Side effects: the current composition is lost; unchanged schemas are skipped.
     */
    external fun deploy(): Boolean

    /**
     * Probes the open session with fixed input (`nihao`, the key n) for the diagnostics page.
     * Returns: one line naming the schema, ascii_mode, the probe's candidates and whether the
     * key was handled, or why there is no session. Side effects: clears the composition.
     */
    external fun selfCheck(): String

    /** Names the deploy tasks that failed in the last [deploy], comma-separated, or "". */
    external fun deployFailures(): String

    /** Replaces the whole raw input (for example a pinyin span) and recomputes the candidates. */
    external fun setInput(input: String): Boolean

    /**
     * Sends one key.
     *
     * Inputs: [keycode] is an X11 keysym (`'a'.code`, BackSpace `0xff08`, space `0x20`); [mask]
     * holds librime's modifier bits (Shift 1, Control 4, release `1 shl 30`).
     * Returns: whether librime handled the key; an unhandled key belongs to the app.
     */
    external fun processKey(keycode: Int, mask: Int): Boolean

    /** Returns the preedit text of the composition, or an empty string. */
    external fun getComposition(): String

    /** Returns up to [limit] candidates of the current segment, best first. */
    fun getCandidates(limit: Int): List<Candidate> {
        val flat = nativeCandidates(limit)
        return List(flat.size / 2) { Candidate(flat[2 * it], flat[2 * it + 1]) }
    }

    /** Returns up to [limit] candidates flattened as text, comment, text, comment, …. */
    private external fun nativeCandidates(limit: Int): Array<String>

    /**
     * Selects the candidate at [index], counted from the first candidate rather than the page.
     *
     * Side effects: if the selection completes the input, librime commits; read it with [getCommit].
     */
    external fun selectCandidate(index: Int): Boolean

    /**
     * Forgets what librime learned about the candidate at [index] (counted from the first).
     * Returns: whether a learned entry was removed; dictionary words keep their place.
     */
    external fun deleteCandidate(index: Int): Boolean

    /** Returns the text committed since the last call, or null; the text is consumed. */
    external fun getCommit(): String?

    /** Discards the composition without committing. */
    external fun clear()

    /** Sets a schema switch or runtime option such as `zh_hans`, `ascii_mode` or `full_shape`. */
    external fun setOption(name: String, value: Boolean)

    /**
     * Chooses whether typing may learn.
     *
     * Inputs: [enabled] false (password fields, `IME_FLAG_NO_PERSONALIZED_LEARNING`, incognito)
     * selects a schema whose user dictionary is off: librime neither records commits nor ranks by
     * what it learned.
     * Returns: whether the schema was selected; the choice is kept for sessions opened later.
     * Side effects: discards the composition.
     */
    external fun setLearning(enabled: Boolean): Boolean

    /** Closes the session and stops librime, flushing the user dictionary. */
    external fun shutdown()
}
