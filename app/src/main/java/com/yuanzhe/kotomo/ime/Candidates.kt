package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.engine.Mozc

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

    /** One Japanese candidate: what it commits and the hiragana reading it stands for. */
    data class Japanese(val text: String, val reading: String)

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

    /** Puts [emoji] into [list] at [EMOJI_POSITION], without duplicates. */
    fun withEmoji(list: List<String>, emoji: List<String>): List<String> {
        if (emoji.isEmpty()) return list
        val at = minOf(EMOJI_POSITION, list.size)
        return (list.take(at) + emoji + list.drop(at)).distinct()
    }

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
