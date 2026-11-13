package com.yuanzhe.kotomo.ime

/**
 * Conjugates a Japanese word for the forms panel (PLAN §10.1): from what Mozc's dictionary says
 * about a candidate (its entry, part of speech, conjugation type and form, `Mozc.describe`) to its
 * dictionary form and the forms the person may want instead (ます, ません, ました, て, た, ない,
 * ば, potential, passive, causative, volitional, imperative, honorific and humble).
 *
 * Notes: pure Kotlin; one rule table per conjugation type of Mozc's id.def (五段 by row and the
 * irregular 行く, 問う, ラ行特殊 and ある; 一段; カ変; サ変 and サ変 nouns; 形容詞 and いい),
 * each covered by InflectionTest (T16). Honorific and humble forms come from a short table of the
 * verbs that change word (行く → いらっしゃる, 参る), otherwise お…になる and お…する. Nothing
 * here is learned or looked up at run time.
 */
object Inflection {
    /** Names one form of the panel, in the panel's order. */
    enum class Form { PLAIN, POLITE, MASU, MASEN, MASHITA, TE, TA, NAI, BA, POTENTIAL, PASSIVE, CAUSATIVE, VOLITIONAL, IMPERATIVE, HONORIFIC, HUMBLE }

    /** One inflected form: its [text] and hiragana [reading]. */
    data class Inflected(val form: Form, val text: String, val reading: String)

    /** A dictionary form and how it conjugates. */
    data class Word(val text: String, val reading: String, val kind: Kind)

    /** How a word conjugates; [row] is a 五段 verb's last kana (く, ぐ, す, …). */
    sealed interface Kind {
        data class Godan(val row: Char, val special: Special = Special.NONE) : Kind
        data class Ichidan(val kureru: Boolean = false) : Kind
        data object Kuru : Kind
        /** する and the verbs ending in it; [noun] for a サ変 noun (勉強), which takes する. */
        data class Suru(val noun: Boolean = false) : Kind
        data class Adjective(val ii: Boolean = false) : Kind
    }

    /** The 五段 verbs that conjugate unlike their row. */
    enum class Special { NONE, IKU, U_ONBIN, RA_SPECIAL, ARU }

    /**
     * Finds the dictionary form of an entry Mozc described: [text] and [reading] of the entry,
     * [pos] its id.def fields (`動詞,自立,*,*,五段・カ行促音便,連用タ接続`).
     * Returns: the word, or null for a part of speech that does not conjugate or a form these
     * tables do not cover.
     */
    fun word(text: String, reading: String, pos: String): Word? {
        val f = pos.split(',')
        if (f.size < 6 || text.isEmpty() || reading.isEmpty()) return null
        val type = f[4]
        val form = f[5]
        if (f[0] == "名詞" && f[1] == "サ変接続") return Word(text + "する", reading + "する", Kind.Suru(noun = true))
        return when {
            f[0] == "動詞" && type.startsWith("五段") -> godan(text, reading, type, form)
            f[0] == "動詞" && type.startsWith("一段") -> strip(text, reading, ICHIDAN_TAILS[form] ?: return null)
                ?.let { (t, r) -> Word(t + "る", r + "る", Kind.Ichidan(kureru = type == "一段・クレル")) }
            f[0] == "動詞" && type.startsWith("カ変") -> kuru(text, reading, form)
            f[0] == "動詞" && (type == "サ変・スル" || type == "サ変・−スル") -> SURU_TAILS[form]?.firstNotNullOfOrNull { strip(text, reading, it, emptyStem = true) }
                ?.let { (t, r) -> Word(t + "する", r + "する", Kind.Suru()) }
            f[0] == "形容詞" && type.startsWith("形容詞") -> adjective(text, reading, type, form)
            else -> null
        }
    }

    /**
     * Lists the forms of [word] in the panel's order, without the form it already is.
     * Returns: each form once; a form two rules write the same (一段 potential and passive) keeps
     * the first.
     */
    fun forms(word: Word): List<Inflected> {
        val all = when (val k = word.kind) {
            is Kind.Godan -> godanForms(word, k)
            is Kind.Ichidan -> ichidanForms(word, k)
            Kind.Kuru -> kuruForms(word)
            is Kind.Suru -> suruForms(word)
            is Kind.Adjective -> adjectiveForms(word, k)
        } + keigo(word)
        return all.distinctBy { it.text }
    }

    // --- dictionary forms -------------------------------------------------------------------

