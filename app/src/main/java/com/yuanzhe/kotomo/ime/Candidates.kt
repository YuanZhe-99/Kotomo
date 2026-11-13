package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.engine.Snapshot
import com.yuanzhe.kotomo.engine.SnapshotCandidate
import com.yuanzhe.kotomo.engine.Lang
import com.yuanzhe.kotomo.engine.Mozc
import com.yuanzhe.kotomo.engine.Origin

/**
 * Builds the candidate lists the candidate bar shows from each engine's results.
 *
 * Notes: pure functions, so the conversion corpus (tests/conversion) runs the same assembly on the
 * host that the keyboard runs on the device.
 */
internal object Candidates {
    /** The most emoji suggested for one composition. */
    const val EMOJI_SUGGESTIONS = 3
    /** The place emoji take in the list: after the two most likely words. */
    const val EMOJI_POSITION = 2
    /** The most Chinese candidates read from librime per update. */
    const val CHINESE_LIMIT = 30

    /**
     * One Japanese candidate: what it commits, the hiragana reading it stands for, whether it is
     * [cross]-language (typed as pinyin or English, from the coordinator) and, for those, whether
     * the coordinator ranked it before its own reading of the letters as romaji ([leads]).
     */
    data class Japanese(val text: String, val reading: String, val cross: Boolean = false, val leads: Boolean = false, val particle: Boolean = false)

    /** The most cross-language candidates Japanese mode adds to Mozc's own. */
    const val JAPANESE_CROSS = 3

    /**
     * Picks Japanese mode's cross-language candidates out of the coordinator's [snapshot] of the
     * same letters (PLAN §2.3: Japanese mode also reads pinyin and English as Japanese).
     * Returns: its candidates written in Japanese that hold a cross-language piece, best first,
     * each with its reading; those the coordinator put before its first candidate typed as
     * romaji throughout are marked [Japanese.leads]. Before them comes the coordinator's best
     * sentence typed as romaji throughout when it reads a `wa`, `o` or `e` as the particle は, を
     * or へ ([Japanese.particle]): Mozc's own session reads those letters as わ, お and え
     * (`watashiwanihonjin`, PLAN §5.4). Empty for null.
     */
    fun japaneseCross(snapshot: Snapshot?): List<Japanese> {
        val all = snapshot?.candidates.orEmpty().filter { it.target == Lang.JAPANESE }
        val plain = all.indexOfFirst { !it.crossLanguage }.let { if (it < 0) all.size else it }
        val particle = all.getOrNull(plain)?.takeIf { c -> c.pieces.any { it.origin == Origin.RULE } }
            ?.let { c -> Japanese(c.text, c.pieces.joinToString("") { it.reading }, particle = true) }
        return listOfNotNull(particle) + all.withIndex().filter { it.value.crossLanguage }
            .map { (i, c) -> Japanese(c.text, c.pieces.joinToString("") { it.reading }, cross = true, leads = i < plain) }
    }

    /**
     * Adds up to [JAPANESE_CROSS] cross-language candidates to Mozc's list for Japanese mode.
     * Inputs: [own] Mozc's candidates ([japanese]); [cross] from [japaneseCross]; [reading] the
     * kana Mozc's romaji table made of the letters.
     * Returns: [own] with the new texts of [cross]: those that lead go right after Mozc's best
     * (romaji first, cross-language next, PLAN §2.3), or in front when the reading still holds
     * letters (`dongjing` is not Japanese as typed, so Mozc's reading of it is a guess); the
     * others, which the coordinator itself ranks below the romaji reading, go last. A sentence
     * read with a particle goes first: it is the romaji reading itself, read correctly.
     */
    fun japaneseWithCross(own: List<Japanese>, cross: List<Japanese>, reading: String): List<Japanese> {
        val texts = own.mapTo(HashSet()) { it.text }
        val added = cross.filter { texts.add(it.text) }.take(JAPANESE_CROSS)
        if (added.isEmpty()) return own
        val first = added.filter { it.particle }
        val (leading, trailing) = added.filter { !it.particle }.partition { it.leads }
        val at = if (reading.any { it in 'a'..'z' || it in 'A'..'Z' }) 0 else minOf(1, own.size)
        return first + own.take(at) + leading + own.drop(at) + trailing
    }

