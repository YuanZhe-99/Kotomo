package com.yuanzhe.kotomo.dictionary

import java.io.File

/** Why a line of a source did not become a word (counted per pack, PLAN §10.5). */
enum class Drop { FORMAT, FILTERED, TEXT, NO_READING, READING, READING_COUNT, AMBIGUOUS, DUPLICATE }

/**
 * Collects the words of one source while it is read, checked as the app checks every entry
 * ([Readings]: word, reading, syllable count), one per word and reading.
 *
 * Inputs: lang is the pack's language.
 * Notes: kept compact ("text<TAB>reading" → number) because a source can hold hundreds of
 * thousands of lines; [entries] builds the entry objects once at the end. A repeated word keeps
 * its largest number. Runs on a background thread, never on the key path.
 */
class Conversion(val lang: Lang) {
    private val best = LinkedHashMap<String, Double?>()
    /** Lines dropped, by reason. */
    val drops = java.util.EnumMap<Drop, Int>(Drop::class.java)
    /** Lines read, including the dropped ones. */
    var read = 0
        private set

    /** The words kept so far. */
    val size: Int get() = best.size

    /** Counts a line dropped before it reached the checks. */
    fun drop(reason: Drop) {
        read++
        drops.merge(reason, 1, Int::plus)
    }

    /**
     * Checks one line and keeps it.
     * Inputs: number is the source's frequency (larger is more common), or null for none.
     */
    fun add(text: String, reading: String?, number: Double?) {
        read++
        val word = Readings.text(text).first ?: return count(Drop.TEXT)
        val (normal, problem) = Readings.reading(lang, word, reading)
        when (problem) {
            null -> {}
            Problem.NO_READING -> return count(Drop.NO_READING)
            Problem.READING_COUNT -> return count(Drop.READING_COUNT)
            else -> return count(Drop.READING)
        }
        val key = "$word\t${normal.orEmpty()}"
        if (key in best) {
            count(Drop.DUPLICATE)
            val old = best[key]
            if (number != null && (old == null || number > old)) best[key] = number
        } else {
            best[key] = number
        }
    }

    private fun count(reason: Drop) {
        drops.merge(reason, 1, Int::plus)
    }

    /**
     * The words as entries of [Source.DOWNLOAD], weights scaled to 0–100 as an import is
     * ([Weights.scale]: logarithm against the largest frequency above 100, 50 without one), most
     * weight first, then by word.
     * Notes: made one at a time as the sequence is read, so a pack of half a million words is
     * never held as entry objects (the phone's heap is about 192 MB).
     */
    fun entries(): Sequence<Entry> {
        val keys = best.keys.toTypedArray()
        val weights = Weights.scale(keys.map { best[it] }, generated = false).toIntArray()
        val order = keys.indices.sortedWith(compareByDescending<Int> { weights[it] }.thenBy { keys[it] })
        return order.asSequence().map { i ->
            val tab = keys[i].indexOf('\t')
            Entry(lang, keys[i].substring(0, tab), keys[i].substring(tab + 1).ifEmpty { null }, weights[i], Source.DOWNLOAD)
        }
    }
}

/**
 * Converts the formats of the dictionary sources Kotomo downloads into its entries, as pure
 * functions over lines (unit-tested; the host tests run the same code over the pinned files).
 */
object Convert {
    /**
     * Reads a Rime word list: the lines after `...`, `text<TAB>code[<TAB>weight]`, `#` comments.
     * Inputs: weighted false ignores the third column.
     * Notes: Chinese codes holding capital letters are spelled-out Latin letters (`B zhan`,
     * zhwiki), not pinyin: READING. English words must start with a letter or digit (the
     * English list also holds markup such as `&nbsp;`): FILTERED; their code is not a reading.
     */
    fun rime(lines: Sequence<String>, weighted: Boolean, out: Conversion) {
        var body = false
        for (line in lines) {
            if (!body) {
                body = line.trim() == "..."
                continue
            }
            if (line.isBlank() || line.startsWith("#")) continue
            val c = line.removeSuffix("\r").split('\t')
            if (c.size > 3) {
                out.drop(Drop.FORMAT)
                continue
            }
            val number = if (weighted) number(c.getOrNull(2)) else null
            if (out.lang == Lang.EN) {
                if (!ENGLISH.matches(c[0].trim())) out.drop(Drop.FILTERED) else out.add(c[0], null, number)
                continue
            }
            val code = c.getOrNull(1)
            if (code != null && code != code.lowercase()) out.drop(Drop.READING) else out.add(c[0], code, number)
        }
    }