    private fun godan(text: String, reading: String, type: String, form: String): Word? {
        val row = ROWS.entries.firstOrNull { type.contains(it.key) }?.value ?: return null
        val special = when {
            type.startsWith("五段・カ行促音便") -> Special.IKU
            type == "五段・ワ行ウ音便" -> Special.U_ONBIN
            type == "五段・ラ行特殊" -> Special.RA_SPECIAL
            type == "五段・ラ行アル" -> Special.ARU
            else -> Special.NONE
        }
        if (form == "基本形") return Word(text, reading, Kind.Godan(row, special))
        // Every other covered form ends in one kana after the stem.
        if (form !in GODAN_FORMS || text.length < 2 || reading.length < 2) return null
        return Word(text.dropLast(1) + row, reading.dropLast(1) + row, Kind.Godan(row, special))
    }

    private fun kuru(text: String, reading: String, form: String): Word? {
        val tail = KURU_TAILS[form] ?: return null
        if (!reading.endsWith(tail)) return null
        val readingStem = reading.dropLast(tail.length)
        val kanji = text.lastIndexOf('来')
        val textStem = when {
            kanji >= 0 -> text.substring(0, kanji)
            text.endsWith(tail) -> text.dropLast(tail.length)
            else -> return null
        }
        return Word(textStem + if (kanji >= 0) "来る" else "くる", readingStem + "くる", Kind.Kuru)
    }

    private fun adjective(text: String, reading: String, type: String, form: String): Word? {
        if (type == "形容詞・イイ") return Word(if (text.startsWith("良")) "良い" else "いい", "いい", Kind.Adjective(ii = true))
        val tail = ADJECTIVE_TAILS[form] ?: return null
        return strip(text, reading, tail)?.let { (t, r) -> Word(t + "い", r + "い", Kind.Adjective()) }
    }

    /**
     * Drops [tail] from both [text] and [reading]; null when either does not end with it, or when
     * nothing is left unless [emptyStem] (する itself).
     */
    private fun strip(text: String, reading: String, tail: String, emptyStem: Boolean = false): Pair<String, String>? {
        if (!text.endsWith(tail) || !reading.endsWith(tail)) return null
        val t = text.dropLast(tail.length)
        val r = reading.dropLast(tail.length)
        return if (!emptyStem && (t.isEmpty() || r.isEmpty())) null else t to r
    }

    // --- forms ------------------------------------------------------------------------------

    private fun godanForms(w: Word, k: Kind.Godan): List<Inflected> {
        val (a, i, e, o) = COLUMNS.getValue(k.row)
        val voiced = k.row in "ぐぬぶむ"
        val onbin = when {
            k.special == Special.IKU -> 'っ'
            k.special == Special.U_ONBIN -> 'う'
            else -> ONBIN.getValue(k.row)
        }
        val stemText = w.text.dropLast(1)
        val stemReading = w.reading.dropLast(1)
        fun of(form: Form, tail: String) = Inflected(form, stemText + tail, stemReading + tail)
        val masuStem = if (k.special == Special.RA_SPECIAL) "い" else i.toString()
        return buildList {
            add(of(Form.MASU, masuStem + "ます"))
            add(of(Form.MASEN, masuStem + "ません"))
            add(of(Form.MASHITA, masuStem + "ました"))
            add(of(Form.TE, "$onbin" + if (voiced) "で" else "て"))
            add(of(Form.TA, "$onbin" + if (voiced) "だ" else "た"))
            // ある has no あらない: its negative is ない.
            add(if (k.special == Special.ARU) Inflected(Form.NAI, "ない", "ない") else of(Form.NAI, "${a}ない"))
            add(of(Form.BA, "${e}ば"))
            if (k.special != Special.ARU) add(of(Form.POTENTIAL, "${e}る"))
            add(of(Form.PASSIVE, "${a}れる"))
            add(of(Form.CAUSATIVE, "${a}せる"))
            add(of(Form.VOLITIONAL, "${o}う"))
            add(of(Form.IMPERATIVE, if (k.special == Special.RA_SPECIAL) "い" else e.toString()))
        }
    }

    private fun ichidanForms(w: Word, k: Kind.Ichidan): List<Inflected> {
        val stemText = w.text.dropLast(1)
        val stemReading = w.reading.dropLast(1)
        fun of(form: Form, tail: String) = Inflected(form, stemText + tail, stemReading + tail)
        return listOf(
            of(Form.MASU, "ます"), of(Form.MASEN, "ません"), of(Form.MASHITA, "ました"),
            of(Form.TE, "て"), of(Form.TA, "た"), of(Form.NAI, "ない"), of(Form.BA, "れば"),
            of(Form.POTENTIAL, "られる"), of(Form.PASSIVE, "られる"), of(Form.CAUSATIVE, "させる"),
            of(Form.VOLITIONAL, "よう"), of(Form.IMPERATIVE, if (k.kureru) "" else "ろ"),
        )
    }