    /** The most predicted words placed right after the best sentence. */
    const val JAPANESE_PREDICTIONS = 3

    /**
     * Lists Japanese candidates for a conversion: the whole sentence, the first
     * [JAPANESE_PREDICTIONS] words predicted from the whole reading that are longer than it
     * (completions such as ありがとう for ありがと), the first segment's alternatives with the
     * rest of the sentence, the other predictions, then the reading in hiragana and in katakana.
     * Inputs: [segments] of the whole composition; [predicted] Mozc's predictions for its reading.
     * Returns: each candidate with its reading (a sentence's is the whole reading, a word's its
     * own); empty for no segments; no text twice.
     */
    fun japanese(segments: List<Mozc.Segment>, predicted: List<Mozc.Candidate> = emptyList()): List<Japanese> {
        if (segments.isEmpty()) return emptyList()
        val reading = segments.joinToString("") { it.reading }
        val firsts = segments.map { s -> s.candidates.firstOrNull()?.value ?: s.reading }
        val rest = firsts.drop(1).joinToString("")
        val restReading = segments.drop(1).joinToString("") { it.reading }
        val seen = HashSet<String>()
        val (completions, others) = predicted.partition { it.reading.length > reading.length }
        val early = completions.take(JAPANESE_PREDICTIONS)
        return buildList {
            add(Japanese(firsts.joinToString(""), reading))
            early.forEach { add(Japanese(it.value, it.reading)) }
            segments.first().candidates.forEach { add(Japanese(it.value + rest, it.reading + restReading)) }
            (completions.drop(early.size) + others).forEach { add(Japanese(it.value, it.reading)) }
            add(Japanese(reading, reading))
            add(Japanese(katakana(reading), reading))
        }.filter { seen.add(it.text) }
    }

    /**
     * Lists the candidates of one segment while it is being edited: its alternatives, then its
     * reading in hiragana and katakana.
     * Returns: the alternatives with the segment's reading; no text twice.
     */
    fun segment(segment: Mozc.Segment): List<Japanese> {
        val seen = HashSet<String>()
        return (segment.candidates.map { Japanese(it.value, it.reading) } +
            Japanese(segment.reading, segment.reading) + Japanese(katakana(segment.reading), segment.reading))
            .filter { seen.add(it.text) }
    }

    /**
     * Lists English suggestions: what was typed stays first (space commits it), then the engine's
     * words, capitalized when the typed word starts with a capital.
     * Inputs: [typed] may be empty, for next-word prediction.
     */
    fun english(typed: String, words: List<String>): List<String> {
        val capital = typed.firstOrNull()?.isUpperCase() == true
        val shown = words.map { if (capital) it.replaceFirstChar(Char::uppercase) else it }
        return if (typed.isEmpty()) shown.distinct() else (listOf(typed) + shown).distinct()
    }

    /**
     * Finds emoji for a composition (English words, pinyin, romaji; CLDR keywords).
     * Returns: up to [EMOJI_SUGGESTIONS] emoji, best first; none for fewer than two letters.
     */
    fun emoji(index: EmojiIndex?, typed: String): List<String> {
        val key = typed.lowercase().filter { it in 'a'..'z' }
        if (key.length < 2) return emptyList()
        return index?.lookup(key, EMOJI_SUGGESTIONS).orEmpty()
    }

    /**
     * Finds emoji for the emoji search's [query] (PLAN §8.6): English words, pinyin or romaji
     * matched against the CLDR keywords, spaces ignored (the keys are stored without them).
     * Returns: up to [EMOJI_SEARCH_RESULTS] emoji, best first; none for an empty query.
     */
    fun emojiSearch(index: EmojiIndex?, query: String): List<String> {
        val key = query.lowercase().filter { !it.isWhitespace() }
        if (key.isEmpty()) return emptyList()
        return index?.lookup(key, EMOJI_SEARCH_RESULTS).orEmpty()
    }