    private val ENGLISH = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M} '’.+#/&_-]*")

    /**
     * Reads the single characters of a Rime word list (rime-ice `8105`) into the reading each one
     * can be trusted with.
     * Returns: character → toneless pinyin for characters whose most frequent reading is at least
     * [DOMINANT] times as frequent as the next one; the other polyphonic characters are listed
     * apart, so a word built from them is AMBIGUOUS rather than given a wrong reading (行 xing / hang).
     */
    fun characters(lines: Sequence<String>): CharReadings {
        val readings = HashMap<Int, MutableList<Pair<String, Double>>>()
        var body = false
        for (line in lines) {
            if (!body) {
                body = line.trim() == "..."
                continue
            }
            if (line.isBlank() || line.startsWith("#")) continue
            val c = line.split('\t')
            if (c.size < 2 || c[0].codePointCount(0, c[0].length) != 1) continue
            readings.getOrPut(c[0].codePointAt(0)) { ArrayList() }.add(c[1].trim() to (number(c.getOrNull(2)) ?: 0.0))
        }
        val trusted = readings.mapNotNull { (cp, rs) ->
            val sorted = rs.distinctBy { it.first }.sortedByDescending { it.second }
            val second = sorted.getOrNull(1)
            if (second == null || sorted[0].second >= DOMINANT * second.second) cp to sorted[0].first else null
        }.toMap()
        return CharReadings(trusted, readings.keys - trusted.keys)
    }

    /** Readings of single characters: the ones trusted alone, and the polyphones left out. */
    data class CharReadings(val trusted: Map<Int, String>, val polyphones: Set<Int>)

    /** How much more frequent a character's first reading must be than its second to be trusted alone. */
    const val DOMINANT = 10.0

    /**
     * Reads a THUOCL list (`word<TAB>document frequency`, padded with spaces), with readings from
     * [chars] ([characters]).
     * Notes: THUOCL has no pinyin. A word with a character that has no trusted reading is
     * AMBIGUOUS when the character is a polyphone and NO_READING otherwise (Latin letters, rare
     * characters).
     */
    fun thuocl(lines: Sequence<String>, chars: CharReadings, out: Conversion) {
        for (line in lines) {
            if (line.isBlank()) continue
            val c = line.split('\t')
            val word = c[0].trim()
            if (c.size != 2 || word.isEmpty()) {
                out.drop(Drop.FORMAT)
                continue
            }
            val cps = word.codePoints().toArray()
            if (cps.any { it !in chars.trusted }) {
                out.drop(if (cps.any { it in chars.polyphones }) Drop.AMBIGUOUS else Drop.NO_READING)
                continue
            }
            out.add(word, cps.joinToString(" ") { chars.trusted.getValue(it) }, number(c[1]))
        }
    }

    /** Parses a non-negative number, or null. */
    private fun number(v: String?): Double? = v?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }
}

/** Where one input file of a source is downloaded from. */
sealed interface Input {
    /**
     * An asset of the latest release of a GitHub repository, found through GitHub's release API
     * and checked against the SHA-256 digest GitHub publishes for it.
     * Inputs: asset matches the asset's name; member, for a zip archive, names the file inside.
     */
    data class Release(val repo: String, val asset: Regex, val member: String? = null) : Input

    /** A file at a fixed HTTPS address whose source publishes no checksum: only HTTPS protects it. */
    data class Plain(val url: String) : Input
}

