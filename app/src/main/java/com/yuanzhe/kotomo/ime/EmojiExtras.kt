package com.yuanzhe.kotomo.ime

/**
 * Holds the emoji panel's own lists besides the emoji data (PLAN §10.6): favorites, combinations,
 * kaomoji, and the person's word → emoji mappings for the candidate bar.
 *
 * Notes: pure Kotlin, unit-tested (EmojiExtrasTest). The stored forms are the settings
 * `emoji.favorites`, `emoji.combos` and `emoji.mappings` (doc/en-us/settings-format.md); what is
 * read from them is checked, since the file may be edited or restored from elsewhere. The kaomoji
 * and the built-in combinations were written for Kotomo.
 */
object EmojiExtras {
    /** The most favorites kept; adding one more drops the oldest. */
    const val MAX_FAVORITES = 64
    /** The longest combination, mapping value or kaomoji kept, in UTF-16 units. */
    private const val MAX_TEXT = 32

    /** Kaomoji by mood, in the panel's order (written for Kotomo). */
    val KAOMOJI: List<String> = listOf(
        "(^_^)", "(^o^)/", "(*^▽^*)", "(≧▽≦)", "(｡•̀ᴗ-)✧", "ヽ(・∀・)ﾉ", "(๑˃ᴗ˂)ﻭ", "o(^▽^)o",
        "(♡˙︶˙♡)", "(〃▽〃)", "(´▽`ʃ♡ƪ)", "(っ´▽`)っ", "(*´∀`)~♥",
        "(T_T)", "(；ω；)", "(´；ω；`)", "(ノД`)", "(｡•́︿•̀｡)",
        "(°o°)", "(⊙_⊙)", "Σ(°△°|||)", "(ﾟДﾟ)",
        "(￣^￣)", "(╬ Ò﹏Ó)", "(눈_눈)", "(¬_¬)",
        "(・ω・)ノ", "(｀・ω・´)ゞ", "m(_ _)m", "(*_ _)人",
        "¯\\_(ツ)_/¯", "(￣▽￣)ノ", "(´-ω-`)", "( ˘ω˘ )zzZ", "(=^･ω･^=)", "ʕ•ᴥ•ʔ",
    )

    /** Combinations offered before the person's own (written for Kotomo). */
    val COMBOS: List<String> = listOf("🎉🎂🥳", "👍👍", "❤️🔥", "🙏✨", "😂👌", "☕📖", "🌸🍡", "🏃‍♂️💨", "🎁🎄", "💪😤")

    /** Reads favorites: one emoji per line, in the order added; empty, too long and repeated lines are dropped. */
    fun parseFavorites(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() && it.length <= MAX_TEXT }.distinct().takeLast(MAX_FAVORITES)

    /** Adds [emoji] to the end of [favorites], or removes it when it is there; returns the stored form. */
    fun toggleFavorite(favorites: List<String>, emoji: String): String =
        (if (emoji in favorites) favorites - emoji else (favorites + emoji).takeLast(MAX_FAVORITES)).joinToString("\n")

    /** Reads combinations: one per line, trimmed; empty, too long and repeated lines are dropped. */
    fun parseCombos(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() && it.length <= MAX_TEXT }.distinct()

    /**
     * Reads the person's mappings: `word=emoji` per line, the word of lowercase letters (what is
     * typed: English, pinyin or romaji, at most 24), the emoji any short text; one word may have
     * several lines. Returns: each word's emoji in the order written; other lines are dropped.
     */
    fun parseMappings(text: String): Map<String, List<String>> = text.split('\n').mapNotNull { line ->
        val at = line.indexOf('=')
        if (at <= 0) return@mapNotNull null
        val word = line.substring(0, at).trim().lowercase()
        val emoji = line.substring(at + 1).trim()
        if (!WORD.matches(word) || emoji.isEmpty() || emoji.length > MAX_TEXT) null else word to emoji
    }.groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }

    /** Writes mappings as [parseMappings] reads them, sorted by word. */
    fun formatMappings(map: Map<String, List<String>>): String =
        map.toSortedMap().flatMap { (w, list) -> list.map { "$w=$it" } }.joinToString("\n")

    private val WORD = Regex("[a-z]{1,24}")
}