    /** The most results the emoji search shows (the bar scrolls; the expand arrow shows all). */
    const val EMOJI_SEARCH_RESULTS = 48

    /** Marks an entry of [chinese] that is not one of librime's candidates. */
    const val TYPED = -2
    const val EMOJI = -1

    /**
     * Lists Chinese candidates: librime's two best, then the letters as typed (English typed in
     * Chinese mode, PLAN §8.2), then the emoji, then the rest of librime's candidates.
     * Inputs: [texts] are librime's candidates, best first; [typed] is librime's raw input.
     * Returns: each entry's text with where it comes from: its index in [texts], [TYPED] or
     * [EMOJI]; no text twice. The typed letters are left out when they are empty or are already
     * one of librime's candidates.
     */
    fun chinese(texts: List<String>, typed: String, emoji: List<String>): List<Pair<String, Int>> {
        val head = texts.withIndex().take(EMOJI_POSITION).map { it.value to it.index }
        val tail = texts.withIndex().drop(EMOJI_POSITION).map { it.value to it.index }
        val letters = if (typed.isEmpty() || typed in texts) emptyList() else listOf(typed to TYPED)
        val seen = HashSet<String>()
        return (head + letters + emoji.map { it to EMOJI } + tail).filter { seen.add(it.first) }
    }

    /** Puts [emoji] into [list] after its first [position] entries, without duplicates. */
    fun withEmoji(list: List<String>, emoji: List<String>, position: Int = EMOJI_POSITION): List<String> {
        if (emoji.isEmpty()) return list
        val at = minOf(position, list.size)
        return (list.take(at) + emoji + list.drop(at)).distinct()
    }

    /** Marks an entry of [mixed] that is not one of the snapshot's candidates. */
    const val MIXED_TYPED = -2
    const val MIXED_EMOJI = -1

    /**
     * Lists Mixed-mode candidates from the coordinator's snapshot (PLAN §2.3): its candidates in
     * the engine's order, the emoji after the first [emojiPosition] of them, and last the letters
     * as typed when no candidate is exactly them, so anything typed can always be committed.
     * Inputs: [texts] are the snapshot's candidate texts, best first; [typed] is its composition.
     * Returns: each entry's text with where it comes from: its index in the snapshot,
     * [MIXED_EMOJI] or [MIXED_TYPED]; no text twice.
     */
    fun mixed(texts: List<String>, typed: String, emoji: List<String>, emojiPosition: Int): List<Pair<String, Int>> {
        val indexed = texts.withIndex().map { it.value to it.index }
        val at = minOf(emojiPosition, indexed.size)
        val letters = typed.trim().let { if (it.isEmpty() || it in texts) emptyList() else listOf(it to MIXED_TYPED) }
        val seen = HashSet<String>()
        return (indexed.take(at) + emoji.map { it to MIXED_EMOJI } + indexed.drop(at) + letters).filter { seen.add(it.first) }
    }

    /**
     * Returns the reading the candidate bar shows next to a Mixed-mode candidate (PLAN §9.3):
     * the hiragana of a Japanese candidate that was not typed as romaji throughout (池袋 typed
     * `chidai` shows いけぶくろ), and "" for every other candidate: one typed in its own language
     * needs no reading, and a reading equal to the text says nothing.
     */
    fun mixedReading(candidate: SnapshotCandidate): String {
        if (candidate.target != Lang.JAPANESE || !candidate.crossLanguage) return ""
        val reading = candidate.pieces.joinToString("") { it.reading }
        return if (reading == candidate.text) "" else reading
    }

