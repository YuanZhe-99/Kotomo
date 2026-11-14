package com.yuanzhe.kotomo.ime

import android.content.Context
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Holds every emoji of the `emoji.tsv` asset (built by lexicon/EmojiData.java): the groups in
 * keyboard order, the skin-tone variants of each base emoji, and a sorted key table that finds
 * emoji by an English word, a Chinese keyword or its pinyin, or a Japanese keyword, its kana or
 * its romaji (`smile`, `xiao`, `笑`, `warau`, `わらう` → 😄 …).
 *
 * Notes: read-only after [parse]; safe to share between threads. Keys are kept sorted so a lookup
 * is a binary search plus a bounded scan; each key's emoji list is parsed only when it is read.
 */
class EmojiIndex private constructor(
    /** The groups in keyboard order, each with its renderable base emoji. */
    val groups: List<Group>,
    private val tones: Map<String, List<String>>,
    private val emoji: Array<String?>,
    private val keys: Array<String>,
    private val hits: Array<String>,
) {
    /** One emoji group: its id (`smileys`, `people`, … `flags`) and its base emoji in order. */
    data class Group(val id: String, val emoji: List<String>)

    /** Returns the skin-tone variants of a base emoji, or an empty list when it has none. */
    fun variants(base: String): List<String> = tones[base].orEmpty()

    /**
     * Finds emoji for a typed key in any of the three languages.
     *
     * Inputs: [typed], matched case-insensitively; [limit], the most emoji returned.
     * Returns: emoji whose key equals [typed] first (best keyword first, then keyboard order),
     * then emoji of keys that start with it, without duplicates.
     * Notes: scans at most [MAX_PREFIX_KEYS] longer keys, so a one-letter prefix stays cheap;
     * callers decide how short a typed key may be before suggesting anything.
     */
    fun lookup(typed: String, limit: Int = 8): List<String> {
        val q = typed.trim().lowercase()
        if (q.isEmpty() || limit <= 0) return emptyList()
        val out = LinkedHashSet<String>()
        var i = keys.binarySearch(q).let { if (it < 0) -it - 1 else it }
        var scanned = 0
        while (i < keys.size && out.size < limit && scanned <= MAX_PREFIX_KEYS && keys[i].startsWith(q)) {
            for (id in hits[i].split(',')) {
                val e = id.toIntOrNull()?.let(emoji::getOrNull) ?: continue
                out += e
                if (out.size == limit) break
            }
            i++
            scanned++
        }
        return out.toList()
    }

    /** Whether [key] (lowercase) is itself a key of the index, not only the start of one. */
    fun has(key: String): Boolean = keys.binarySearch(key) >= 0

    companion object {
        /** The asset name, at the root of the APK's assets. */
        const val ASSET = "emoji.tsv"
        private const val HEADER = "#kotomo-emoji\t1\t"
        private const val MAX_PREFIX_KEYS = 64

        @Volatile private var cached: EmojiIndex? = null

        /**
         * Parses the asset's lines.
         *
         * Inputs: [lines] of the asset; [renderable] keeps an emoji or variant only when it
         * returns true (the panel passes the system font's glyph check).
         * Returns: the index; emoji dropped by [renderable] are absent from groups, variants and
         * lookups.
         * Notes: the asset is read from the APK, so a wrong format version fails here and
         * malformed lines are skipped.
         */
        fun parse(lines: Sequence<String>, renderable: (String) -> Boolean = { true }): EmojiIndex {
            val groups = mutableListOf<Group>()
            val tones = HashMap<String, List<String>>()
            val emoji = ArrayList<String?>()
            val keys = ArrayList<String>()
            val hits = ArrayList<String>()
            var current: MutableList<String>? = null
            var first = true
            for (line in lines) {
                if (first) {
                    check(line.startsWith(HEADER)) { "emoji asset: unknown format" }
                    first = false
                    continue
                }
                val f = line.split('\t')
                when (f[0]) {
                    "g" -> if (f.size >= 2) {
                        current = mutableListOf<String>().also { groups += Group(f[1], it) }
                    }
                    "e" -> if (f.size >= 2) {
                        val ok = renderable(f[1])
                        emoji += f[1].takeIf { ok }
                        if (!ok) continue
                        current?.add(f[1])
                        if (f.size >= 3) {
                            val v = f[2].split(' ').filter { it.isNotEmpty() && renderable(it) }
                            if (v.isNotEmpty()) tones[f[1]] = v
                        }
                    }
                    "k" -> if (f.size >= 3) {
                        keys += f[1]
                        hits += f[2]
                    }
                }
            }
            check(!first) { "emoji asset: empty" }
            return EmojiIndex(groups.filter { it.emoji.isNotEmpty() }, tones, emoji.toTypedArray(), keys.toTypedArray(), hits.toTypedArray())
        }

        /**
         * Loads the index from the APK's assets once per process.
         *
         * Returns: the shared index.
         * Notes: runs on an I/O thread; keeps only what the system emoji font can draw (Apple's
         * images never ship, PLAN.md §4.1). Two first callers may both parse; either result is
         * equal.
         */
        suspend fun load(context: Context): EmojiIndex = cached ?: withContext(Dispatchers.IO) {
            val paint = Paint()
            context.assets.open(ASSET).bufferedReader().useLines { lines -> parse(lines) { paint.hasGlyph(it) } }
        }.also { cached = it }
    }
}