/** How a source's files become words. */
enum class SourceFormat {
    /** One Rime word list with frequencies. */
    RIME,
    /** One Rime word list without frequencies (every word 50). */
    RIME_UNWEIGHTED,
    /** A THUOCL list, then rime-ice's single-character list for the readings. */
    THUOCL,
}

/**
 * One dictionary Kotomo can download straight from where it is published and convert on the
 * device (PLAN §10.5, ADR-011): what the settings show, where its files come from, how they
 * become words.
 */
data class DictionarySource(
    val id: String,
    val titles: Map<String, String>,
    val lang: Lang,
    val category: String,
    val license: String,
    val licenseUrl: String,
    val attribution: String,
    val inputs: List<Input>,
    val format: SourceFormat,
    val notice: String? = null,
    val compiled: Boolean = false,
) {
    /** Whether every file is checked against a published checksum (else only HTTPS protects it). */
    val checked: Boolean get() = inputs.all { it is Input.Release }

    /** The source's title in the interface [language] (`en`, `zh-CN`, `zh-TW`, `ja`), else English. */
    fun title(language: String): String = titles[language] ?: titles.getValue("en")

    /**
     * Converts the downloaded files, in the order of [inputs].
     * Returns: the conversion with its words and dropped lines.
     */
    fun convert(files: List<File>): Conversion {
        val out = Conversion(lang)
        when (format) {
            SourceFormat.RIME, SourceFormat.RIME_UNWEIGHTED -> files[0].useLines { Convert.rime(it, format == SourceFormat.RIME, out) }
            SourceFormat.THUOCL -> {
                val chars = files[1].useLines(block = Convert::characters)
                files[0].useLines { Convert.thuocl(it, chars, out) }
            }
        }
        return out
    }

    /** The hosts its files are fetched from, for the notice before the first download. */
    val hosts: List<String> get() = inputs.flatMap {
        when (it) {
            is Input.Release -> listOf("api.github.com", "github.com", "release-assets.githubusercontent.com")
            is Input.Plain -> listOf(java.net.URI(it.url).host)
        }
    }.distinct()
}

/**
 * The dictionaries the settings offer for download (PLAN §10.5 "词库数据"; the evaluation of
 * every approved source is in `doc/en-us/dictionaries.md`).
 *
 * Notes: small sources write plain-text tables that librime reads at schema selection; large
 * Chinese sources (`rime-ice` `base` and `ext`, `compiled = true`) compile into `kotomo_packs_bin.table.bin`
 * in the background `:deploy` process so loading stays instant on the key path. Japanese sources wait
 * for Mozc's user dictionary. Every one is credited on the data sources page (`settings/About.kt`).
 */
object Sources {
    private const val ICE = "iDvel/rime-ice"
    private const val ICE_RAW = "https://raw.githubusercontent.com/iDvel/rime-ice/main/"
    private const val THUOCL_RAW = "https://raw.githubusercontent.com/thunlp/THUOCL/master/data/"
    private const val ICE_BY = "rime-ice by iDvel and contributors"
    private const val THUOCL_BY = "THUOCL: Tsinghua Open Chinese Lexicon (THUNLP, 2016); readings from rime-ice"
    private const val THUOCL_CITE = "Han Shiyi, Zhang Yuhui, Ma Yunshan, Tu Cunchao, Guo Zhipeng, Liu Zhiyuan, Sun Maosong. THUOCL: Tsinghua Open Chinese Lexicon. Tsinghua University, 2016."
    private const val GPL = "https://www.gnu.org/licenses/gpl-3.0.html"

    private fun titles(en: String, zhCn: String, zhTw: String, ja: String) = mapOf("en" to en, "zh-CN" to zhCn, "zh-TW" to zhTw, "ja" to ja)

