package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.engine.Core
import com.yuanzhe.kotomo.engine.English
import com.yuanzhe.kotomo.engine.Mozc
import com.yuanzhe.kotomo.engine.Snapshot
import com.yuanzhe.kotomo.engine.SnapshotCandidate

/**
 * Mixed mode's side of the engine thread: owns the coordinator ([Core]) and keeps the composition
 * it holds in step with the one the keyboard shows (PLAN §8.3, §9; doc/en-us/engine-contract.md).
 *
 * The keyboard's composition is the truth; each update is sent as the smallest request that gets
 * the engine there (one key with its touch, one backspace, a clear, or the whole composition).
 *
 * Notes: engine thread only, like the engines it reaches. It converts in librime's one session,
 * so the caller runs it only while no Chinese-mode composition is open. It learns nothing by
 * itself: the keyboard tells it what the person's history adds ([learned]), and nothing typed is
 * logged.
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
    /** The text before the composition the engine was last told. */
    private var context = ""
    private var last: Snapshot? = null
    /** The engine's last answer listed one span's readings, so [last] must be asked for again. */
    private var spanShown = false
    private var generation = 0L
    /** What the person's history adds to readings: as wanted, and as the open coordinator was told. */
    private var learned: Map<Pair<String, String>, Int> = emptyMap()
    private var told: Map<Pair<String, String>, Int> = emptyMap()

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
        tellLearned()
    }

    /**
     * Sets what the person's history adds to each reading's cost ([MixedLearning]); the engine is
     * told the differences, now and again after every reopening.
     */
    fun learned(adjustments: Map<Pair<String, String>, Int>) {
        learned = adjustments
        tellLearned()
    }

    /** Sends the readings that changed since the open coordinator was last told. */
    private fun tellLearned() {
        val c = core ?: return
        if (learned == told) return
        var answer: Snapshot? = null
        for (key in told.keys - learned.keys) answer = c.learn(++generation, key.first, key.second, 0)
        for ((key, adjust) in learned) if (told[key] != adjust) answer = c.learn(++generation, key.first, key.second, adjust)
        told = learned
        // Each answer is the whole composition's snapshot under the new history.
        last = answer
        held = answer?.input.orEmpty()
        spanShown = false
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
        context = ""
        told = emptyMap()
        last = null
        spanShown = false
    }

    /**
     * Tells the engine the text before the composition, which makes that text's language a
     * little cheaper (PLAN §9.7). Notes: one request, and only when the text changed; the caller
     * passes an empty string wherever the field may leave no trace (PLAN §14).
     */
    fun context(before: String) {
        val c = core ?: return
        if (before == context) return
        context = before
        last = c.context(++generation, before)
        held = last?.input.orEmpty()
    }

    /**
     * Lists the readings of the span [begin, end) of [raw] (PLAN §5.6).
     * Returns: a snapshot whose candidates stand for that span ([Snapshot.focused]); null without
     * a coordinator or when the span cuts a piece the person locked.
     */
    fun alternatives(raw: String, touches: List<Pair<Int, Int>?>, wanted: Core.Options, begin: Int, end: Int): Snapshot? {
        type(raw, touches, wanted) ?: return null
        val span = core?.alternatives(++generation, begin, end) ?: return null
        if (span.focused) spanShown = true else last = span
        return span.takeIf { it.focused }
    }

    /**
     * Locks the span of [span] (from [alternatives]) to its candidate [index], or to the letters
     * as typed for [Core.AS_TYPED], and converts the rest around it.
     * Returns: the snapshot of the whole composition; unchanged when the engine has answered
     * another request since [span].
     */
    fun lock(span: Snapshot, index: Int): Snapshot? = answer(core?.lock(++generation, span.generation, index))

    /** Drops every locked piece of [raw]: it converts afresh. Returns: its snapshot. */
    fun restore(raw: String, touches: List<Pair<Int, Int>?>, wanted: Core.Options): Snapshot? {
        type(raw, touches, wanted) ?: return null
        return answer(core?.restore(++generation))
    }

    /**
     * Commits the best candidate's pieces of [raw] up to byte [end], a piece boundary inside it.
     * Returns: a snapshot whose [Snapshot.commit] is their text and whose input is the rest,
     * still composed with its locks; one with an empty commit when the boundary does not hold.
     */
    fun commitPrefix(raw: String, touches: List<Pair<Int, Int>?>, wanted: Core.Options, end: Int): Snapshot? {
        val shown = type(raw, touches, wanted) ?: return null
        return answer(core?.commitPrefix(++generation, shown.generation, 0, end))
    }

    /**
     * Takes back the last commit. Returns: the snapshot of the composition it brought back, with
     * its locked pieces; one with an empty input when there is nothing to take back.
     */
    fun undo(): Snapshot? = answer(core?.undo(++generation))

    /** Records an answer about the whole composition as the engine's state and returns it in full. */
    private fun answer(snapshot: Snapshot?): Snapshot? {
        // The person asked for this one (a piece chosen, the input restored, a commit taken
        // back): it is shown complete, not in two stages.
        val refined = if (snapshot?.more == true) core?.refine(snapshot.generation) ?: snapshot else snapshot
        val full = if (snapshot != null && refined != null && snapshot.commit.isNotEmpty() && refined.commit.isEmpty()) {
            refined.copy(commit = snapshot.commit)
        } else {
            refined
        }
        last = full
        held = full?.input.orEmpty()
        spanShown = false
        return full
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
        if (options != wanted || spanShown) {
            // Sending the options changes nothing else and answers with the whole composition.
            last = c.options(++generation, wanted)
            options = wanted
            spanShown = false
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
     * Brings the later stage of the engine's last answer (PLAN §5.5).
     * Returns: the full list for the same composition; null when the last answer was already
     * complete, was about one span, or there is no coordinator.
     */
    fun refine(): Snapshot? {
        val shown = last?.takeIf { it.more && !spanShown } ?: return null
        return core?.refine(shown.generation)?.also { last = it }
    }

    /** Result of committing or flushing a Mixed candidate: committed text, candidate, and remainder. */
    data class Commit(val text: String, val candidate: SnapshotCandidate?, val next: Snapshot?)

    /**
     * Decides what choosing a candidate commits, and selects or clears the engine's composition.
     * Inputs: [raw], [touches] and [wanted] as for [type]; [index] the candidate's index in the
     * snapshot of [raw], or null for the best candidate; [expected] the text the person tapped,
     * or null.
     * Returns: a [Commit] holding the committed text ([expected] when given, otherwise the chosen
     * candidate's text or [raw] trimmed), the matched [SnapshotCandidate], and the engine's
     * post-select [Snapshot] (whose [Snapshot.input] holds any unconsumed remainder after a prefix
     * commit).
     * Side effects: the engine is told which candidate was chosen when it still holds the
     * snapshot the choice was made on; nothing is learned here.
     */
    fun commit(raw: String, touches: List<Pair<Int, Int>?>, wanted: Core.Options, index: Int?, expected: String?): Commit {
        val snapshot = type(raw, touches, wanted)
        val c = core
        val chosen = index ?: 0
        val candidate = snapshot?.candidates?.getOrNull(chosen)
        val text = candidate?.text
        val matched = candidate?.takeIf { expected == null || expected == text }
        var next: Snapshot? = null
        if (c != null) {
            next = if (snapshot != null && text != null && matched != null) {
                c.select(++generation, snapshot.generation, chosen)
            } else {
                c.clear(++generation)
            }
            last = next
            held = next?.input.orEmpty()
        }
        return Commit(expected ?: next?.commit?.takeIf { it.isNotEmpty() } ?: text ?: raw.trim(), matched, next)
    }
}
