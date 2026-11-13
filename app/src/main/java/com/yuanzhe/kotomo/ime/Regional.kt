package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.settings.ChineseScript
import java.io.File

/**
 * Lets Taiwan and Hong Kong words take part in ranking (PLAN §10.3), not only in the output
 * conversion: in those scripts a candidate that is one of the region's words (the words OpenCC's
 * regional phrase tables write, `TWPhrases.txt` and `HKPhrases.txt`, the same files the output
 * conversion uses) moves to the front when it is among the first [WITHIN] candidates.
 *
 * Notes: pure Kotlin besides [words], which reads a file (engine thread); unit-tested
 * (RegionalTest) and run by the conversion corpus (T11). Words are matched as librime shows them,
 * after its OpenCC filter.
 */
object Regional {
    /** How far down the list a regional word may be and still move to the front. */
    const val WITHIN = 8

    /** Names the phrase table of [script], or null for a script without regional words. */
    fun table(script: ChineseScript): String? = when (script) {
        ChineseScript.TAIWAN -> "TWPhrases.txt"
        ChineseScript.HONG_KONG -> "HKPhrases.txt"
        else -> null
    }

    /**
     * Reads the regional words of an OpenCC phrase table: every value after the tab (`鼠標	滑鼠`
     * gives 滑鼠), several separated by spaces; comments and malformed lines are skipped.
     * Returns: the words; empty when the file is missing or unreadable.
     */
    fun words(file: File): Set<String> = runCatching { parse(file.readText()) }.getOrDefault(emptySet())

    /** Parses an OpenCC phrase table's text into its values (see [words]). */
    fun parse(text: String): Set<String> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line -> line.substringAfter('\t', "").takeIf { it.isNotBlank() } }
        .flatMap { it.trim().split(' ') }
        .filter { it.length >= 2 }
        .toSet()

    /**
     * Moves the regional words among the first [WITHIN] of [entries] to the front, keeping their
     * order and the order of everything else; entries that are not the engine's (a negative
     * index: the letters as typed, an emoji) never move.
     */
    fun <T> promote(entries: List<Pair<String, T>>, words: Set<String>, isEngine: (T) -> Boolean): List<Pair<String, T>> {
        if (words.isEmpty()) return entries
        val head = entries.take(WITHIN)
        val regional = head.filter { (text, at) -> isEngine(at) && text in words }
        if (regional.isEmpty()) return entries
        return regional + entries.filterNot { e -> regional.any { it === e } }
    }
}