    /** The THUOCL lists: file suffix, id suffix, titles. */
    private val THUOCL_LISTS = listOf(
        listOf("IT", "it", "IT terms", "IT 词汇", "IT 詞彙", "IT 用語"),
        listOf("animal", "animal", "Animals", "动物", "動物", "動物"),
        listOf("caijing", "finance", "Finance", "财经", "財經", "金融"),
        listOf("car", "car", "Cars", "汽车", "汽車", "自動車"),
        listOf("chengyu", "idioms", "Idioms", "成语", "成語", "成語"),
        listOf("diming", "places", "Place names", "地名", "地名", "地名"),
        listOf("food", "food", "Food", "食物", "食物", "食べ物"),
        listOf("law", "law", "Law", "法律", "法律", "法律"),
        listOf("lishimingren", "people", "Historical figures", "历史名人", "歷史名人", "歴史上の人物"),
        listOf("medical", "medical", "Medicine", "医学", "醫學", "医学"),
        listOf("poem", "poetry", "Classical poetry", "古诗词", "古詩詞", "漢詩"),
    )

    /** Every downloadable source, in the order the settings list them. */
    val ALL: List<DictionarySource> = buildList {
        add(DictionarySource(
            "zh-web-slang", titles("Web slang (Wikipedia)", "网络用语（维基百科）", "網路用語（維基百科）", "中国語のネットスラング（Wikipedia）"),
            Lang.ZH, "web-slang", "CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0/",
            "Chinese Wikipedia, through fcitx5-pinyin-zhwiki by Felix Yan",
            listOf(Input.Release("felixonmars/fcitx5-pinyin-zhwiki", Regex("web-slang-\\d+\\.dict\\.yaml"))), SourceFormat.RIME_UNWEIGHTED,
        ))
        add(DictionarySource(
            "zh-ice-base", titles("Modern Chinese vocabulary (rime-ice base)", "现代汉语常用与流行词（雾凇 base）", "現代漢語常用與流行詞（霧凇 base）", "現代中国語の基本・流行語彙（rime-ice base）"),
            Lang.ZH, "general", "GPL-3.0", GPL, ICE_BY,
            listOf(Input.Release(ICE, Regex("cn_dicts\\.zip"), "cn_dicts/base.dict.yaml")), SourceFormat.RIME, compiled = true,
        ))
        add(DictionarySource(
            "zh-ice-ext", titles("Extended Chinese vocabulary & slang (rime-ice ext)", "现代汉语扩展与网络词（雾凇 ext）", "現代漢語擴展與網路詞（霧凇 ext）", "現代中国語の拡張・ネット語彙（rime-ice ext）"),
            Lang.ZH, "general", "GPL-3.0", GPL, ICE_BY,
            listOf(Input.Release(ICE, Regex("cn_dicts\\.zip"), "cn_dicts/ext.dict.yaml")), SourceFormat.RIME, compiled = true,
        ))
        for (l in THUOCL_LISTS) {
            add(DictionarySource(
                "zh-thuocl-${l[1]}", titles("${l[2]} (THUOCL)", "${l[3]}（THUOCL）", "${l[4]}（THUOCL）", "中国語の${l[5]}（THUOCL）"),
                Lang.ZH, "domain", "MIT (THUOCL); readings GPL-3.0 (rime-ice)", "https://github.com/thunlp/THUOCL/blob/master/LICENSE", THUOCL_BY,
                listOf(Input.Plain("${THUOCL_RAW}THUOCL_${l[0]}.txt"), Input.Plain("${ICE_RAW}cn_dicts/8105.dict.yaml")), SourceFormat.THUOCL,
                notice = THUOCL_CITE,
            ))
        }
        add(DictionarySource(
            "en-ice-tech", titles("Technical English (rime-ice)", "技术与编程英文（雾凇）", "技術與程式英文（霧凇）", "技術・プログラミング英語（rime-ice）"),
            Lang.EN, "technical", "GPL-3.0", GPL, ICE_BY, listOf(Input.Release(ICE, Regex("en_dicts\\.zip"), "en_dicts/en_ext.dict.yaml")), SourceFormat.RIME_UNWEIGHTED,
        ))
    }

    /** The source with [id], or null. */
    fun of(id: String): DictionarySource? = ALL.firstOrNull { it.id == id }
}
