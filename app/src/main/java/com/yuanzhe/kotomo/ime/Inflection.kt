package com.yuanzhe.kotomo.ime

/**
 * Conjugates Japanese words and phrases across four politeness registers for [FormsPanel]
 * (PLAN §10.1): verbs, i-adjectives, na-adjectives, curated keigo nouns/pronouns/adverbs, and
 * polite expressions, plus whole-sentence predicate extraction preserving prefixes and
 * sentence-final particles.
 *
 * Notes: pure Kotlin; zero network or database access. Verb and adjective rules follow Mozc's
 * id.def conjugation types (including row inference for generic `五段動詞` entries) covered by
 * `InflectionTest` (T16); honorific, humble, and lexical keigo tables guard irregular verbs
 * (`行く`, `来る`, `死ぬ`, `知る`, `わかる`), Sino-Japanese `サ変` prefixes (`ご〜`), and 1-mora
 * Ichidan stems (`出る`).
 */
object Inflection {
    /** The four politeness registers shown as tabs in [FormsPanel], in tab order. */
    enum class Register { PLAIN, POLITE, HONORIFIC, HUMBLE }

    /**
     * Names one inflected or lexical variant of the panel.
     * Each entry carries a distinct enum constant within a word so lookups by [Form] stay unique.
     */
    enum class Form(val register: Register) {
        PLAIN(Register.PLAIN),
        NAI(Register.PLAIN),
        TA(Register.PLAIN),
        TE(Register.PLAIN),
        PAST_NEGATIVE(Register.PLAIN),
        BA(Register.PLAIN),
        POTENTIAL(Register.PLAIN),
        VOLITIONAL(Register.PLAIN),
        PASSIVE(Register.PLAIN),
        CAUSATIVE(Register.PLAIN),
        IMPERATIVE(Register.PLAIN),
        ADNOMINAL(Register.PLAIN),
        ADVERBIAL(Register.PLAIN),
        POLITE(Register.POLITE),
        MASU(Register.POLITE),
        MASEN(Register.POLITE),
        MASHITA(Register.POLITE),
        MASENDESHITA(Register.POLITE),
        MASHOU(Register.POLITE),
        POTENTIAL_POLITE(Register.POLITE),
        TE_KUDASAI(Register.POLITE),
        POLITE_NEGATIVE(Register.POLITE),
        HONORIFIC_PLAIN(Register.HONORIFIC),
        HONORIFIC(Register.HONORIFIC),
        HONORIFIC_ALT(Register.HONORIFIC),
        HONORIFIC_PAST(Register.HONORIFIC),
        HONORIFIC_PASSIVE(Register.HONORIFIC),
        HONORIFIC_REQUEST(Register.HONORIFIC),
        HUMBLE_PLAIN(Register.HUMBLE),
        HUMBLE(Register.HUMBLE),
        HUMBLE_ALT(Register.HUMBLE),
        HUMBLE_PAST(Register.HUMBLE),
    }

    /** Lexical category for non-inflecting words that carry register/keigo alternates. */
    enum class LexicalCategory { NOUN, PRONOUN, ADVERB, EXPRESSION }

    /** One form candidate: its surface [text], hiragana [reading], target [register], and optional label string resource. */
    data class Inflected(
        val form: Form,
        val text: String,
        val reading: String,
        val register: Register = form.register,
        val customLabel: Int? = null,
    )

    /** A dictionary form (or lexical base entry) and how it conjugates or substitutes. */
    data class Word(val text: String, val reading: String, val kind: Kind)

    /** How a word conjugates or substitutes across registers. */
    sealed interface Kind {
        data class Godan(val row: Char, val special: Special = Special.NONE) : Kind
        data class Ichidan(val kureru: Boolean = false) : Kind
        data object Kuru : Kind
        /** する and verbs ending in it; [noun] for a サ変 noun (勉強), which takes する. */
        data class Suru(val noun: Boolean = false) : Kind
        data class Adjective(val ii: Boolean = false) : Kind
        data object NaAdjective : Kind
        data class Lexical(val category: LexicalCategory, val entries: List<Inflected>) : Kind
    }

    /** The 五段 verbs that conjugate unlike their row. */
    enum class Special { NONE, IKU, U_ONBIN, RA_SPECIAL, ARU }

    /** Minimal dictionary description fields returned by Mozc's `Describe`. */
    data class DescribedWord(val entry: String, val reading: String, val pos: String)

