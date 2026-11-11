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
 * Turns the enabled fuzzy-pinyin rules into the librime rule file `kotomo_fuzzy.yaml` that
 * Kotomo's schema patch includes when it exists (`core/rime/data/luna_pinyin_simp.custom.yaml`),
 * as pure functions so the same text is unit-tested and fed to the host librime.
 *
 * Notes: every rule works both ways and marks what it derives as fuzzy, so librime halves a fuzzy
 * spelling's weight and an exact spelling wins between words of similar frequency. Two rules that
 * share a letter chain in librime whatever their order (with n/l and l/r on, `ri` also offers
 * n-words: 你 for `ri`); corpus line T08-N21 records it as a known gap (doc/en-us/upstream.md).
 * The two abbreviation rules are luna_pinyin's own, applied again after the fuzzy ones so that
 * initials work for fuzzy spellings too, as upstream's fuzzy-pinyin template does.
 */
object Fuzzy {
    /** The file name librime looks up in its user directory (`kotomo_fuzzy:/patch?`). */
    const val FILE = "kotomo_fuzzy.yaml"

    /**
     * Writes the rule file for [rules].
     * Returns: the file's text, or null when no rule is on (the file is then removed and the
     * prebuilt data applies unchanged).
     * Notes: deterministic (the enum's order, not the set's), so its [hash] identifies a rule set.
     */
    fun yaml(rules: Set<FuzzyRule>): String? {
        if (rules.isEmpty()) return null
        val ordered = FuzzyRule.entries.filter { it in rules }
        return buildString {
            append("# Kotomo: fuzzy pinyin, generated from the settings (chinese.fuzzy.*); rewritten on every change.\n")
            append("# Rules: ").append(ordered.joinToString(" ") { it.key }).append('\n')
            append("patch:\n")
            append("  speller/algebra/+:\n")
            ordered.flatMap { listOf(it.forward, it.backward) }.forEach { (from, to) ->
                append("    - ").append(quote("fuzz/$from/$to/")).append('\n')
            }
            ABBREVIATIONS.forEach { append("    - ").append(quote(it)).append('\n') }
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

    /** luna_pinyin's abbreviation rules (rime-luna-pinyin `pinyin.yaml`, `abbreviation`). */
    private val ABBREVIATIONS = listOf("abbrev/^([a-z]).+$/$1/", "abbrev/^([zcs]h).+$/$1/")
}
