package com.yuanzhe.kotomo.dictionary

import java.text.Normalizer

/** Names an entry's language: Chinese, Japanese or English (the codes of the import formats). */
enum class Lang(val code: String) {
    ZH("zh"), JA("ja"), EN("en");

    companion object {
        /** Returns the language with [code] (case-insensitive), or null. */
        fun of(code: String?): Lang? = entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }
    }
}

/**
 * Names where an entry came from (PLAN §10.5). [MANUAL] entries rank highest; [IMPORT] and
 * [GENERATED] (made with an LLM) are untrusted and always rank low; [DOWNLOAD] comes from a
 * hash-checked dictionary pack.
 *
 * Notes: [key] is stored in `user.json`, so renaming it is a format change.
 */
enum class Source(val key: String) {
    MANUAL("manual"), IMPORT("import"), GENERATED("generated"), DOWNLOAD("download");

    companion object {
        /** Returns the source stored as [key], or null. */
        fun of(key: String?): Source? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Holds one dictionary entry: a word in [lang], its [reading] (toneless pinyin syllables separated
 * by spaces, or hiragana; null for English), a [weight] from 0 to 100, its [source], and [extra]
 * fields this version does not use, kept when the entry is written back (romaji, tags, emoji, …).
 */
data class Entry(
    val lang: Lang,
    val text: String,
    val reading: String?,
    val weight: Int,
    val source: Source,
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** Identifies the entry for duplicates and conflicts: the same word with the same reading. */
    val key: Triple<Lang, String, String?> get() = Triple(lang, text, reading)

    /**
     * Whether the keyboard uses the entry today: Chinese only. Japanese and English entries are
     * stored and shown as not used yet until Mozc's user dictionary and the English engine read
     * them (PLAN §10.5).
     */
    val used: Boolean get() = lang == Lang.ZH

    /** The pinyin as librime's tables want it: the syllables without spaces (Chinese only). */
    val rimeCode: String? get() = if (lang == Lang.ZH) reading?.replace(" ", "") else null
}

/**
 * Checks and normalizes words and readings for every way entries arrive (typed in the settings,
 * imported, downloaded), as pure functions (unit-tested).
 *
 * Notes: pinyin is lowercase and toneless (tone marks and tone digits removed, ü and u: written
 * v), separated by spaces or apostrophes, and must split into syllables that luna_pinyin knows;
 * Japanese readings are kana only (katakana becomes hiragana, the long-vowel mark ー stays);
 * English has no reading.
 */
object Readings {
    /** The longest word accepted, in code points. */
    const val MAX_TEXT = 64

    /**
     * Checks a word.
     * Returns: the word trimmed, or the problem: empty, longer than [MAX_TEXT], or holding control
     * characters, tabs, line breaks or U+FFFD (what a broken decoding leaves).
     */
    fun text(raw: String): Pair<String?, Problem?> {
        val text = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
        return when {
            text.isEmpty() -> null to Problem.EMPTY_TEXT
            text.codePointCount(0, text.length) > MAX_TEXT -> null to Problem.TEXT_TOO_LONG
            text.any { Character.isISOControl(it) || it == '\uFFFD' || it == '\u2028' || it == '\u2029' } -> null to Problem.TEXT_CHARACTERS
            else -> text to null
        }
    }

    /**
     * Normalizes the reading of a word [text] in [lang].
     * Returns: the canonical reading (null for English), or the problem: a Chinese or Japanese
     * word without one, a reading that is not pinyin or kana, or a Chinese word written only in
     * Han characters whose pinyin has a different number of syllables.
     */
    fun reading(lang: Lang, text: String, raw: String?): Pair<String?, Problem?> {
        val given = raw?.trim().orEmpty()
        return when (lang) {
            Lang.EN -> null to null
            Lang.ZH -> {
                if (given.isEmpty()) return null to Problem.NO_READING
                val syllables = pinyin(given) ?: return null to Problem.READING
                val hans = text.codePoints().toArray()
                if (hans.all { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN } && hans.size != syllables.size) {
                    return null to Problem.READING_COUNT
                }
                syllables.joinToString(" ") to null
            }
            Lang.JA -> {
                if (given.isEmpty()) return null to Problem.NO_READING
                (kana(given) ?: return null to Problem.READING) to null
            }
        }
    }

    /**
     * Splits pinyin into syllables.
     * Returns: the toneless syllables, or null when some part does not split into known
     * syllables. Spaces and apostrophes are boundaries; within a part the split with the fewest
     * syllables wins (`xian` is one syllable, `xi'an` two).
     */
    fun pinyin(raw: String): List<String>? {
        val plain = buildString {
            for (c in Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)) {
                when {
                    c in "\u0300\u0301\u030C\u0304" -> {} // tone marks
                    c == '\u0308' -> if (isNotEmpty() && last() == 'u') setCharAt(length - 1, 'v') // ü
                    c in '1'..'5' -> {} // tone digits
                    else -> append(c)
                }
            }
        }.replace("u:", "v")
        val parts = plain.split(' ', '\'', '\u2019').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.flatMap { split(it) ?: return null }
    }

    /** Splits one run of letters into the fewest known syllables, or null. */
    private fun split(part: String): List<String>? {
        // best[i]: the fewest syllables covering part[0, i), with the syllable that ends there.
        val best = arrayOfNulls<Pair<Int, Int>>(part.length + 1)
        best[0] = 0 to 0
        for (end in 1..part.length) {
            for (start in maxOf(0, end - LONGEST)..<end) {
                val before = best[start] ?: continue
                if (part.substring(start, end) !in SYLLABLES) continue
                val count = before.first + 1
                if (best[end] == null || count < best[end]!!.first) best[end] = count to start
            }
        }
        if (best[part.length] == null) return null
        val out = ArrayDeque<String>()
        var end = part.length
        while (end > 0) {
            val start = best[end]!!.second
            out.addFirst(part.substring(start, end))
            end = start
        }
        return out.toList()
    }

    /**
     * Normalizes a Japanese reading to hiragana.
     * Returns: the reading with katakana turned into hiragana (ー kept), or null when it holds
     * anything that is not kana.
     */
    fun kana(raw: String): String? {
        val out = StringBuilder()
        for (c in Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)) {
            when (c) {
                in '\u3041'..'\u3096', '\u309D', '\u309E', '\u30FC' -> out.append(c)
                in '\u30A1'..'\u30F6', '\u30FD', '\u30FE' -> out.append(c - 0x60)
                else -> return null
            }
        }
        return out.toString().ifEmpty { null }
    }

