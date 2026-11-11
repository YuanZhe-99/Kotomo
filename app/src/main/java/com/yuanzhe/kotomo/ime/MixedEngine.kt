package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.engine.Core
import com.yuanzhe.kotomo.engine.English
import com.yuanzhe.kotomo.engine.Mozc
import com.yuanzhe.kotomo.engine.Snapshot

/**
 * Mixed mode's side of the engine thread: owns the coordinator ([Core]) and keeps the composition
 * it holds in step with the one the keyboard shows (PLAN §8.3, §9; doc/en-us/engine-contract.md).
 *
 * The keyboard's composition is the truth; each update is sent as the smallest request that gets
 * the engine there (one key with its touch, one backspace, a clear, or the whole composition).
 *
 * Notes: engine thread only, like the engines it reaches. It converts in librime's one session,
 * so the caller runs it only while no Chinese-mode composition is open. Nothing here learns, and
 * nothing typed is logged.
 */
internal class MixedEngine {
    /** Names the file that holds the compiled cross-language lexicon (an asset inside the APK). */
    data class Lexicon(val path: String, val offset: Long, val length: Long)

    /** The engines the open coordinator was given; it is reopened when one of them changes. */
    private data class Parts(val rime: Boolean, val mozc: Mozc?, val english: English?, val lexicon: Lexicon?)

    private var core: Core? = null
    private var parts: Parts? = null
    private var options: Core.Options? = null
    private var held = ""
    private var last: Snapshot? = null
    private var generation = 0L

    /** Whether a coordinator is open (false before [attach], or when its library did not open). */
    val open: Boolean get() = core != null

    /** The number of entries of the cross-language lexicon in use; 0 without one. */
    val lexiconEntries: Int get() = core?.lexiconEntries ?: 0

    /**
     * Opens the coordinator over the engines that are there, or reopens it when they changed
     * (Mozc opened later, the first unlock, the learning data cleared).
     * Inputs: [rime] whether librime has a session; [mozc] and [english] the open engines or null.
     * Side effects: a reopened coordinator starts with an empty composition and default options;
     * the next [type] sends both again.
     */
    fun attach(rime: Boolean, mozc: Mozc?, english: English?, lexicon: Lexicon?) {
        val wanted = Parts(rime, mozc, english, lexicon)
        if (core != null && parts == wanted) return
        detach()
        core = runCatching { Core.open(rime, mozc, english, lexicon?.path, lexicon?.offset ?: 0, lexicon?.length ?: 0) }.getOrNull()
        parts = wanted
    }

    /**
     * Closes the coordinator. Notes: call it before closing Mozc or the English engine, whose
     * handles it holds.
     */
    fun detach() {
        core?.close()
        core = null
        parts = null
        options = null
        held = ""
        last = null
    }

    /**
     * Brings the engine's composition to [raw] and returns its snapshot.
     * Inputs: [raw] the keyboard's composition (letters, spaces and apostrophes); [touches] one
     * touch position per character where known, in the English engine's layout space; [wanted]
     * the options in force.
     * Returns: the snapshot of [raw], or null without a coordinator.
     * Notes: one request in the common cases (a key more, a key less, nothing left); the whole
     * composition only when the two have diverged, for example after a mode switch.
     */
    fun type(raw: String, touches: List<Pair<Int, Int>?>, wanted: Core.Options): Snapshot? {
        val c = core ?: return null
        if (options != wanted) {
            last = c.options(++generation, wanted)
            options = wanted
        }
        val current = last
        val snapshot = when {
            raw == held && current != null && current.input == raw -> current
            raw.isEmpty() -> c.clear(++generation)
            raw.length == held.length + 1 && raw.startsWith(held) -> {
                val touch = touches.getOrNull(raw.length - 1)
                c.key(++generation, raw.last(), touch?.first ?: Core.NO_COORDINATE, touch?.second ?: Core.NO_COORDINATE)
            }
            held.length == raw.length + 1 && held.startsWith(raw) -> c.backspace(++generation)
            else -> c.set(++generation, raw)
        }
        last = snapshot
        held = snapshot?.input.orEmpty()
        return snapshot
    }

    /**
     * Decides what choosing a candidate commits, and empties the engine's composition.
     * Inputs: [raw], [touches] and [wanted] as for [type]; [index] the candidate's index in the
     * snapshot of [raw], or null for the best candidate; [expected] the text the person tapped,
     * or null.
     * Returns: [expected] when given (what was tapped is what is committed); otherwise the best
     * candidate's text, or [raw] without its outer spaces when there is no candidate or no
     * coordinator.
     * Side effects: the engine is told which candidate was chosen when it still holds the
     * snapshot the choice was made on (learning will start from here); nothing is learned yet.
     */
    fun commit(raw: String, touches: List<Pair<Int, Int>?>, wanted: Core.Options, index: Int?, expected: String?): String {
        val snapshot = type(raw, touches, wanted)
        val c = core
        val chosen = index ?: 0
        val text = snapshot?.candidates?.getOrNull(chosen)?.text
        if (c != null) {
            last = if (snapshot != null && text != null && (expected == null || expected == text)) {
                c.select(++generation, snapshot.generation, chosen)
            } else {
                c.clear(++generation)
            }
            held = last?.input.orEmpty()
        }
        return expected ?: text ?: raw.trim()
    }
}
