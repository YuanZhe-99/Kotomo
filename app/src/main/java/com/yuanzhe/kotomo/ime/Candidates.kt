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

    /**
     * Lists a Japanese conversion: the whole sentence, the first segment's alternatives with the
     * rest of the sentence, then the reading in hiragana and in katakana.
     * Returns: an empty list for no segments; no duplicates.
     */
    fun japanese(segments: List<Mozc.Segment>): List<String> {
        if (segments.isEmpty()) return emptyList()
        val reading = segments.joinToString("") { it.reading }
        val firsts = segments.map { s -> s.candidates.firstOrNull()?.value ?: s.reading }
        val rest = firsts.drop(1).joinToString("")
        return buildList {
            add(firsts.joinToString(""))
            segments.first().candidates.forEach { add(it.value + rest) }
            add(reading)
            add(katakana(reading))
        }.distinct()
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

    /** Puts [emoji] into [list] at [EMOJI_POSITION], without duplicates. */
    fun withEmoji(list: List<String>, emoji: List<String>): List<String> {
        if (emoji.isEmpty()) return list
        val at = minOf(EMOJI_POSITION, list.size)
        return (list.take(at) + emoji + list.drop(at)).distinct()
    }

    /** Converts hiragana to katakana; other characters stay. */
    fun katakana(text: String) = buildString {
        for (c in text) append(if (c in '\u3041'..'\u3096') c + 0x60 else c)
    }
}