    /**
     * Extracted target inside a single word or multi-segment sentence: everything before ([prefix])
     * and after ([suffix], such as sentence-final particles `か`/`ね`/`よ` and punctuation) stays
     * intact when replacing [currentSurface] with one of [forms].
     */
    data class Target(
        val prefix: String,
        val word: Word,
        val currentSurface: String,
        val suffix: String,
        val forms: List<Inflected>,
    )

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
        if (f[0] == "名詞" && f[1] == "形容動詞語幹") {
            return if (text in NON_NA_ADJECTIVES) null else Word(text, reading, Kind.NaAdjective)
        }
        return when {
            f[0] == "動詞" && type.startsWith("五段") -> godan(text, reading, type, form)
            f[0] == "動詞" && type.startsWith("一段") -> {
                val tails = if (form == "未然ウ接続") listOf("よう", "よ") else listOfNotNull(ICHIDAN_TAILS[form])
                tails.firstNotNullOfOrNull { strip(text, reading, it) }
                    ?.let { (t, r) -> Word(t + "る", r + "る", Kind.Ichidan(kureru = type == "一段・クレル")) }
            }
            f[0] == "動詞" && type.startsWith("カ変") -> kuru(text, reading, form)
            f[0] == "動詞" && (type == "サ変・スル" || type == "サ変・−スル") -> SURU_TAILS[form]?.firstNotNullOfOrNull { strip(text, reading, it, emptyStem = true) }
                ?.let { (t, r) -> Word(t + "する", r + "する", Kind.Suru()) }
            f[0] == "形容詞" && type.startsWith("形容詞") -> adjective(text, reading, type, form)
            else -> null
        }
    }

    /**
     * Looks up a curated keigo noun, pronoun, adverb, or polite expression matching [surface] or
     * [reading].
     * Returns: a [Word] with [Kind.Lexical], or null when [surface] is not in the curated table.
     */
    fun lexical(surface: String, reading: String = ""): Word? {
        if (surface.isEmpty() && reading.isEmpty()) return null
        return LEXICAL_BY_KEY[surface] ?: reading.takeIf { it.isNotEmpty() }?.let { LEXICAL_BY_KEY[it] }
    }

    /**
     * Lists all forms of [word] across the four [Register]s in display order.
     * Notes: deduplicates within each register (`register to text`), so forms shared across
     * registers (such as potential polite and passive honorific) remain reachable in both tabs.
     */
    fun forms(word: Word): List<Inflected> {
        val all = when (val k = word.kind) {
            is Kind.Godan -> godanForms(word, k) + keigo(word)
            is Kind.Ichidan -> ichidanForms(word, k) + keigo(word)
            Kind.Kuru -> kuruForms(word) + keigo(word)
            is Kind.Suru -> suruForms(word) + keigo(word)
            is Kind.Adjective -> adjectiveForms(word, k) + keigo(word)
            Kind.NaAdjective -> naAdjectiveForms(word)
            is Kind.Lexical -> k.entries
        }
        return all.distinctBy { it.register to it.text }
    }

    /**
     * Lists all forms including the dictionary/plain entry itself, filtered to exclude forms
     * grammatically incompatible with a trailing particle [suffix] (such as imperative + `か` or
     * `静かだか`).
     */
    fun formsFor(word: Word, suffix: String = ""): List<Inflected> {
        val base = when (word.kind) {
            is Kind.Lexical -> emptyList()
            Kind.NaAdjective -> emptyList()
            else -> listOf(Inflected(Form.PLAIN, word.text, word.reading))
        }
        val raw = (base + forms(word)).distinctBy { it.register to it.text }
        if (!suffix.startsWith("か")) return raw
        return raw.filterNot { f ->
            f.form == Form.IMPERATIVE ||
                f.form == Form.TE_KUDASAI ||
                f.form == Form.HONORIFIC_REQUEST ||
                f.form == Form.TE ||
                f.form == Form.ADNOMINAL ||
                f.form == Form.ADVERBIAL ||
                (word.kind == Kind.NaAdjective && f.form == Form.PLAIN)
        }
    }

    /**
     * Resolves a surface/reading pair using a Mozc [described] result first, falling back to
     * curated [lexical] entries and a pure-Kotlin suffix [deinflect] pass.
     */
    fun resolve(surface: String, reading: String, described: DescribedWord? = null): Word? {
        val fromMozc = described?.let { word(it.entry, it.reading, it.pos) }
        val fromLex = lexical(surface, reading)
        if (fromLex != null) {
            // For expressions and keigo nouns/pronouns, prefer the curated register set; if the
            // entry also inflects as a na-adjective (e.g. 大丈夫), merge them.
            if (fromMozc != null && fromMozc.kind == Kind.NaAdjective && fromLex.kind is Kind.Lexical) {
                val combined = (forms(fromMozc) + fromLex.kind.entries).distinctBy { it.register to it.text }
                return Word(fromMozc.text, fromMozc.reading, Kind.Lexical(LexicalCategory.EXPRESSION, combined))
            }
            if (fromMozc == null || fromLex.kind is Kind.Lexical) return fromLex
        }
        return fromMozc ?: deinflect(surface, reading)
    }

    /**
     * Extracts the target predicate/word and its surrounding [Target.prefix] and [Target.suffix]
     * from a single-word or multi-segment sentence represented as `(surface, reading)` pairs.
     * Inputs: [segments] in sentence order; [describe] queries Mozc's dictionary on the engine thread.
     * Returns: a [Target] with at least one form different from [Target.currentSurface] (or any
     * valid form set for single words), or null when no conjugable/keigo target is found.
     */
    fun extractTarget(
        segments: List<Pair<String, String>>,
        describe: (surface: String, reading: String) -> DescribedWord?,
    ): Target? {
        if (segments.isEmpty()) return null
        val fullSurface = segments.joinToString("") { it.first }
        val fullReading = segments.joinToString("") { it.second }
        if (fullSurface.isEmpty()) return null

        lexical(fullSurface, fullReading)?.let { w ->
            val f = formsFor(w, "")
            if (f.isNotEmpty()) return Target("", w, fullSurface, "", f)
        }

        // Peel trailing punctuation and standalone sentence-final particle segments, keeping at
        // least one segment so the whole sentence never empties out.
        var bodyEnd = segments.size
        val suffixParts = ArrayDeque<String>()
        while (bodyEnd > 1) {
            val segText = segments[bodyEnd - 1].first
            if (segText.isEmpty() || segText.all { it in SENTENCE_PUNCTUATION } || segText in SENTENCE_PARTICLES) {
                suffixParts.addFirst(segText)
                bodyEnd--
            } else {
                break
            }
        }
        var suffix = suffixParts.joinToString("")
        val trimmed = segments.subList(0, bodyEnd)
        val trimmedSurface = trimmed.joinToString("") { it.first }
        val trimmedReading = trimmed.joinToString("") { it.second }
        lexical(trimmedSurface, trimmedReading)?.let { w ->
            val f = formsFor(w, suffix)
            if (f.isNotEmpty()) return Target("", w, trimmedSurface, suffix, f)
        }

        // Strip trailing punctuation stuck inside the last segment (e.g. `行くか？`).
        var (predSurface, predReading) = trimmed.last()
        var predPrefix = trimmed.dropLast(1).joinToString("") { it.first }
        while (predSurface.length > 1 && predSurface.last() in SENTENCE_PUNCTUATION) {
            val ch = predSurface.last()
            suffix = ch + suffix
            predSurface = predSurface.dropLast(1)
            if (predReading.endsWith(ch)) predReading = predReading.dropLast(1)
        }

        // Merge `<サ変接続> + <する conjugation>` across the last two segments (e.g. `勉強 + します`).
        if (trimmed.size >= 2) {
            val prev = trimmed[trimmed.size - 2]
            val prevDesc = if (prev.second.isNotEmpty()) describe(prev.first, prev.second) else null
            val prevPos = prevDesc?.pos?.split(',')
            val isSuruNoun = prevPos != null && prevPos.size >= 2 && prevPos[0] == "名詞" && prevPos[1] == "サ変接続" &&
                prevDesc.entry == prev.first
            val looksSuruTail = predSurface in SURU_AUX_SURFACES ||
                (describe(predSurface, predReading)?.pos?.contains("サ変・スル") == true)
            if (isSuruNoun && looksSuruTail) {
                val mergedSurface = prev.first + predSurface
                val mergedReading = prev.second + predReading
                val mergedDesc = describe(mergedSurface, mergedReading)
                resolve(mergedSurface, mergedReading, mergedDesc)?.let { w ->
                    val f = formsFor(w, suffix)
                    if (f.isNotEmpty()) {
                        return Target(
                            prefix = trimmed.dropLast(2).joinToString("") { it.first },
                            word = w,
                            currentSurface = mergedSurface,
                            suffix = suffix,
                            forms = f,
                        )
                    }
                }
            }
        }

        // Try resolving the final segment directly, or after stripping a stuck final particle
        // (`行くか`, `静かですね`, `行くよ`).
        resolve(predSurface, predReading, if (predReading.isNotEmpty()) describe(predSurface, predReading) else null)?.let { w ->
            val f = formsFor(w, suffix)
            if (f.isNotEmpty()) return Target(predPrefix, w, predSurface, suffix, f)
        }

        for (particle in ATTACHED_FINAL_PARTICLES) {
            if (predSurface.length > particle.length && predSurface.endsWith(particle)) {
                val stemS = predSurface.dropLast(particle.length)
                val stemR = if (predReading.length > particle.length && predReading.endsWith(particle)) {
                    predReading.dropLast(particle.length)
                } else {
                    stemS
                }
                val nextSuffix = particle + suffix
                val d = if (stemR.isNotEmpty()) describe(stemS, stemR) else null
                resolve(stemS, stemR, d)?.let { w ->
                    val f = formsFor(w, nextSuffix)
                    if (f.isNotEmpty()) return Target(predPrefix, w, stemS, nextSuffix, f)
                }
            }
        }

        return null
    }

    /**
     * Converts hiragana or full-width katakana into half-width katakana (`ｱ`–`ﾝ`, `ﾞ`, `ﾟ`, `ｰ`).
     * Used by [FormsPanel] for the script variants strip on single words.
     */
    fun halfWidthKatakana(kana: String): String {
        if (kana.isEmpty()) return ""
        val out = StringBuilder(kana.length * 2)
        for (raw in kana) {
            val ch = if (raw in '\u3041'..'\u3096') (raw.code + 0x60).toChar() else raw
            val mapped = HALF_KATA_MAP[ch]
            if (mapped != null) {
                out.append(mapped)
            } else {
                out.append(ch)
            }
        }
        return out.toString()
    }

    // --- dictionary forms -------------------------------------------------------------------

    private fun godan(text: String, reading: String, type: String, form: String): Word? {
        // Mozc's dictionary stores pre-inflected volitional tokens with trailing う (行こう, 書こう,
        // 飲もう) alongside bare 未然ウ接続 stems (行こ). Normalize trailing う before deriving row.
        val (t, r) = if (form == "未然ウ接続" && text.length >= 3 && reading.length >= 3 &&
            text.endsWith("う") && reading.endsWith("う") && reading[reading.length - 2] in GODAN_O_TO_ROW
        ) {
            text.dropLast(1) to reading.dropLast(1)
        } else {
            text to reading
        }
        var row = ROWS.entries.firstOrNull { type.contains(it.key) }?.value
        if (row == null && type == "五段動詞") {
            if (t.length < 2 || r.length < 2) return null
            val last = r.last()
            row = when (form) {
                "基本形" -> last.takeIf { it in COLUMNS }
                "未然形" -> GODAN_A_TO_ROW[last]
                "連用形" -> GODAN_I_TO_ROW[last]
                "仮定形", "命令ｅ" -> GODAN_E_TO_ROW[last]
                "未然ウ接続" -> GODAN_O_TO_ROW[last]
                else -> null
            } ?: return null
            if (form != "基本形" && t.last() != r.last()) return null
            return if (form == "基本形") {
                Word(t, r, Kind.Godan(row))
            } else {
                Word(t.dropLast(1) + row, r.dropLast(1) + row, Kind.Godan(row))
            }
        }
        if (row == null) return null
        val special = when {
            type.startsWith("五段・カ行促音便") -> Special.IKU
            type == "五段・ワ行ウ音便" -> Special.U_ONBIN
            type == "五段・ラ行特殊" -> Special.RA_SPECIAL
            type == "五段・ラ行アル" -> Special.ARU
            else -> Special.NONE
        }
        if (form == "基本形") return Word(t, r, Kind.Godan(row, special))
        if (form !in GODAN_FORMS || t.length < 2 || r.length < 2) return null
        return Word(t.dropLast(1) + row, r.dropLast(1) + row, Kind.Godan(row, special))
    }

    private fun kuru(text: String, reading: String, form: String): Word? {
        val tails = if (form == "未然ウ接続") listOf("こよう", "こよ") else listOfNotNull(KURU_TAILS[form])
        val tail = tails.firstOrNull { reading.endsWith(it) } ?: return null
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
     * Pure-Kotlin suffix fallback when Mozc has no direct dictionary stem for an inflected piece
     * (for instance, `行こう` / `行きます` / `食べません` / `静かでした` inside a Mixed segment).
     */
    private fun deinflect(surface: String, reading: String): Word? {
        if (surface.length < 2) return null
        val r = reading.ifEmpty { surface }
        // Na-adjective endings when surface ends with copula/particle.
        for (tail in NA_DEINFLECT_TAILS) {
            if (surface.length > tail.length && surface.endsWith(tail)) {
                val stemT = surface.dropLast(tail.length)
                val stemR = if (r.endsWith(tail) && r.length > tail.length) r.dropLast(tail.length) else stemT
                if (stemT !in NON_NA_ADJECTIVES && lexical(stemT, stemR)?.kind is Kind.NaAdjective) {
                    return Word(stemT, stemR, Kind.NaAdjective)
                }
            }
        }
        // Irregular high-frequency verb inflections (`行こう`, `買いに`, `来ましょう`, etc.).
        DEINFLECT_EXACT[surface]?.let { (baseText, baseReading, kind) ->
            return Word(baseText, baseReading, kind)
        }
        // Suru compounds (`勉強しましょう`, `勉強しました`, `勉強しています`).
        for (tail in SURU_COMPOUND_TAILS) {
            if (surface.length > tail.length && surface.endsWith(tail)) {
                val stemT = surface.dropLast(tail.length)
                val stemR = if (r.endsWith(tail) && r.length > tail.length) r.dropLast(tail.length) else stemT
                if (stemT.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
                    return Word(stemT + "する", stemR + "する", Kind.Suru(noun = true))
                }
            }
        }
        // Generic `〜ます` / `〜ません` / `〜ました` / `〜ましょう` / `〜ませんでした` endings.
        for (masuTail in MASU_TAILS) {
            if (surface.length > masuTail.length && surface.endsWith(masuTail) && r.endsWith(masuTail)) {
                val masuStemT = surface.dropLast(masuTail.length)
                val masuStemR = r.dropLast(masuTail.length)
                if (masuStemT.isNotEmpty() && masuStemR.isNotEmpty()) {
                    val last = masuStemR.last()
                    // Check common Godan i-column stem (e.g. `買い`, `行き`, `飲み`, `読み`, `待ち`).
                    val godanRow = if (masuStemT != "し" && masuStemR != "き" && masuStemR.length >= 2) GODAN_I_TO_ROW[last] else null
                    if (godanRow != null && masuStemT.last() == last) {
                        val special = if (masuStemT == "行き" || masuStemT == "いき") Special.IKU else Special.NONE
                        return Word(masuStemT.dropLast(1) + godanRow, masuStemR.dropLast(1) + godanRow, Kind.Godan(godanRow, special))
                    }
                    // Ichidan e/i-stem (`食べ`, `見`, `起き`).
                    if (last in "えけせてねへめれげぜでべぺいきしちにひみりぎじぢびぴ") {
                        return Word(masuStemT + "る", masuStemR + "る", Kind.Ichidan())
                    }
                }
            }
        }
        return null
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
        val masuStemText = if (k.special == Special.RA_SPECIAL) "い" else i.toString()
        val masuStemReading = if (k.special == Special.RA_SPECIAL) "い" else i.toString()
        val naiStemText = if (k.special == Special.ARU) "" else a.toString()
        val naiText = if (k.special == Special.ARU) "ない" else "$stemText${a}ない"
        val naiReading = if (k.special == Special.ARU) "ない" else "$stemReading${a}ない"
        val teText = "$stemText$onbin" + if (voiced) "で" else "て"
        val teReading = "$stemReading$onbin" + if (voiced) "で" else "て"
        val allowPotential = k.special != Special.ARU && w.text !in NO_POTENTIAL_VERBS
        val allowRequest = k.special != Special.ARU && w.text !in NO_REQUEST_VERBS
        return buildList {
            // Plain register
            add(Inflected(Form.NAI, naiText, naiReading))
            add(of(Form.TA, "$onbin" + if (voiced) "だ" else "た"))
            add(Inflected(Form.TE, teText, teReading))
            add(Inflected(Form.PAST_NEGATIVE, if (k.special == Special.ARU) "なかった" else "$stemText${naiStemText}なかった", if (k.special == Special.ARU) "なかった" else "$stemReading${naiStemText}なかった"))
            add(of(Form.BA, "${e}ば"))
            if (allowPotential) add(of(Form.POTENTIAL, "${e}る"))
            add(of(Form.VOLITIONAL, "${o}う"))
            add(of(Form.PASSIVE, "${a}れる"))
            add(of(Form.CAUSATIVE, "${a}せる"))
            if (k.special != Special.ARU) add(of(Form.IMPERATIVE, if (k.special == Special.RA_SPECIAL) "い" else e.toString()))
            // Polite register
            add(Inflected(Form.MASU, "$stemText${masuStemText}ます", "$stemReading${masuStemReading}ます"))
            add(Inflected(Form.MASEN, "$stemText${masuStemText}ません", "$stemReading${masuStemReading}ません"))
            add(Inflected(Form.MASHITA, "$stemText${masuStemText}ました", "$stemReading${masuStemReading}ました"))
            add(Inflected(Form.MASENDESHITA, "$stemText${masuStemText}ませんでした", "$stemReading${masuStemReading}ませんでした"))
            add(Inflected(Form.MASHOU, "$stemText${masuStemText}ましょう", "$stemReading${masuStemReading}ましょう"))
            if (allowPotential) add(of(Form.POTENTIAL_POLITE, "${e}ます"))
            if (allowRequest) add(Inflected(Form.TE_KUDASAI, "${teText}ください", "${teReading}ください"))
        }
    }

    private fun ichidanForms(w: Word, k: Kind.Ichidan): List<Inflected> {
        val stemText = w.text.dropLast(1)
        val stemReading = w.reading.dropLast(1)
        fun of(form: Form, tail: String) = Inflected(form, stemText + tail, stemReading + tail)
        val allowPotential = w.text !in NO_POTENTIAL_VERBS
        val allowRequest = w.text !in NO_REQUEST_VERBS
        return buildList {
            // Plain register
            add(of(Form.NAI, "ない"))
            add(of(Form.TA, "た"))
            add(of(Form.TE, "て"))
            add(of(Form.PAST_NEGATIVE, "なかった"))
            add(of(Form.BA, "れば"))
            if (allowPotential) add(of(Form.POTENTIAL, "られる"))
            add(of(Form.VOLITIONAL, "よう"))
            add(of(Form.PASSIVE, "られる"))
            add(of(Form.CAUSATIVE, "させる"))
            add(of(Form.IMPERATIVE, if (k.kureru) "" else "ろ"))
            // Polite register
            add(of(Form.MASU, "ます"))
            add(of(Form.MASEN, "ません"))
            add(of(Form.MASHITA, "ました"))
            add(of(Form.MASENDESHITA, "ませんでした"))
            add(of(Form.MASHOU, "ましょう"))
            if (allowPotential) add(of(Form.POTENTIAL_POLITE, "られます"))
            if (allowRequest && !k.kureru) add(of(Form.TE_KUDASAI, "てください"))
        }
    }

    private fun kuruForms(w: Word): List<Inflected> {
        val kanji = w.text.endsWith("来る")
        val stemText = w.text.dropLast(2)
        val stemReading = w.reading.dropLast(2)
        fun of(form: Form, kuruKana: String, rest: String) =
            Inflected(form, stemText + (if (kanji) "来" else kuruKana) + rest, stemReading + kuruKana + rest)
        return listOf(
            // Plain
            of(Form.NAI, "こ", "ない"),
            of(Form.TA, "き", "た"),
            of(Form.TE, "き", "て"),
            of(Form.PAST_NEGATIVE, "こ", "なかった"),
            of(Form.BA, "く", "れば"),
            of(Form.POTENTIAL, "こ", "られる"),
            of(Form.VOLITIONAL, "こ", "よう"),
            of(Form.PASSIVE, "こ", "られる"),
            of(Form.CAUSATIVE, "こ", "させる"),
            of(Form.IMPERATIVE, "こ", "い"),
            // Polite
            of(Form.MASU, "き", "ます"),
            of(Form.MASEN, "き", "ません"),
            of(Form.MASHITA, "き", "ました"),
            of(Form.MASENDESHITA, "き", "ませんでした"),
            of(Form.MASHOU, "き", "ましょう"),
            of(Form.POTENTIAL_POLITE, "こ", "られます"),
            of(Form.TE_KUDASAI, "き", "てください"),
        )
    }

    private fun suruForms(w: Word): List<Inflected> {
        val stemText = w.text.removeSuffix("する")
        val stemReading = w.reading.removeSuffix("する")
        fun of(form: Form, tail: String, readingTail: String = tail) = Inflected(form, stemText + tail, stemReading + readingTail)
        return listOf(
            // Plain
            of(Form.NAI, "しない"),
            of(Form.TA, "した"),
            of(Form.TE, "して"),
            of(Form.PAST_NEGATIVE, "しなかった"),
            of(Form.BA, "すれば"),
            of(Form.POTENTIAL, "できる"),
            of(Form.VOLITIONAL, "しよう"),
            of(Form.PASSIVE, "される"),
            of(Form.CAUSATIVE, "させる"),
            of(Form.IMPERATIVE, "しろ"),
            // Polite
            of(Form.MASU, "します"),
            of(Form.MASEN, "しません"),
            of(Form.MASHITA, "しました"),
            of(Form.MASENDESHITA, "しませんでした"),
            of(Form.MASHOU, "しましょう"),
            of(Form.POTENTIAL_POLITE, "できます"),
            of(Form.TE_KUDASAI, "してください"),
        )
    }

    private fun adjectiveForms(w: Word, k: Kind.Adjective): List<Inflected> {
        val stemText = if (k.ii) (if (w.text.startsWith("良")) "良" else "よ") else w.text.dropLast(1)
        val stemReading = if (k.ii) "よ" else w.reading.dropLast(1)
        fun of(form: Form, tail: String) = Inflected(form, stemText + tail, stemReading + tail)
        return listOf(
            // Plain
            of(Form.NAI, "くない"),
            of(Form.TA, "かった"),
            of(Form.TE, "くて"),
            of(Form.PAST_NEGATIVE, "くなかった"),
            of(Form.BA, "ければ"),
            of(Form.ADVERBIAL, "く"),
            // Polite
            Inflected(Form.POLITE, w.text + "です", w.reading + "です"),
            of(Form.MASEN, "くないです"),
            of(Form.POLITE_NEGATIVE, "くありません"),
            of(Form.MASHITA, "かったです"),
            of(Form.MASENDESHITA, "くありませんでした"),
        )
    }

    private fun naAdjectiveForms(w: Word): List<Inflected> {
        val stem = w.text
        val r = w.reading
        fun of(form: Form, tail: String) = Inflected(form, stem + tail, r + tail)
        return listOf(
            // Plain
            of(Form.PLAIN, "だ"),
            of(Form.ADNOMINAL, "な"),
            of(Form.ADVERBIAL, "に"),
            of(Form.TE, "で"),
            of(Form.TA, "だった"),
            of(Form.NAI, "ではない"),
            of(Form.PAST_NEGATIVE, "ではなかった"),
            of(Form.BA, "なら"),
            // Polite
            of(Form.POLITE, "です"),
            of(Form.MASHITA, "でした"),
            of(Form.MASEN, "ではありません"),
            of(Form.MASENDESHITA, "ではありませんでした"),
        )
    }

    /** Honorific and humble forms across [KEIGO] and productive rules. */
    private fun keigo(w: Word): List<Inflected> {
        KEIGO[w.text]?.let { return it }
        if (w.kind is Kind.Adjective && w.kind.ii) {
            return listOf(
                Inflected(Form.HONORIFIC, "よろしいです", "よろしいです"),
                Inflected(Form.HONORIFIC_PLAIN, "よろしい", "よろしい"),
                Inflected(Form.HONORIFIC_ALT, "よろしければ", "よろしければ"),
                Inflected(Form.HONORIFIC_REQUEST, "結構です", "けっこうです"),
            )
        }
        return when (val k = w.kind) {
            is Kind.Suru -> if (!k.noun) emptyList() else {
                val noun = w.text.removeSuffix("する")
                val nounReading = w.reading.removeSuffix("する")
                val hasPrefix = noun.startsWith("お") || noun.startsWith("ご")
                val prefix = when {
                    hasPrefix -> ""
                    noun in O_SURU_NOUNS || noun.any { it in '\u3040'..'\u309F' } -> "お"
                    noun.length >= 2 -> "ご"
                    else -> ""
                }
                buildList {
                    add(Inflected(Form.HONORIFIC, noun + "なさいます", nounReading + "なさいます"))
                    add(Inflected(Form.HONORIFIC_PASSIVE, noun + "されます", nounReading + "されます"))
                    if (prefix.isNotEmpty() || hasPrefix) {
                        add(Inflected(Form.HONORIFIC_ALT, "$prefix${noun}になります", "$prefix${nounReading}になります"))
                        add(Inflected(Form.HONORIFIC_PLAIN, "$prefix${noun}になる", "$prefix${nounReading}になる"))
                        add(Inflected(Form.HONORIFIC_REQUEST, "$prefix${noun}ください", "$prefix${nounReading}ください"))
                    }
                    add(Inflected(Form.HUMBLE, noun + "いたします", nounReading + "いたします"))
                    if (prefix.isNotEmpty() || hasPrefix) {
                        add(Inflected(Form.HUMBLE_ALT, "$prefix${noun}いたします", "$prefix${nounReading}いたします"))
                        add(Inflected(Form.HUMBLE_PLAIN, "$prefix${noun}します", "$prefix${nounReading}します"))
                    }
                }
            }
            is Kind.Godan, is Kind.Ichidan -> {
                val masu = plainFormsOnly(w.kind, w).firstOrNull { it.form == Form.MASU } ?: return emptyList()
                val passive = plainFormsOnly(w.kind, w).firstOrNull { it.form == Form.PASSIVE }
                val stemText = masu.text.removeSuffix("ます")
                val stemReading = masu.reading.removeSuffix("ます")
                val hasKanji = masu.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
                val oneMoraIchidan = w.kind is Kind.Ichidan && w.reading.removeSuffix("る").length <= 1
                buildList {
                    if (hasKanji && !oneMoraIchidan && w.text !in NO_O_KEIGO_VERBS) {
                        add(Inflected(Form.HONORIFIC, "お${stemText}になります", "お${stemReading}になります"))
                        add(Inflected(Form.HONORIFIC_PLAIN, "お${stemText}になる", "お${stemReading}になる"))
                        add(Inflected(Form.HONORIFIC_PAST, "お${stemText}になりました", "お${stemReading}になりました"))
                        if (w.text !in NO_REQUEST_VERBS) {
                            add(Inflected(Form.HONORIFIC_REQUEST, "お${stemText}ください", "お${stemReading}ください"))
                        }
                        if (passive != null) {
                            val passStemT = passive.text.removeSuffix("る")
                            val passStemR = passive.reading.removeSuffix("る")
                            add(Inflected(Form.HONORIFIC_PASSIVE, "${passStemT}ます", "${passStemR}ます"))
                        }
                        add(Inflected(Form.HUMBLE, "お${stemText}します", "お${stemReading}します"))
                        add(Inflected(Form.HUMBLE_ALT, "お${stemText}いたします", "お${stemReading}いたします"))
                        add(Inflected(Form.HUMBLE_PLAIN, "お${stemText}する", "お${stemReading}する"))
                        add(Inflected(Form.HUMBLE_PAST, "お${stemText}しました", "お${stemReading}しました"))
                    } else if (passive != null && w.text !in NO_O_KEIGO_VERBS) {
                        val passStemT = passive.text.removeSuffix("る")
                        val passStemR = passive.reading.removeSuffix("る")
                        add(Inflected(Form.HONORIFIC_PASSIVE, "${passStemT}ます", "${passStemR}ます"))
                    }
                }
            }
            else -> emptyList()
        }
    }

    /** Plain verb forms alone (no keigo), for deriving the `お…になる` / passive stems without recursion. */
    private fun plainFormsOnly(kind: Kind, w: Word): List<Inflected> = when (kind) {
        is Kind.Godan -> godanForms(w, kind)
        is Kind.Ichidan -> ichidanForms(w, kind)
        else -> emptyList()
    }

    /** 五段 rows by the kana id.def names in the conjugation type. */
    private val ROWS = linkedMapOf(
        "カ行" to 'く', "ガ行" to 'ぐ', "サ行" to 'す', "タ行" to 'つ', "ナ行" to 'ぬ',
        "バ行" to 'ぶ', "マ行" to 'む', "ラ行" to 'る', "ワ行" to 'う',
    )

    private val GODAN_A_TO_ROW = mapOf('か' to 'く', 'が' to 'ぐ', 'さ' to 'す', 'た' to 'つ', 'な' to 'ぬ', 'ば' to 'ぶ', 'ま' to 'む', 'ら' to 'る', 'わ' to 'う')
    private val GODAN_I_TO_ROW = mapOf('き' to 'く', 'ぎ' to 'ぐ', 'し' to 'す', 'ち' to 'つ', 'に' to 'ぬ', 'び' to 'ぶ', 'み' to 'む', 'り' to 'る', 'い' to 'う')
    private val GODAN_E_TO_ROW = mapOf('け' to 'く', 'げ' to 'ぐ', 'せ' to 'す', 'て' to 'つ', 'ね' to 'ぬ', 'べ' to 'ぶ', 'め' to 'む', 'れ' to 'る', 'え' to 'う')
    private val GODAN_O_TO_ROW = mapOf('こ' to 'く', 'ご' to 'ぐ', 'そ' to 'す', 'と' to 'つ', 'の' to 'ぬ', 'ぼ' to 'ぶ', 'も' to 'む', 'ろ' to 'る', 'お' to 'う')

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
        "仮定形" to listOf("すれ"), "命令ｒｏ" to listOf("しろ"), "命令ｙｏ" to listOf("せよ"), "未然ウ接続" to listOf("しよう", "しよ"),
    )

    /** The kana after an adjective's stem per form. */
    private val ADJECTIVE_TAILS = mapOf("基本形" to "い", "連用テ接続" to "く", "連用タ接続" to "かっ", "仮定形" to "けれ", "未然ウ接続" to "かろ", "ガル接続" to "", "体言接続" to "き")

    private val NON_NA_ADJECTIVES = setOf("こんな", "そんな", "あんな", "どんな", "同じ")
    private val NO_POTENTIAL_VERBS = setOf("ある", "有る", "在る", "わかる", "分かる", "できる", "出来る", "見える", "聞こえる")
    private val NO_REQUEST_VERBS = setOf("ある", "有る", "在る", "わかる", "分かる", "できる", "出来る", "要る", "いる", "居る")
    private val NO_O_KEIGO_VERBS = setOf("ある", "有る", "在る")
    private val O_SURU_NOUNS = setOf("電話", "返事", "仕事", "話", "礼", "願い", "祝い", "詫び", "世話", "手伝い", "迎え", "見舞い", "買い物", "掃除", "洗濯")

    private val SENTENCE_PUNCTUATION = setOf('。', '、', '！', '？', '!', '?', '.', '，')
    private val SENTENCE_PARTICLES = setOf("か", "ね", "よ", "よね", "わ", "かな", "な", "ぞ", "ぜ")
    private val ATTACHED_FINAL_PARTICLES = listOf("よね", "かな", "か", "ね", "よ", "わ")

    private val SURU_AUX_SURFACES = setOf("する", "しない", "した", "して", "します", "しません", "しました", "しませんでした", "しましょう", "できる", "できます", "される", "させる", "しよう", "しろ", "いたします")
    private val SURU_COMPOUND_TAILS = listOf("しませんでした", "しましょう", "いたします", "しません", "しました", "します", "した", "して", "しない", "すれば", "できる", "できます", "しよう", "しろ", "する")
    private val MASU_TAILS = listOf("ませんでした", "ましょう", "ません", "ました", "ます")
    private val NA_DEINFLECT_TAILS = listOf("ではありませんでした", "ではありません", "ではなかった", "ではない", "でした", "だった", "です", "なら", "だ", "な", "に", "で")

    /** High-frequency whole-word / irregular inflections when Mozc isn't holding the segment stem separately. */
    private val DEINFLECT_EXACT: Map<String, Triple<String, String, Kind>> = mapOf(
        "行こう" to Triple("行く", "いく", Kind.Godan('く', Special.IKU)),
        "いこう" to Triple("いく", "いく", Kind.Godan('く', Special.IKU)),
        "行って" to Triple("行く", "いく", Kind.Godan('く', Special.IKU)),
        "行った" to Triple("行く", "いく", Kind.Godan('く', Special.IKU)),
        "行きます" to Triple("行く", "いく", Kind.Godan('く', Special.IKU)),
        "行かない" to Triple("行く", "いく", Kind.Godan('く', Special.IKU)),
        "行ける" to Triple("行く", "いく", Kind.Godan('く', Special.IKU)),
        "来よう" to Triple("来る", "くる", Kind.Kuru),
        "来ない" to Triple("来る", "くる", Kind.Kuru),
        "来ます" to Triple("来る", "くる", Kind.Kuru),
        "来て" to Triple("来る", "くる", Kind.Kuru),
        "来た" to Triple("来る", "くる", Kind.Kuru),
        "しよう" to Triple("する", "する", Kind.Suru()),
        "します" to Triple("する", "する", Kind.Suru()),
        "しない" to Triple("する", "する", Kind.Suru()),
        "した" to Triple("する", "する", Kind.Suru()),
        "して" to Triple("する", "する", Kind.Suru()),
        "食べよう" to Triple("食べる", "たべる", Kind.Ichidan()),
        "買いに" to Triple("買う", "かう", Kind.Godan('う')),
        "買おう" to Triple("買う", "かう", Kind.Godan('う')),
    )

    /** Irregular & contextual keigo verbs across honorific and humble registers (distinct [Form] keys per verb). */
    private val KEIGO: Map<String, List<Inflected>> = buildMap {
        fun add(words: List<String>, items: List<Inflected>) = words.forEach { put(it, items) }
        val irassharu = listOf(
            Inflected(Form.HONORIFIC, "いらっしゃいます", "いらっしゃいます"),
            Inflected(Form.HONORIFIC_PLAIN, "いらっしゃる", "いらっしゃる"),
            Inflected(Form.HONORIFIC_PAST, "いらっしゃいました", "いらっしゃいました"),
            Inflected(Form.HONORIFIC_ALT, "おいでになります", "おいでになります"),
        )
        add(
            listOf("行く", "いく"),
            irassharu + listOf(
                Inflected(Form.HONORIFIC_PASSIVE, "行かれます", "いかれます"),
                Inflected(Form.HUMBLE, "参ります", "まいります"),
                Inflected(Form.HUMBLE_PLAIN, "参る", "まいる"),
                Inflected(Form.HUMBLE_PAST, "参りました", "まいりました"),
                Inflected(Form.HUMBLE_ALT, "伺います", "うかがいます"),
            ),
        )
        add(
            listOf("来る", "くる"),
            irassharu + listOf(
                Inflected(Form.HONORIFIC_PASSIVE, "来られます", "こられます"),
                Inflected(Form.HUMBLE, "参ります", "まいります"),
                Inflected(Form.HUMBLE_PLAIN, "参る", "まいる"),
                Inflected(Form.HUMBLE_PAST, "参りました", "まいりました"),
                Inflected(Form.HUMBLE_ALT, "伺います", "うかがいます"),
            ),
        )
        add(
            listOf("いる", "居る"),
            irassharu + listOf(
                Inflected(Form.HUMBLE, "おります", "おります"),
                Inflected(Form.HUMBLE_PLAIN, "おる", "おる"),
                Inflected(Form.HUMBLE_PAST, "おりました", "おりました"),
            ),
        )
        add(
            listOf("言う", "いう"),
            listOf(
                Inflected(Form.HONORIFIC, "おっしゃいます", "おっしゃいます"),
                Inflected(Form.HONORIFIC_PLAIN, "おっしゃる", "おっしゃる"),
                Inflected(Form.HONORIFIC_PAST, "おっしゃいました", "おっしゃいました"),
                Inflected(Form.HUMBLE, "申します", "もうします"),
                Inflected(Form.HUMBLE_PLAIN, "申す", "もうす"),
                Inflected(Form.HUMBLE_ALT, "申し上げます", "もうしあげます"),
                Inflected(Form.HUMBLE_PAST, "申しました", "もうしました"),
            ),
        )
        add(
            listOf("する"),
            listOf(
                Inflected(Form.HONORIFIC, "なさいます", "なさいます"),
                Inflected(Form.HONORIFIC_PLAIN, "なさる", "なさる"),
                Inflected(Form.HONORIFIC_PAST, "なさいました", "なさいました"),
                Inflected(Form.HONORIFIC_PASSIVE, "されます", "されます"),
                Inflected(Form.HUMBLE, "いたします", "いたします"),
                Inflected(Form.HUMBLE_PLAIN, "いたす", "いたす"),
                Inflected(Form.HUMBLE_PAST, "いたしました", "いたしました"),
            ),
        )
        add(
            listOf("食べる", "飲む"),
            listOf(
                Inflected(Form.HONORIFIC, "召し上がります", "めしあがります"),
                Inflected(Form.HONORIFIC_PLAIN, "召し上がる", "めしあがる"),
                Inflected(Form.HONORIFIC_PAST, "召し上がりました", "めしあがりました"),
                Inflected(Form.HUMBLE, "いただきます", "いただきます"),
                Inflected(Form.HUMBLE_PLAIN, "いただく", "いただく"),
                Inflected(Form.HUMBLE_PAST, "いただきました", "いただきました"),
            ),
        )
        add(
            listOf("見る"),
            listOf(
                Inflected(Form.HONORIFIC, "ご覧になります", "ごらんになります"),
                Inflected(Form.HONORIFIC_PLAIN, "ご覧になる", "ごらんになる"),
                Inflected(Form.HONORIFIC_PAST, "ご覧になりました", "ごらんになりました"),
                Inflected(Form.HONORIFIC_REQUEST, "ご覧ください", "ごらんください"),
                Inflected(Form.HUMBLE, "拝見します", "はいけんします"),
                Inflected(Form.HUMBLE_PLAIN, "拝見する", "はいけんする"),
                Inflected(Form.HUMBLE_ALT, "拝見いたします", "はいけんいたします"),
            ),
        )
        add(
            listOf("読む"),
            listOf(
                Inflected(Form.HONORIFIC, "お読みになります", "およみになります"),
                Inflected(Form.HONORIFIC_PLAIN, "お読みになる", "およみになる"),
                Inflected(Form.HONORIFIC_REQUEST, "お読みください", "およみください"),
                Inflected(Form.HUMBLE, "拝読します", "はいどくします"),
                Inflected(Form.HUMBLE_ALT, "拝読いたします", "はいどくいたします"),
                Inflected(Form.HUMBLE_PLAIN, "拝読する", "はいどくする"),
            ),
        )
        add(
            listOf("借りる"),
            listOf(
                Inflected(Form.HONORIFIC, "お借りになります", "おかりになります"),
                Inflected(Form.HUMBLE, "拝借します", "はいしゃくします"),
                Inflected(Form.HUMBLE_ALT, "拝借いたします", "はいしゃくいたします"),
                Inflected(Form.HUMBLE_PLAIN, "お借りします", "おかりします"),
            ),
        )
        add(
            listOf("知る"),
            listOf(
                Inflected(Form.HONORIFIC, "ご存じです", "ごぞんじです"),
                Inflected(Form.HUMBLE, "存じております", "ぞんじております"),
                Inflected(Form.HUMBLE_ALT, "存じ上げております", "ぞんじあげております"),
            ),
        )
        add(
            listOf("わかる", "分かる"),
            listOf(
                Inflected(Form.HONORIFIC, "お分かりになります", "おわかりになります"),
                Inflected(Form.HUMBLE, "承知いたしました", "しょうちいたしました"),
                Inflected(Form.HUMBLE_ALT, "かしこまりました", "かしこまりました"),
                Inflected(Form.HUMBLE_PAST, "承知しました", "しょうちしました"),
            ),
        )
        add(
            listOf("会う"),
            listOf(
                Inflected(Form.HONORIFIC, "お会いになります", "おあいになります"),
                Inflected(Form.HONORIFIC_PLAIN, "お会いになる", "おあいになる"),
                Inflected(Form.HUMBLE, "お目にかかります", "おめにかかります"),
                Inflected(Form.HUMBLE_PLAIN, "お目にかかる", "おめにかかる"),
            ),
        )
        add(
            listOf("聞く"),
            listOf(
                Inflected(Form.HONORIFIC, "お聞きになります", "おききになります"),
                Inflected(Form.HONORIFIC_REQUEST, "お聞きください", "おききください"),
                Inflected(Form.HUMBLE, "伺います", "うかがいます"),
                Inflected(Form.HUMBLE_PLAIN, "伺う", "うかがう"),
                Inflected(Form.HUMBLE_ALT, "拝聴いたします", "はいちょういたします"),
            ),
        )
        add(
            listOf("尋ねる", "訪ねる"),
            listOf(
                Inflected(Form.HONORIFIC, "お訪ねになります", "おたずねになります"),
                Inflected(Form.HUMBLE, "伺います", "うかがいます"),
                Inflected(Form.HUMBLE_PLAIN, "伺う", "うかがう"),
                Inflected(Form.HUMBLE_PAST, "伺いました", "うかがいました"),
            ),
        )
        add(
            listOf("思う"),
            listOf(
                Inflected(Form.HONORIFIC, "お思いになります", "おおもいになります"),
                Inflected(Form.HONORIFIC_PASSIVE, "思われます", "おもわれます"),
                Inflected(Form.HUMBLE, "存じます", "ぞんじます"),
                Inflected(Form.HUMBLE_PLAIN, "存じる", "ぞんじる"),
                Inflected(Form.HUMBLE_ALT, "存じ上げます", "ぞんじあげます"),
            ),
        )
        add(
            listOf("あげる", "上げる", "やる"),
            listOf(
                Inflected(Form.HUMBLE, "差し上げます", "さしあげます"),
                Inflected(Form.HUMBLE_PLAIN, "差し上げる", "さしあげる"),
                Inflected(Form.HUMBLE_PAST, "差し上げました", "さしあげました"),
            ),
        )
        add(
            listOf("もらう", "貰う", "受ける"),
            listOf(
                Inflected(Form.HUMBLE, "いただきます", "いただきます"),
                Inflected(Form.HUMBLE_PLAIN, "いただく", "いただく"),
                Inflected(Form.HUMBLE_ALT, "頂戴いたします", "ちょうだいいたします"),
                Inflected(Form.HUMBLE_PAST, "いただきました", "いただきました"),
            ),
        )
        add(
            listOf("くれる", "呉れる"),
            listOf(
                Inflected(Form.HONORIFIC, "くださいます", "くださいます"),
                Inflected(Form.HONORIFIC_PLAIN, "くださる", "くださる"),
                Inflected(Form.HONORIFIC_PAST, "くださいました", "くださいました"),
            ),
        )
        add(
            listOf("寝る"),
            listOf(
                Inflected(Form.HONORIFIC, "お休みになります", "おやすみになります"),
                Inflected(Form.HONORIFIC_PLAIN, "お休みになる", "おやすみになる"),
                Inflected(Form.HONORIFIC_PAST, "お休みになりました", "おやすみになりました"),
            ),
        )
        add(
            listOf("着る"),
            listOf(
                Inflected(Form.HONORIFIC, "お召しになります", "おめしになります"),
                Inflected(Form.HONORIFIC_PLAIN, "お召しになる", "おめしになる"),
            ),
        )
        add(
            listOf("死ぬ", "しぬ"),
            listOf(
                Inflected(Form.HONORIFIC, "お亡くなりになります", "おなくなりになります"),
                Inflected(Form.HONORIFIC_PLAIN, "お亡くなりになる", "おなくなりになる"),
                Inflected(Form.HONORIFIC_PAST, "お亡くなりになりました", "おなくなりになりました"),
            ),
        )
    }

    /** Curated keigo nouns, pronouns, adverbs, and high-frequency polite expressions. */
    private val LEXICAL_BY_KEY: Map<String, Word> = buildMap {
        fun add(keys: List<String>, baseText: String, baseReading: String, cat: LexicalCategory, vararg entries: Inflected) {
            val word = Word(baseText, baseReading, Kind.Lexical(cat, entries.toList()))
            keys.forEach { put(it, word) }
        }
        fun beautifiedNoun(noun: String, reading: String, prefix: String) {
            add(
                listOf(noun, reading, "$prefix$noun", "$prefix$reading"),
                noun,
                reading,
                LexicalCategory.NOUN,
                Inflected(Form.PLAIN, noun, reading),
                Inflected(Form.POLITE, "$prefix$noun", "$prefix$reading"),
                Inflected(Form.HONORIFIC, "${prefix}${noun}です", "${prefix}${reading}です"),
                Inflected(Form.HONORIFIC_REQUEST, "${prefix}${noun}ください", "${prefix}${reading}ください"),
            )
        }

        // Nouns & Pronouns
        add(
            listOf("会社", "かいしゃ", "御社", "おんしゃ", "貴社", "きしゃ", "弊社", "へいしゃ"),
            "会社", "かいしゃ", LexicalCategory.NOUN,
            Inflected(Form.PLAIN, "会社", "かいしゃ"),
            Inflected(Form.POLITE, "会社です", "かいしゃです"),
            Inflected(Form.HONORIFIC, "御社", "おんしゃ"),
            Inflected(Form.HONORIFIC_ALT, "貴社", "きしゃ"),
            Inflected(Form.HUMBLE, "弊社", "へいしゃ"),
            Inflected(Form.HUMBLE_ALT, "当社", "とうしゃ"),
        )
        add(
            listOf("人", "ひと", "方", "かた"),
            "人", "ひと", LexicalCategory.NOUN,
            Inflected(Form.PLAIN, "人", "ひと"),
            Inflected(Form.POLITE, "方", "かた"),
            Inflected(Form.HONORIFIC, "方々", "かたがた"),
            Inflected(Form.HONORIFIC_ALT, "皆様", "みなさま"),
        )
        add(
            listOf("誰", "だれ", "どなた"),
            "誰", "だれ", LexicalCategory.PRONOUN,
            Inflected(Form.PLAIN, "誰", "だれ"),
            Inflected(Form.POLITE, "どなたですか", "どなたですか"),
            Inflected(Form.HONORIFIC, "どなた", "どなた"),
            Inflected(Form.HONORIFIC_ALT, "どなた様", "どなたさま"),
        )
        add(
            listOf("私", "わたし", "僕", "ぼく", "俺", "おれ", "自分", "じぶん"),
            "私", "わたし", LexicalCategory.PRONOUN,
            Inflected(Form.PLAIN, "私", "わたし"),
            Inflected(Form.TA, "僕", "ぼく"),
            Inflected(Form.POLITE, "私です", "わたしです"),
            Inflected(Form.HUMBLE, "わたくし", "わたくし"),
            Inflected(Form.HUMBLE_ALT, "私ども", "わたくしども"),
        )
        add(
            listOf("みんな", "皆", "みな", "皆さん", "みなさん", "皆様", "みなさま"),
            "みんな", "みんな", LexicalCategory.PRONOUN,
            Inflected(Form.PLAIN, "みんな", "みんな"),
            Inflected(Form.POLITE, "皆さん", "みなさん"),
            Inflected(Form.HONORIFIC, "皆様", "みなさま"),
            Inflected(Form.HONORIFIC_ALT, "皆様方", "みなさまがた"),
        )
        add(
            listOf("名前", "なまえ", "お名前", "おなまえ", "ご芳名", "ごほうめい"),
            "名前", "なまえ", LexicalCategory.NOUN,
            Inflected(Form.PLAIN, "名前", "なまえ"),
            Inflected(Form.POLITE, "お名前", "おなまえ"),
            Inflected(Form.HONORIFIC, "お名前様", "おなまえさま"),
            Inflected(Form.HONORIFIC_ALT, "ご芳名", "ごほうめい"),
        )

        // Time, place & adverbials
        add(
            listOf("今日", "きょう", "本日", "ほんじつ"),
            "今日", "きょう", LexicalCategory.ADVERB,
            Inflected(Form.PLAIN, "今日", "きょう"),
            Inflected(Form.POLITE, "本日", "ほんじつ"),
        )
        add(
            listOf("明日", "あした", "あす", "みょうにち"),
            "明日", "あした", LexicalCategory.ADVERB,
            Inflected(Form.PLAIN, "明日", "あした"),
            Inflected(Form.POLITE, "みょうにち", "みょうにち"),
            Inflected(Form.MASU, "翌日", "よくじつ"),
        )
        add(
            listOf("今", "いま", "ただいま"),
            "今", "いま", LexicalCategory.ADVERB,
            Inflected(Form.PLAIN, "今", "いま"),
            Inflected(Form.POLITE, "現在", "げんざい"),
            Inflected(Form.HUMBLE, "ただいま", "ただいま"),
        )
        add(
            listOf("さっき", "先ほど", "さきほど"),
            "さっき", "さっき", LexicalCategory.ADVERB,
            Inflected(Form.PLAIN, "さっき", "さっき"),
            Inflected(Form.POLITE, "先ほど", "さきほど"),
        )
        add(
            listOf("後で", "あとで", "後ほど", "のちほど"),
            "後で", "あとで", LexicalCategory.ADVERB,
            Inflected(Form.PLAIN, "後で", "あとで"),
            Inflected(Form.POLITE, "のちほど", "のちほど"),
            Inflected(Form.MASU, "後ほど", "のちほど"),
        )
        add(listOf("ここ"), "ここ", "ここ", LexicalCategory.PRONOUN, Inflected(Form.PLAIN, "ここ", "ここ"), Inflected(Form.POLITE, "こちら", "こちら"))
        add(listOf("そこ"), "そこ", "そこ", LexicalCategory.PRONOUN, Inflected(Form.PLAIN, "そこ", "そこ"), Inflected(Form.POLITE, "そちら", "そちら"))
        add(listOf("あそこ"), "あそこ", "あそこ", LexicalCategory.PRONOUN, Inflected(Form.PLAIN, "あそこ", "あそこ"), Inflected(Form.POLITE, "あちら", "あちら"))
        add(listOf("どこ", "どちら"), "どこ", "どこ", LexicalCategory.PRONOUN, Inflected(Form.PLAIN, "どこ", "どこ"), Inflected(Form.POLITE, "どちら", "どちら"))
        add(
            listOf("少し", "すこし", "ちょっと", "少々", "しょうしょう"),
            "少し", "すこし", LexicalCategory.ADVERB,
            Inflected(Form.PLAIN, "少し", "すこし"),
            Inflected(Form.TA, "ちょっと", "ちょっと"),
            Inflected(Form.POLITE, "少々", "しょうしょう"),
        )
        add(
            listOf("とても", "すごく", "大変", "たいへん", "誠に", "まことに"),
            "とても", "とても", LexicalCategory.ADVERB,
            Inflected(Form.PLAIN, "とても", "とても"),
            Inflected(Form.POLITE, "大変", "たいへん"),
            Inflected(Form.HONORIFIC, "誠に", "まことに"),
            Inflected(Form.HUMBLE, "非常に", "ひじょうに"),
        )
        add(
            listOf("どう", "いかが"),
            "どう", "どう", LexicalCategory.ADVERB,
            Inflected(Form.PLAIN, "どう", "どう"),
            Inflected(Form.POLITE, "いかがですか", "いかがですか"),
            Inflected(Form.HONORIFIC, "いかが", "いかが"),
            Inflected(Form.HONORIFIC_ALT, "いかがでしょうか", "いかがでしょうか"),
        )

        // Common beautified nouns (お / ご)
        beautifiedNoun("電話", "でんわ", "お")
        beautifiedNoun("時間", "じかん", "お")
        beautifiedNoun("返事", "へんじ", "お")
        beautifiedNoun("仕事", "しごと", "お")
        beautifiedNoun("話", "はなし", "お")
        beautifiedNoun("手紙", "てがみ", "お")
        beautifiedNoun("願い", "ねがい", "お")
        beautifiedNoun("連絡", "れんらく", "ご")
        beautifiedNoun("都合", "つごう", "ご")
        beautifiedNoun("予定", "よてい", "ご")
        beautifiedNoun("質問", "しつもん", "ご")
        beautifiedNoun("意見", "いけん", "ご")
        beautifiedNoun("回答", "かいとう", "ご")
        beautifiedNoun("確認", "かくにん", "ご")
        beautifiedNoun("案内", "あんない", "ご")
        beautifiedNoun("相談", "そうだん", "ご")
        beautifiedNoun("家族", "かぞく", "ご")

        // Curated polite expressions
        add(
            listOf("ありがとう", "ありがとうございます", "ありがとうございました", "有難う", "有り難うございます"),
            "ありがとう", "ありがとう", LexicalCategory.EXPRESSION,
            Inflected(Form.PLAIN, "ありがとう", "ありがとう"),
            Inflected(Form.POLITE, "ありがとうございます", "ありがとうございます"),
            Inflected(Form.MASHITA, "ありがとうございました", "ありがとうございました"),
            Inflected(Form.HONORIFIC, "誠にありがとうございます", "まことにありがとうございます"),
            Inflected(Form.HUMBLE, "感謝申し上げます", "かんしゃもうしあげます"),
            Inflected(Form.HUMBLE_ALT, "厚く御礼申し上げます", "あつくおんれいもうしあげます"),
        )
        add(
            listOf("すみません", "すいません", "ごめん", "ごめんなさい", "申し訳ありません", "申し訳ございません", "失礼します"),
            "すみません", "すみません", LexicalCategory.EXPRESSION,
            Inflected(Form.PLAIN, "ごめん", "ごめん"),
            Inflected(Form.TA, "ごめんなさい", "ごめんなさい"),
            Inflected(Form.POLITE, "すみません", "すみません"),
            Inflected(Form.MASU, "失礼します", "しつれいします"),
            Inflected(Form.MASHITA, "失礼しました", "しつれいしました"),
            Inflected(Form.HUMBLE, "申し訳ございません", "もうしわけございません"),
            Inflected(Form.HUMBLE_ALT, "申し訳ありません", "もうしわけありません"),
            Inflected(Form.HUMBLE_PLAIN, "恐れ入ります", "おそれいります"),
        )
        add(
            listOf("お願い", "おねがい", "お願いします", "おねがいします", "お願いいたします"),
            "お願いします", "おねがいします", LexicalCategory.EXPRESSION,
            Inflected(Form.PLAIN, "お願い", "おねがい"),
            Inflected(Form.POLITE, "お願いします", "おねがいします"),
            Inflected(Form.HONORIFIC, "お願いいたします", "おねがいいたします"),
            Inflected(Form.HONORIFIC_ALT, "よろしくお願いいたします", "よろしくおねがいいたします"),
            Inflected(Form.HUMBLE, "お願い申し上げます", "おねがいもうしあげます"),
            Inflected(Form.HUMBLE_ALT, "何卒よろしくお願い申し上げます", "なにとぞよろしくおねがいもうしあげます"),
        )
        add(
            listOf("よろしく", "よろしくお願いします", "よろしくお願いいたします"),
            "よろしく", "よろしく", LexicalCategory.EXPRESSION,
            Inflected(Form.PLAIN, "よろしく", "よろしく"),
            Inflected(Form.POLITE, "よろしくお願いします", "よろしくおねがいします"),
            Inflected(Form.HONORIFIC, "よろしくお願いいたします", "よろしくおねがいいたします"),
            Inflected(Form.HUMBLE, "何卒よろしくお願い申し上げます", "なにとぞよろしくおねがいもうしあげます"),
        )
        add(
            listOf("わかった", "分かった", "わかりました", "分かりました", "了解", "りょうかい", "承知しました", "承知いたしました", "かしこまりました"),
            "わかった", "わかった", LexicalCategory.EXPRESSION,
            Inflected(Form.PLAIN, "わかった", "わかった"),
            Inflected(Form.TA, "了解", "りょうかい"),
            Inflected(Form.POLITE, "わかりました", "わかりました"),
            Inflected(Form.MASU, "了解しました", "りょうかいしました"),
            Inflected(Form.HUMBLE, "承知いたしました", "しょうちいたしました"),
            Inflected(Form.HUMBLE_ALT, "かしこまりました", "かしこまりました"),
            Inflected(Form.HUMBLE_PAST, "承知しました", "しょうちしました"),
        )
        add(
            listOf("大丈夫", "だいじょうぶ", "大丈夫です", "問題ありません"),
            "大丈夫", "だいじょうぶ", LexicalCategory.EXPRESSION,
            Inflected(Form.POLITE_NEGATIVE, "問題ありません", "もんだいありません"),
            Inflected(Form.HONORIFIC, "結構です", "けっこうです"),
            Inflected(Form.HONORIFIC_ALT, "差し支えありません", "さしつかえありません"),
            Inflected(Form.HUMBLE, "問題ございません", "もんだいございません"),
            Inflected(Form.HUMBLE_ALT, "差し支えございません", "さしつかえございません"),
        )
        add(
            listOf("待って", "まって", "ちょっと待って", "ちょっとまって", "待ってください"),
            "待って", "まって", LexicalCategory.EXPRESSION,
            Inflected(Form.PLAIN, "待って", "まって"),
            Inflected(Form.TA, "ちょっと待って", "ちょっとまって"),
            Inflected(Form.POLITE, "待ってください", "まってください"),
            Inflected(Form.TE_KUDASAI, "少し待ってください", "すこしまってください"),
            Inflected(Form.HONORIFIC_REQUEST, "お待ちください", "おまちください"),
            Inflected(Form.HONORIFIC, "少々お待ちください", "しょうしょうおまちください"),
            Inflected(Form.HUMBLE, "少々お待ちくださいませ", "しょうしょうおまちくださいませ"),
        )
        add(
            listOf("お疲れ", "おつかれ", "お疲れ様", "おつかれさま", "お疲れ様です", "お疲れ様でした"),
            "お疲れ様", "おつかれさま", LexicalCategory.EXPRESSION,
            Inflected(Form.PLAIN, "お疲れ", "おつかれ"),
            Inflected(Form.POLITE, "お疲れ様です", "おつかれさまです"),
            Inflected(Form.MASHITA, "お疲れ様でした", "おつかれさまでした"),
            Inflected(Form.HONORIFIC, "大変お疲れ様でございます", "たいへんおつかれさまでございます"),
        )
        add(
            listOf("おはよう", "おはようございます"),
            "おはよう", "おはよう", LexicalCategory.EXPRESSION,
            Inflected(Form.PLAIN, "おはよう", "おはよう"),
            Inflected(Form.POLITE, "おはようございます", "おはようございます"),
        )
        add(
            listOf("おやすみ", "おやすみなさい"),
            "おやすみ", "おやすみ", LexicalCategory.EXPRESSION,
            Inflected(Form.PLAIN, "おやすみ", "おやすみ"),
            Inflected(Form.POLITE, "おやすみなさい", "おやすみなさい"),
            Inflected(Form.HONORIFIC, "お休みなさいませ", "おやすみなさいませ"),
        )
        add(
            listOf("どういたしまして", "いいえ", "とんでもございません"),
            "どういたしまして", "どういたしまして", LexicalCategory.EXPRESSION,
            Inflected(Form.PLAIN, "いいえ", "いいえ"),
            Inflected(Form.POLITE, "どういたしまして", "どういたしまして"),
            Inflected(Form.HUMBLE, "とんでもございません", "とんでもございません"),
            Inflected(Form.HUMBLE_ALT, "恐れ入ります", "おそれいります"),
        )
    }

    /** Full-width katakana to half-width katakana mapping (`ｱ`–`ﾝ`, voiced marks, small kana). */
    private val HALF_KATA_MAP: Map<Char, String> = mapOf(
        'ア' to "ｱ", 'イ' to "ｲ", 'ウ' to "ｳ", 'エ' to "ｴ", 'オ' to "ｵ",
        'ァ' to "ｧ", 'ィ' to "ｨ", 'ゥ' to "ｩ", 'ェ' to "ｪ", 'ォ' to "ｫ",
        'カ' to "ｶ", 'キ' to "ｷ", 'ク' to "ｸ", 'ケ' to "ｹ", 'コ' to "ｺ",
        'ガ' to "ｶﾞ", 'ギ' to "ｷﾞ", 'グ' to "ｸﾞ", 'ゲ' to "ｹﾞ", 'ゴ' to "ｺﾞ",
        'サ' to "ｻ", 'シ' to "ｼ", 'ス' to "ｽ", 'セ' to "ｾ", 'ソ' to "ｿ",
        'ザ' to "ｻﾞ", 'ジ' to "ｼﾞ", 'ズ' to "ｽﾞ", 'ゼ' to "ｾﾞ", 'ゾ' to "ｿﾞ",
        'タ' to "ﾀ", 'チ' to "ﾁ", 'ツ' to "ﾂ", 'テ' to "ﾃ", 'ト' to "ﾄ", 'ッ' to "ｯ",
        'ダ' to "ﾀﾞ", 'ヂ' to "ﾁﾞ", 'ヅ' to "ﾂﾞ", 'デ' to "ﾃﾞ", 'ド' to "ﾄﾞ",
        'ナ' to "ﾅ", 'ニ' to "ﾆ", 'ヌ' to "ﾇ", 'ネ' to "ﾈ", 'ノ' to "ﾉ",
        'ハ' to "ﾊ", 'ヒ' to "ﾋ", 'フ' to "ﾌ", 'ヘ' to "ﾍ", 'ホ' to "ﾎ",
        'バ' to "ﾊﾞ", 'ビ' to "ﾋﾞ", 'ブ' to "ﾌﾞ", 'ベ' to "ﾍﾞ", 'ボ' to "ﾎﾞ",
        'パ' to "ﾊﾟ", 'ピ' to "ﾋﾟ", 'プ' to "ﾌﾟ", 'ペ' to "ﾍﾟ", 'ポ' to "ﾎﾟ",
        'マ' to "ﾏ", 'ミ' to "ﾐ", 'ム' to "ﾑ", 'メ' to "ﾒ", 'モ' to "ﾓ",
        'ヤ' to "ﾔ", 'ユ' to "ﾕ", 'ヨ' to "ﾖ", 'ャ' to "ｬ", 'ュ' to "ｭ", 'ョ' to "ｮ",
        'ラ' to "ﾗ", 'リ' to "ﾘ", 'ル' to "ﾙ", 'レ' to "ﾚ", 'ロ' to "ﾛ",
        'ワ' to "ﾜ", 'ヲ' to "ｦ", 'ン' to "ﾝ", 'ヴ' to "ｳﾞ",
        'ー' to "ｰ", '。' to "｡", '、' to "､", '「' to "｢", '」' to "｣", '・' to "･",
    )
}
