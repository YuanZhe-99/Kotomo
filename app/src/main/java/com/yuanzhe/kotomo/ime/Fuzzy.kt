package com.yuanzhe.kotomo.ime

import java.security.MessageDigest

/**
 * Names one Chinese fuzzy-pinyin rule (PLAN §9.6): a pair of initials or finals that the person
 * lets stand for each other, each with its own switch (`chinese.fuzzy.<key>`, off by default).
 *
 * Notes: [key] is stored in the settings file, so renaming it is a format change. [forward] and
 * [backward] are librime `fuzz` patterns (Boost regular expressions over one syllable's spelling):
 * the first turns the left spelling into the right one, the second the right into the left.
 */
enum class FuzzyRule(val key: String, val forward: Pair<String, String>, val backward: Pair<String, String>) {
    Z_ZH("z_zh", "^z(?!h)" to "zh", "^zh" to "z"),
    C_CH("c_ch", "^c(?!h)" to "ch", "^ch" to "c"),
    S_SH("s_sh", "^s(?!h)" to "sh", "^sh" to "s"),
    N_L("n_l", "^n" to "l", "^l" to "n"),
    F_H("f_h", "^f" to "h", "^h" to "f"),
    L_R("l_r", "^l" to "r", "^r" to "l"),
    // Finals: an/ang leaves the -ian and -uan finals to their own rules.
    AN_ANG("an_ang", "(?<![iu])an$" to "ang", "(?<![iu])ang$" to "an"),
    EN_ENG("en_eng", "en$" to "eng", "eng$" to "en"),
    IN_ING("in_ing", "in$" to "ing", "ing$" to "in"),
    IAN_IANG("ian_iang", "ian$" to "iang", "iang$" to "ian"),
    UAN_UANG("uan_uang", "uan$" to "uang", "uang$" to "uan"),
}

/**
 * Names one double-pinyin (双拼) scheme (PLAN §10.4): two keys per syllable, an initial key and a
 * final key, as librime spelling rules applied after the fuzzy ones. [key] is stored
 * (`chinese.double_pinyin`); [semicolon] marks the schemes whose ing is the `;` key.
 *
 * Notes: the key layouts are the schemes' published ones, checked against the rime-double-pinyin
 * schemas (LGPL-3.0; Microsoft, Xiaohe, Ziranma) and rime-ice's Sogou schema (GPL-3.0), whose rule
 * order is followed (doc/en-us/third-party.md). Each rule writes capitals or the semicolon, which no
 * later lowercase pattern matches, and the last transliteration lowers them.
 */
enum class DoublePinyin(val key: String, val semicolon: Boolean, val rules: List<String>) {
    MICROSOFT("microsoft", true, msRules(sogou = false)),
    SOGOU("sogou", true, msRules(sogou = true)),
    XIAOHE(
        "xiaohe", false,
        listOf(
            "erase/^xx$/", "derive/^([jqxy])u$/$1v/", "derive/^([aoe])([ioun])$/$1$1$2/", "xform/^([aoe])(ng)?$/$1$1$2/",
            "xform/iu$/Q/", "xform/(.)ei$/$1W/", "xform/uan$/R/", "xform/[uv]e$/T/", "xform/un$/Y/",
            "xform/^sh/U/", "xform/^ch/I/", "xform/^zh/V/", "xform/uo$/O/", "xform/ie$/P/", "xform/i?ong$/S/",
            "xform/ing$|uai$/K/", "xform/(.)ai$/$1D/", "xform/(.)en$/$1F/", "xform/(.)eng$/$1G/", "xform/[iu]ang$/L/",
            "xform/(.)ang$/$1H/", "xform/ian$/M/", "xform/(.)an$/$1J/", "xform/(.)ou$/$1Z/", "xform/[iu]a$/X/",
            "xform/iao$/N/", "xform/(.)ao$/$1C/", "xform/ui$/V/", "xform/in$/B/",
            "xlit/QWRTYUIOPSDFGHJKLZXCVBNM/qwrtyuiopsdfghjklzxcvbnm/",
        ),
    ),
    ZIRANMA(
        "ziranma", false,
        listOf(
            "erase/^xx$/", "derive/^([jqxy])u$/$1v/", "derive/^([aoe])([ioun])$/$1$1$2/", "xform/^([aoe])(ng)?$/$1$1$2/",
            "xform/iu$/Q/", "xform/[iu]a$/W/", "xform/[uv]an$/R/", "xform/[uv]e$/T/", "xform/ing$|uai$/Y/",
            "xform/^sh/U/", "xform/^ch/I/", "xform/^zh/V/", "xform/uo$/O/", "xform/[uv]n$/P/", "xform/i?ong$/S/",
            "xform/[iu]ang$/D/", "xform/(.)en$/$1F/", "xform/(.)eng$/$1G/", "xform/(.)ang$/$1H/", "xform/ian$/M/",
            "xform/(.)an$/$1J/", "xform/iao$/C/", "xform/(.)ao$/$1K/", "xform/(.)ai$/$1L/", "xform/(.)ei$/$1Z/",
            "xform/ie$/X/", "xform/ui$/V/", "xform/(.)ou$/$1B/", "xform/in$/N/",
            "xlit/QWRTYUIOPSDFGHMJCKLZXVBN/qwrtyuiopsdfghmjcklzxvbn/",
        ),
    ),
}

/**
 * The Microsoft scheme's rules, and Sogou's, which differ in two: Sogou puts ong only after an
 * initial and does not also put üe on v.
 */