    /** The toneless syllables luna_pinyin's dictionary uses (rime-luna-pinyin, 424 syllables). */
    private val SYLLABLES: Set<String> = listOf(
        "a ai an ang ao",
        "ba bai ban bang bao bei ben beng bi bian biang biao bie bin bing bo bu",
        "ca cai can cang cao ce cei cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo",
        "da dai dan dang dao de dei den deng di dia dian diao die din ding diu dong dou du duan dui dun duo",
        "e eh ei en eng er",
        "fa fan fang fei fen feng fiao fo fong fou fu",
        "ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo",
        "ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo",
        "ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun",
        "ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo",
        "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lvan lve",
        "ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu",
        "na nai nan nang nao ne nei nen neng ni nia nian niang niao nie nin ning niu nong nou nu nuan nun nuo nv nve",
        "o ou",
        "pa pai pan pang pao pei pen peng pi pia pian piao pie pin ping po pou pu",
        "qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun",
        "ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo",
        "sa sai san sang sao se sei sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo",
        "ta tai tan tang tao te tei teng ti tian tiao tie ting tong tou tu tuan tui tun tuo",
        "wa wai wan wang wei wen weng wo wong wu",
        "xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun",
        "ya yai yan yang yao ye yi yin ying yo yong you yu yuan yue yun",
        "za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo",
    ).flatMap { it.split(' ') }.toHashSet()

    /** The longest syllable's length (zhuang). */
    private const val LONGEST = 6
}

/**
 * Turns the weights of one file into 0–100 (PLAN §10.5), as a pure function (unit-tested).
 *
 * Notes: a missing weight is 50. When the file's largest weight exceeds 100 every weight is first
 * scaled by its logarithm against that maximum and only then clamped, so a file of word
 * frequencies keeps its order. Entries made with an LLM never exceed [GENERATED_MAX].
 */
object Weights {
    const val DEFAULT = 50
    const val MAX = 100
    const val GENERATED_MAX = 20

    /**
     * Scales one file's weights.
     * Inputs: raw holds each entry's weight as written (null when missing; never negative, the
     * parser refuses those); generated caps every result at [GENERATED_MAX].
     * Returns: the weights in the same order.
     */
    fun scale(raw: List<Double?>, generated: Boolean): List<Int> {
        val max = raw.filterNotNull().maxOrNull() ?: 0.0
        return raw.map { w ->
            val scaled = when {
                w == null -> DEFAULT.toDouble()
                max > MAX -> MAX * kotlin.math.ln(1 + w) / kotlin.math.ln(1 + max)
                else -> w
            }
            val clamped = kotlin.math.round(scaled).toInt().coerceIn(0, MAX)
            if (generated) minOf(clamped, GENERATED_MAX) else clamped
        }
    }
}
