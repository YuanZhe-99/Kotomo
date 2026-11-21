package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.settings.ChineseScript
import java.io.File

/**
 * Converts pure-Han words between Chinese (Simplified / Traditional / Taiwan / Hong Kong) and
 * Japanese Shinjitai using the bundled OpenCC tables, and detects Japanese kanji strings that share
 * the exact same form as the active Chinese script (badge `中日`).
 *
 * Notes: pure Kotlin except [load], which reads files from `opencc/` off the main thread;
 * unit-tested (`KanjiScriptTest`). Never called on the key path before candidates exist.
 */
class KanjiScript private constructor(
    private val s2t: Table,
    private val t2jp: Table,
    private val jp2t: Table,
    private val t2s: Table,
    private val t2tw: Table,
    private val t2hk: Table,
) {
    /** Longest-match string replacement table over an OpenCC text dictionary. */
    private class Table(private val maxKeyLen: Int, private val map: Map<String, String>) {
        fun convert(text: String): String {
            if (text.isEmpty() || map.isEmpty()) return text
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                var matched: String? = null
                var nextIndex = i + Character.charCount(text.codePointAt(i))
                val limit = minOf(maxKeyLen, text.length - i)
                var end = i + limit
                while (end > i) {
                    // Never slice in the middle of a UTF-16 surrogate pair.
                    if (end < text.length && Character.isLowSurrogate(text[end])) {
                        end--
                        continue
                    }
                    val sub = text.substring(i, end)
                    val replacement = map[sub]
                    if (replacement != null) {
                        matched = replacement
                        nextIndex = end
                        break
                    }
                    end--
                }
                if (matched != null) {
                    out.append(matched)
                } else {
                    out.append(text, i, nextIndex)
                }
                i = nextIndex
            }
            return out.toString()
        }
    }

    /**
     * Converts a pure-Han Chinese word [text] into Japanese Shinjitai, or returns null when [text]
     * is not pure Han or has the exact same form in Japanese.
     */
    fun toJapanese(text: String): String? {
        if (!isPureHan(text)) return null
        val out = t2jp.convert(s2t.convert(text))
        return out.takeIf { it != text }
    }

    /**
     * Converts a pure-Han Japanese word [text] into the active Chinese [script], or returns null
     * when [text] is not pure Han or has the exact same form in that Chinese script.
     */
    fun toChinese(text: String, script: ChineseScript): String? {
        if (!isPureHan(text)) return null
        val trad = jp2t.convert(text)
        val out = when (script) {
            ChineseScript.SIMPLIFIED -> t2s.convert(trad)
            ChineseScript.TRADITIONAL -> trad
            ChineseScript.TAIWAN -> t2tw.convert(trad)
            ChineseScript.HONG_KONG -> t2hk.convert(trad)
        }
        return out.takeIf { it != text }
    }

    /**
     * Whether a Japanese candidate [text] is pure Han, identical in the active Chinese [script],
     * and free of Japanese-only kokuji, so it shares the `中日` badge.
     */
    fun isSharedJapaneseKanji(text: String, script: ChineseScript): Boolean =
        isPureHan(text) &&
            toChinese(text, script) == null &&
            toJapanese(text) == null &&
            text.codePoints().noneMatch { it in KOKUJI }

    companion object {
        /** Japanese-only kokuji excluded from the `中日` shared badge even when OpenCC leaves them untouched. */
        private val KOKUJI: Set<Int> = "込働畑辻峠枠躾匂凪榊栃雫畠噺樫槙栂柾桝鋲辿塀麿凧凩笹鰯鯱"
            .codePoints().toArray().toHashSet()

        /** Japanese-only glyphs whose OpenCC entry starts with a self-mapping, supplemented for `jp2t`. */
        private val JP_ONLY_TO_TRAD: Map<String, String> = mapOf(
            "氷" to "冰",
            "戸" to "戶",
            "隣" to "鄰",
            "産" to "產",
            "呉" to "吳",
            "汚" to "污",
            "姫" to "姬",
            "姉" to "姊",
            "舗" to "鋪",
            "輌" to "輛",
            "聡" to "聰",
            "竃" to "竈",
            "焔" to "焰",
            "溌" to "潑",
            "涛" to "濤",
        )

        /** Returns true when [text] is non-empty and every code point is a Han ideograph. */
        fun isPureHan(text: String): Boolean =
            text.isNotEmpty() && text.codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }

        /**
         * Loads the OpenCC tables from [openccDir]; returns null when any required table is missing
         * or unreadable.
         */
        fun load(openccDir: File): KanjiScript? = runCatching {
            val stPhrases = File(openccDir, "STPhrases.txt")
            val stChars = File(openccDir, "STCharacters.txt")
            val tsPhrases = File(openccDir, "TSPhrases.txt")
            val tsChars = File(openccDir, "TSCharacters.txt")
            val jpPhrases = File(openccDir, "JPShinjitaiPhrases.txt")
            val jpChars = File(openccDir, "JPShinjitaiCharacters.txt")
            val twPhrases = File(openccDir, "TWVariantsPhrases.txt")
            val twChars = File(openccDir, "TWVariants.txt")
            val hkPhrases = File(openccDir, "HKVariantsPhrases.txt")
            val hkChars = File(openccDir, "HKVariants.txt")
            if (!stChars.isFile || !jpChars.isFile || !tsChars.isFile) return null
            KanjiScript(
                s2t = loadForward(stPhrases, stChars),
                t2jp = loadT2Jp(jpChars),
                jp2t = loadJp2T(jpPhrases, jpChars),
                t2s = loadForward(tsPhrases, tsChars),
                t2tw = loadForward(twPhrases, twChars),
                t2hk = loadForward(hkPhrases, hkChars),
            )
        }.getOrNull()

        /** Builds a forward table taking the first target value of each line across [files]. */
        private fun loadForward(vararg files: File): Table {
            val map = HashMap<String, String>()
            var maxLen = 1
            for (file in files) {
                if (!file.isFile) continue
                file.forEachLine(Charsets.UTF_8) { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEachLine
                    val key = line.substring(0, tab)
                    val value = line.substring(tab + 1).trim().substringBefore(' ')
                    if (value.isEmpty() || value == key) return@forEachLine
                    if (map.putIfAbsent(key, value) == null && key.length > maxLen) {
                        maxLen = key.length
                    }
                }
            }
            return Table(maxLen, map)
        }

        /**
         * Builds `jp2t` from [jpPhrases] and [jpChars] plus [JP_ONLY_TO_TRAD] for Japanese-only
         * glyphs whose OpenCC entry lists itself first.
         */
        private fun loadJp2T(jpPhrases: File, jpChars: File): Table {
            val combined = HashMap<String, String>()
            var maxLen = 1
            for (file in listOf(jpPhrases, jpChars)) {
                if (!file.isFile) continue
                file.forEachLine(Charsets.UTF_8) { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEachLine
                    val key = line.substring(0, tab)
                    val value = line.substring(tab + 1).trim().substringBefore(' ')
                    if (value.isNotEmpty() && value != key && combined.putIfAbsent(key, value) == null && key.length > maxLen) {
                        maxLen = key.length
                    }
                }
            }
            for ((jp, trad) in JP_ONLY_TO_TRAD) {
                if (combined.putIfAbsent(jp, trad) == null && jp.length > maxLen) {
                    maxLen = jp.length
                }
            }
            return Table(maxLen, combined)
        }

        /**
         * Builds `t2jp` in two passes from `JPShinjitaiCharacters.txt`: primary mappings (`vals[0]
         * != key`) take priority over compatibility self-first mappings (`vals[0] == key`), and
         * standard Joyo `舗` wins over `舖` for `鋪`.
         */
        private fun loadT2Jp(jpChars: File): Table {
            val compat = ArrayList<Pair<String, List<String>>>()
            val map = HashMap<String, String>()
            var maxLen = 1
            jpChars.forEachLine(Charsets.UTF_8) { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val key = line.substring(0, tab)
                val vals = line.substring(tab + 1).trim().split(' ').filter { it.isNotEmpty() }
                if (vals.isEmpty()) return@forEachLine
                if (vals[0] != key) {
                    for (v in vals) {
                        if (map.putIfAbsent(v, key) == null && v.length > maxLen) maxLen = v.length
                    }
                } else if (key != "舖") {
                    compat += key to vals.drop(1)
                }
            }
            for ((key, vals) in compat) {
                for (v in vals) {
                    if (map.putIfAbsent(v, key) == null && v.length > maxLen) maxLen = v.length
                }
            }
            return Table(maxLen, map)
        }
    }
}