private fun msRules(sogou: Boolean) = listOfNotNull(
    "erase/^xx$/", "derive/^([jqxy])u$/$1v/", "derive/^([aoe].*)$/o$1/", "xform/^([ae])(.*)$/$1$1$2/",
    "xform/iu$/Q/", "xform/[iu]a$/W/", "xform/er$|[uv]an$/R/", "xform/[uv]e$/T/", "xform/v$|uai$/Y/",
    "xform/^sh/U/", "xform/^ch/I/", "xform/^zh/V/", "xform/uo$/O/", "xform/[uv]n$/P/",
    if (sogou) "xform/(.)i?ong$/$1S/" else "xform/i?ong$/S/",
    "xform/[iu]ang$/D/", "xform/(.)en$/$1F/", "xform/(.)eng$/$1G/", "xform/(.)ang$/$1H/", "xform/ian$/M/",
    "xform/(.)an$/$1J/", "xform/iao$/C/", "xform/(.)ao$/$1K/", "xform/(.)ai$/$1L/", "xform/(.)ei$/$1Z/",
    "xform/ie$/X/", "xform/ui$/V/", if (sogou) null else "derive/T$/V/", "xform/(.)ou$/$1B/", "xform/in$/N/", "xform/ing$/;/",
    "xlit/QWRTYUIOPSDFGHMJCKLZXVBN/qwrtyuiopsdfghmjcklzxvbn/",
)

/**
 * Turns the enabled fuzzy-pinyin rules into the librime rule file `kotomo_fuzzy.yaml` that
 * Kotomo's schema patch includes when it exists (`core/rime/data/luna_pinyin_simp.custom.yaml`),
 * as pure functions so the same text is unit-tested and fed to the host librime.
 *
 * Notes: every rule works both ways and marks what it derives as fuzzy, so librime halves a fuzzy
 * spelling's weight and an exact spelling wins between words of similar frequency. Rules never
 * chain (PLAN §2.5 decision 16: with n/l and l/r on, l stands for n and r, but n never for r):
 * librime applies each algebra rule to every spelling the earlier rules produced, together with
 * all the syllables that spelling already stands for, so each rule writes what it derives in
 * capitals, which no later rule's lowercase pattern matches, and one transliteration after all of
 * them lowers the capitals again (corpus T08-N21, T08-N22). An initial rule and a final rule still
 * combine on one syllable (`lang` for `nan` with n/l and an/ang on): they change different letters.
 * The two abbreviation rules are luna_pinyin's own, applied again after the fuzzy ones so that
 * initials work for fuzzy spellings too, as upstream's fuzzy-pinyin template does.
 */
object Fuzzy {
    /** The file name librime looks up in its user directory (`kotomo_fuzzy:/patch?`). */
    const val FILE = "kotomo_fuzzy.yaml"

    /**
     * Writes the rule file for [rules], the [double] pinyin scheme (null: full pinyin) and
     * [correction], librime's key correction (`chinese.key_correction`, feedback B2: a key
     * taken for its neighbour, `nohao` → 你好; the bridge lists such readings after the exact ones).
     * Returns: the file's text, or null when no rule is on, no scheme chosen and the correction
     * off (the file is then removed and the prebuilt data applies unchanged).
     * Notes: deterministic (the enum's order, not the set's), so its [hash] identifies a rule set.
     */
    fun yaml(rules: Set<FuzzyRule>, double: DoublePinyin? = null, correction: Boolean = false): String? {
        if (rules.isEmpty() && double == null && !correction) return null
        val ordered = FuzzyRule.entries.filter { it in rules }
        return buildString {
            append("# Kotomo: fuzzy and double pinyin, generated from the settings (chinese.fuzzy.*, chinese.double_pinyin); rewritten on every change.\n")
            append("# Rules: ").append(ordered.joinToString(" ") { it.key }).append('\n')
            if (double != null) append("# Double pinyin: ").append(double.key).append('\n')
            if (correction) append("# Key correction\n")
            append("patch:\n")
            if (correction) append("  translator/enable_correction: true\n")
            if (ordered.isEmpty() && double == null) return@buildString
            // The semicolon is a final key in two schemes, so the speller takes it.
            if (double?.semicolon == true) append("  speller/alphabet: ").append(quote("zyxwvutsrqponmlkjihgfedcba;")).append('\n')
            append("  speller/algebra/+:\n")
            if (ordered.isNotEmpty()) {
                ordered.flatMap { listOf(it.forward, it.backward) }.forEach { (from, to) ->
                    append("    - ").append(quote("fuzz/$from/${to.uppercase()}/")).append('\n')
                }
                append("    - ").append(quote(LOWER)).append('\n')
                ABBREVIATIONS.forEach { append("    - ").append(quote(it)).append('\n') }
            }
            // Double pinyin rewrites every full spelling (the fuzzy ones too) into its two keys.
            double?.rules?.forEach { append("    - ").append(quote(it)).append('\n') }
        }
    }

    /** Identifies a rule file's text (SHA-256, lowercase hex), or "none" for no file. */
    fun hash(text: String?): String = if (text == null) {
        NONE
    } else {
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** The [hash] of "no rule file". */
    const val NONE = "none"

    /** Quotes [rule] as a YAML double-quoted scalar (the rules hold no backslash or quote). */
    private fun quote(rule: String): String {
        require('"' !in rule && '\\' !in rule) { "unquotable rule $rule" }
        return "\"$rule\""
    }

    /** Lowers the capitals the fuzzy rules derive (librime `xlit`, one letter for one letter). */
    private const val LOWER = "xlit/ABCDEFGHIJKLMNOPQRSTUVWXYZ/abcdefghijklmnopqrstuvwxyz/"

    /** luna_pinyin's abbreviation rules (rime-luna-pinyin `pinyin.yaml`, `abbreviation`). */
    private val ABBREVIATIONS = listOf("abbrev/^([a-z]).+$/$1/", "abbrev/^([zcs]h).+$/$1/")
}
