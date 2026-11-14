package com.yuanzhe.kotomo.ime

/**
 * Names one Japanese fuzzy rule (feedback B4): a reading a person who mistypes or mishears a word
 * may have meant. [key] is stored (`japanese.fuzzy.<key>`); [bit] is the rule's bit in the
 * coordinator's options request (doc/en-us/engine-contract.md).
 *
 * Notes: every rule has its own switch and all are off by default. A rule never implies another
 * that shares a kana (は/ば is [VOICED], は/ぱ is [SEMI_VOICED]). The coordinator applies them in
 * Japanese mode and in Mixed mode alike.
 */
enum class JapaneseFuzzyRule(val key: String, val bit: Int) {
    /** A long vowel left out or typed too much: `tokyo` → 東京. */
    LONG_VOWEL("long_vowel", 1 shl 0),
    /** The small っ left out or typed too much: `kite` → 切手. */
    SOKUON("sokuon", 1 shl 1),
    /** ん before a vowel, や行 or な行: `konichiha` → こんにちは. */
    MORAIC_N("moraic_n", 1 shl 2),
    /** か/が, さ/ざ, た/だ, は/ば: `wadashi` → 私. */
    VOICED("voiced", 1 shl 3),
    /** は/ぱ, ば/ぱ. */
    SEMI_VOICED("semi_voiced", 1 shl 4),
    /** A small or a full や, ゆ, よ: `kiyou` → 今日. */
    YOON("yoon", 1 shl 5),
    /** じ/ぢ, ず/づ: `tsuzuku` → 続く. */
    YOTSUGANA("yotsugana", 1 shl 6),
    /** ら行 for な行 or だ, で, ど. */
    RA_NA_DA("ra_na_da", 1 shl 7),
    /** ひ/し. */
    HI_SHI("hi_shi", 1 shl 8),
    /** Two spellings of a loanword: ヴァ/バ, ティ/チ, ファ/ハ. */
    LOANWORD("loanword", 1 shl 9),
    /** Romaji: l typed for r (`lamen`). */
    L_AS_R("l_as_r", 1 shl 10),
    /** Romaji: `cu` typed for つ. */
    CU("cu", 1 shl 11),
    ;

    companion object {
        /** Returns: the bits of [rules] for [com.yuanzhe.kotomo.engine.Core.Options.japaneseFuzzy]. */
        fun mask(rules: Set<JapaneseFuzzyRule>): Int = rules.fold(0) { bits, rule -> bits or rule.bit }
    }
}