    private fun kuruForms(w: Word): List<Inflected> {
        val kanji = w.text.endsWith("来る")
        // 来る and くる are both two characters.
        val stemText = w.text.dropLast(2)
        val stemReading = w.reading.dropLast(2)
        // Each form: the reading's kana for 来 (き, こ, く) and the kana after it.
        fun of(form: Form, kuruKana: String, rest: String) =
            Inflected(form, stemText + (if (kanji) "来" else kuruKana) + rest, stemReading + kuruKana + rest)
        return listOf(
            of(Form.MASU, "き", "ます"), of(Form.MASEN, "き", "ません"), of(Form.MASHITA, "き", "ました"),
            of(Form.TE, "き", "て"), of(Form.TA, "き", "た"), of(Form.NAI, "こ", "ない"), of(Form.BA, "く", "れば"),
            of(Form.POTENTIAL, "こ", "られる"), of(Form.PASSIVE, "こ", "られる"), of(Form.CAUSATIVE, "こ", "させる"),
            of(Form.VOLITIONAL, "こ", "よう"), of(Form.IMPERATIVE, "こ", "い"),
        )
    }

    private fun suruForms(w: Word): List<Inflected> {
        val stemText = w.text.removeSuffix("する")
        val stemReading = w.reading.removeSuffix("する")
        fun of(form: Form, tail: String, readingTail: String = tail) = Inflected(form, stemText + tail, stemReading + readingTail)
        return listOf(
            of(Form.MASU, "します"), of(Form.MASEN, "しません"), of(Form.MASHITA, "しました"),
            of(Form.TE, "して"), of(Form.TA, "した"), of(Form.NAI, "しない"), of(Form.BA, "すれば"),
            of(Form.POTENTIAL, "できる"), of(Form.PASSIVE, "される"), of(Form.CAUSATIVE, "させる"),
            of(Form.VOLITIONAL, "しよう"), of(Form.IMPERATIVE, "しろ"),
        )
    }

    private fun adjectiveForms(w: Word, k: Kind.Adjective): List<Inflected> {
        // いい conjugates from よい (よくて, よかった).
        val stemText = if (k.ii) (if (w.text.startsWith("良")) "良" else "よ") else w.text.dropLast(1)
        val stemReading = if (k.ii) "よ" else w.reading.dropLast(1)
        fun of(form: Form, tail: String) = Inflected(form, stemText + tail, stemReading + tail)
        return listOf(
            Inflected(Form.POLITE, w.text + "です", w.reading + "です"),
            of(Form.TE, "くて"), of(Form.TA, "かった"), of(Form.NAI, "くない"), of(Form.BA, "ければ"),
        )
    }

