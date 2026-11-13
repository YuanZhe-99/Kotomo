package com.yuanzhe.kotomo.ime

import java.io.File

/**
 * Completes Chinese idioms (PLAN §10.8): after the first two or three characters of a four-character
 * idiom are committed, offers the rest. The idioms come from the Idioms dictionary the person
 * downloaded (THUOCL's 成语 list, `zh-thuocl-idioms`), so nothing is offered without it.
 *
 * Notes: [from] reads the pack's table once, off the main thread; [complete] is a map lookup, cheap
 * enough for the main thread after a commit. Pure Kotlin; unit-tested (IdiomsTest).
 */
class Idioms private constructor(private val byPrefix: Map<String, List<String>>) {
    /**
     * Returns the completions of the idioms that [committed] starts (its last three, then last two
     * characters), best first, at most [MAX]; empty when none.
     */
    fun complete(committed: String): List<String> {
        if (committed.length < 2) return emptyList()
        val three = if (committed.length >= 3) byPrefix[committed.takeLast(3)].orEmpty() else emptyList()
        return (three + byPrefix[committed.takeLast(2)].orEmpty()).distinct().take(MAX)
    }

    companion object {
        /** The most completions offered at once. */
        const val MAX = 5
        /** No idioms (the pack is missing or off). */
        val NONE = Idioms(emptyMap())
        /** The id of the downloadable pack the idioms come from. */
        const val PACK = "zh-thuocl-idioms"

        /**
         * Builds the completions from table lines `text\tcode\tweight` (the pack's librime table):
         * four-character idioms of CJK ideographs only, the heavier first for each prefix.
         * Notes: a line that is not three tab-separated fields with a number last is skipped.
         */
        fun of(lines: Sequence<String>): Idioms {
            val weighted = HashMap<String, MutableList<Pair<String, Long>>>()
            for (line in lines) {
                val parts = line.split('\t')
                if (parts.size != 3) continue
                val text = parts[0]
                val weight = parts[2].trim().toLongOrNull() ?: continue
                if (text.length != 4 || !text.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) continue
                weighted.getOrPut(text.take(2)) { mutableListOf() } += text.drop(2) to weight
                weighted.getOrPut(text.take(3)) { mutableListOf() } += text.drop(3) to weight
            }
            return Idioms(weighted.mapValues { (_, l) -> l.sortedByDescending { it.second }.map { it.first }.distinct() })
        }

        /** Reads [table] (the pack's table file), or [NONE] when it is missing or unreadable. */
        fun from(table: File?): Idioms = table?.takeIf { it.isFile }?.let { f -> runCatching { f.useLines { of(it) } }.getOrNull() } ?: NONE
    }
}