    /**
     * Places emoji in Mixed mode by the emoji weight (`mixed.weight_emoji`, 0–100): after the two
     * best candidates at 100, one place later for every 20 points below, so at the default of 30
     * they follow the cross-language candidates as in PLAN §2.3's examples.
     */
    fun mixedEmojiPosition(weight: Int): Int = EMOJI_POSITION + (100 - weight.coerceIn(0, 100)) / 20

    /**
     * Builds the spans that choosing [surface] for segment [index] asks Mozc to convert: the
     * segments before it keep their boundaries (and their pinned surface, if any), the segment is
     * pinned, and the segments after it are converted again around it, except those already pinned.
     * Returns: spans in reading order; null for an index out of range.
     * Notes: PLAN §5.6: changing one segment never rewrites the segments already chosen.
     */
    fun pick(segments: List<Mozc.Segment>, index: Int, surface: String): List<Mozc.Span>? {
        if (index !in segments.indices) return null
        val spans = ArrayList<Mozc.Span>()
        segments.forEachIndexed { i, s ->
            spans += when {
                i == index -> Mozc.Span(s.reading, surface)
                s.pinned -> Mozc.Span(s.reading, s.candidates.firstOrNull()?.value ?: s.reading)
                i < index -> Mozc.Span(s.reading, fixed = true)
                else -> Mozc.Span(s.reading)
            }
        }
        return merged(spans)
    }

    /**
     * Builds the spans that shrinking or extending segment [index] by [delta] characters asks
     * Mozc to convert: the segments before it keep their boundaries, the segment keeps its new
     * length, and the rest of the reading is converted again.
     * Returns: spans in reading order; null when the segment cannot change that way (it would be
     * empty, or reach past the end of the reading, or [index] is out of range).
     */
    fun resize(segments: List<Mozc.Segment>, index: Int, delta: Int): List<Mozc.Span>? {
        if (index !in segments.indices || delta == 0) return null
        val reading = segments.joinToString("") { it.reading }
        val start = segments.take(index).sumOf { it.reading.length }
        val length = segments[index].reading.length + delta
        if (length <= 0 || start + length > reading.length) return null
        val spans = ArrayList<Mozc.Span>()
        segments.take(index).forEach { s ->
            spans += if (s.pinned) Mozc.Span(s.reading, s.candidates.firstOrNull()?.value ?: s.reading) else Mozc.Span(s.reading, fixed = true)
        }
        spans += Mozc.Span(reading.substring(start, start + length), fixed = true)
        if (start + length < reading.length) spans += Mozc.Span(reading.substring(start + length))
        return spans
    }

    /**
     * Builds the spans that keep the segments the person chose while the reading grows: chosen
     * segments stay pinned, the rest and the new reading are converted again.
     * Returns: null when nothing was chosen or [reading] does not start with the old reading (a
     * deletion undoes the choices).
     */
    fun extend(segments: List<Mozc.Segment>, reading: String): List<Mozc.Span>? {
        if (segments.none { it.pinned }) return null
        val old = segments.joinToString("") { it.reading }
        if (!reading.startsWith(old)) return null
        val spans = segments.map { s ->
            if (s.pinned) Mozc.Span(s.reading, s.candidates.firstOrNull()?.value ?: s.reading) else Mozc.Span(s.reading)
        }
        return merged(if (reading.length > old.length) spans + Mozc.Span(reading.substring(old.length)) else spans)
    }

    /** Joins neighbouring free spans, so Mozc segments them as one stretch of reading. */
    private fun merged(spans: List<Mozc.Span>): List<Mozc.Span> = buildList {
        for (span in spans) {
            val last = lastOrNull()
            if (last != null && span.surface == null && !span.fixed && last.surface == null && !last.fixed) {
                set(size - 1, Mozc.Span(last.reading + span.reading))
            } else {
                add(span)
            }
        }
    }

    /** Converts hiragana to katakana; other characters stay. */
    fun katakana(text: String) = buildString {
        for (c in text) append(if (c in '\u3041'..'\u3096') c + 0x60 else c)
    }
}