    /** The honorific and humble forms, polite (ます): from [KEIGO], else お…になる and お…する. */
    private fun keigo(w: Word): List<Inflected> {
        KEIGO[w.text]?.let { (honorific, humble) ->
            return listOfNotNull(
                honorific?.let { Inflected(Form.HONORIFIC, it.first, it.second) },
                humble?.let { Inflected(Form.HUMBLE, it.first, it.second) },
            )
        }
        return when (val k = w.kind) {
            is Kind.Suru -> if (!k.noun) emptyList() else {
                val noun = w.text.removeSuffix("する")
                val nounReading = w.reading.removeSuffix("する")
                listOf(Inflected(Form.HONORIFIC, noun + "なさいます", nounReading + "なさいます"), Inflected(Form.HUMBLE, noun + "いたします", nounReading + "いたします"))
            }
            is Kind.Godan, is Kind.Ichidan -> {
                // お + the ます stem; only for a verb written with a kanji, as these forms are.
                val masu = forms(w.kind, w).firstOrNull { it.form == Form.MASU } ?: return emptyList()
                if (masu.text.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return emptyList()
                val stemText = masu.text.removeSuffix("ます")
                val stemReading = masu.reading.removeSuffix("ます")
                listOf(
                    Inflected(Form.HONORIFIC, "お${stemText}になります", "お${stemReading}になります"),
                    Inflected(Form.HUMBLE, "お${stemText}します", "お${stemReading}します"),
                )
            }
            else -> emptyList()
        }
    }

    /** The plain forms alone (no keigo), for building the generic keigo from the ます stem. */
    private fun forms(kind: Kind, w: Word): List<Inflected> = when (kind) {
        is Kind.Godan -> godanForms(w, kind)
        is Kind.Ichidan -> ichidanForms(w, kind)
        else -> emptyList()
    }

    /** 五段 rows by the kana id.def names in the conjugation type. */
    private val ROWS = linkedMapOf(
        "カ行" to 'く', "ガ行" to 'ぐ', "サ行" to 'す', "タ行" to 'つ', "ナ行" to 'ぬ',
        "バ行" to 'ぶ', "マ行" to 'む', "ラ行" to 'る', "ワ行" to 'う',
    )

    /** A 五段 row's あ, い, え and お columns. */
    private val COLUMNS = mapOf(
        'く' to "かきけこ", 'ぐ' to "がぎげご", 'す' to "さしせそ", 'つ' to "たちてと", 'ぬ' to "なにねの",
        'ぶ' to "ばびべぼ", 'む' to "まみめも", 'る' to "らりれろ", 'う' to "わいえお",
    ).mapValues { (_, c) -> listOf(c[0], c[1], c[2], c[3]) }

    /** The sound before て and た (音便) per 五段 row. */
    private val ONBIN = mapOf('く' to 'い', 'ぐ' to 'い', 'す' to 'し', 'つ' to 'っ', 'ぬ' to 'ん', 'ぶ' to 'ん', 'む' to 'ん', 'る' to 'っ', 'う' to 'っ')

    /** The 五段 forms (id.def names) whose last kana replaces the dictionary form's. */
    private val GODAN_FORMS = setOf("未然形", "未然ウ接続", "連用形", "連用タ接続", "仮定形", "命令ｅ")

    /** The kana after a 一段 stem per form. */
    private val ICHIDAN_TAILS = mapOf("基本形" to "る", "未然形" to "", "連用形" to "", "仮定形" to "れ", "命令ｒｏ" to "ろ", "命令ｙｏ" to "よ", "未然ウ接続" to "よ")

    /** The reading after the stem of a カ変 verb per form. */
    private val KURU_TAILS = mapOf("基本形" to "くる", "連用形" to "き", "未然形" to "こ", "仮定形" to "くれ", "命令ｉ" to "こい", "命令ｙｏ" to "こよ", "未然ウ接続" to "こよ")

    /** The kana after a サ変 stem per form (未然形 has three). */
    private val SURU_TAILS = mapOf(
        "基本形" to listOf("する"), "連用形" to listOf("し"), "未然形" to listOf("し", "さ", "せ"),
        "仮定形" to listOf("すれ"), "命令ｒｏ" to listOf("しろ"), "命令ｙｏ" to listOf("せよ"), "未然ウ接続" to listOf("しよ"),
    )

    /** The kana after an adjective's stem per form. */
    private val ADJECTIVE_TAILS = mapOf("基本形" to "い", "連用テ接続" to "く", "連用タ接続" to "かっ", "仮定形" to "けれ", "未然ウ接続" to "かろ", "ガル接続" to "", "体言接続" to "き")

    /**
     * The verbs whose honorific or humble form is another word (polite, with readings); null where
     * there is none in common use.
     */
    private val KEIGO: Map<String, Pair<Pair<String, String>?, Pair<String, String>?>> = buildMap {
        fun put(words: List<String>, honorific: Pair<String, String>?, humble: Pair<String, String>?) = words.forEach { put(it, honorific to humble) }
        val irassharu = "いらっしゃいます" to "いらっしゃいます"
        put(listOf("行く", "いく"), irassharu, "参ります" to "まいります")
        put(listOf("来る", "くる"), irassharu, "参ります" to "まいります")
        put(listOf("いる", "居る"), irassharu, "おります" to "おります")
        put(listOf("言う", "いう"), "おっしゃいます" to "おっしゃいます", "申します" to "もうします")
        put(listOf("する"), "なさいます" to "なさいます", "いたします" to "いたします")
        put(listOf("食べる", "飲む"), "召し上がります" to "めしあがります", "いただきます" to "いただきます")
        put(listOf("見る"), "ご覧になります" to "ごらんになります", "拝見します" to "はいけんします")
        put(listOf("知る"), "ご存じです" to "ごぞんじです", "存じております" to "ぞんじております")
        put(listOf("会う"), "お会いになります" to "おあいになります", "お目にかかります" to "おめにかかります")
        put(listOf("聞く"), "お聞きになります" to "おききになります", "伺います" to "うかがいます")
        put(listOf("訪ねる"), "お訪ねになります" to "おたずねになります", "伺います" to "うかがいます")
        put(listOf("思う"), "お思いになります" to "おおもいになります", "存じます" to "ぞんじます")
        put(listOf("あげる", "上げる"), null, "差し上げます" to "さしあげます")
        put(listOf("もらう", "貰う"), null, "いただきます" to "いただきます")
        put(listOf("くれる", "呉れる"), "くださいます" to "くださいます", null)
        put(listOf("寝る"), "お休みになります" to "おやすみになります", null)
        put(listOf("着る"), "お召しになります" to "おめしになります", null)
    }
}
