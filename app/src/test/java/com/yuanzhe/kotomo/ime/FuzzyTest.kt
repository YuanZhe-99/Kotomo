package com.yuanzhe.kotomo.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the fuzzy-pinyin rule file (T08, PLAN §9.6): each rule's patterns on real syllables (a
 * positive and a negative case each), the file's exact text and its identity. The same text is
 * fed to librime by FuzzyRimeTest and the corpus lines T08-*.
 */
class FuzzyTest {
    /** Applies one librime `fuzz` pattern the way Boost does for these patterns (Java's regex agrees on them). */
    private fun apply(rule: Pair<String, String>, syllable: String) = Regex(rule.first).replace(syllable, rule.second)

    /** Each rule: syllables it changes (both directions) and syllables it must leave alone. */
    private val cases = mapOf(
        FuzzyRule.Z_ZH to (listOf("zong" to "zhong", "zhi" to "zi") to listOf("ci", "zhong" to "zhong")),
        FuzzyRule.C_CH to (listOf("ci" to "chi", "chuan" to "cuan") to listOf("zi", "chi" to "chi")),
        FuzzyRule.S_SH to (listOf("si" to "shi", "shan" to "san") to listOf("xi", "shi" to "shi")),
        FuzzyRule.N_L to (listOf("ni" to "li", "lan" to "nan") to listOf("ri")),
        FuzzyRule.F_H to (listOf("fang" to "hang", "hui" to "fui") to listOf("zhi", "shi")),
        FuzzyRule.L_R to (listOf("lu" to "ru", "ren" to "len") to listOf("ni")),
        FuzzyRule.AN_ANG to (listOf("an" to "ang", "wan" to "wang", "zhang" to "zhan") to listOf("bian", "yuan", "guan", "xiang")),
        FuzzyRule.EN_ENG to (listOf("fen" to "feng", "sheng" to "shen") to listOf("bin", "ban")),
        FuzzyRule.IN_ING to (listOf("yin" to "ying", "ming" to "min") to listOf("ren", "bian")),
        FuzzyRule.IAN_IANG to (listOf("xian" to "xiang", "liang" to "lian") to listOf("ban", "guan")),
        FuzzyRule.UAN_UANG to (listOf("guan" to "guang", "huang" to "huan") to listOf("ban", "bian")),
    )

    @Test
    fun everyRuleHasCases() {
        assertEquals(FuzzyRule.entries.toSet(), cases.keys)
    }

    @Test
    fun rulesChangeTheirPairBothWaysAndNothingElse() {
        for ((rule, case) in cases) {
            val (positive, negative) = case
            for ((from, to) in positive) {
                val derived = setOf(apply(rule.forward, from), apply(rule.backward, from)) - from
                assertEquals("${rule.key}: $from", setOf(to), derived)
            }
            for (item in negative) {
                val syllable = if (item is Pair<*, *>) item.first as String else item as String
                val unchanged = if (item is Pair<*, *>) {
                    // The rule's own other direction may apply; this direction must not.
                    apply(rule.forward, syllable) == syllable
                } else {
                    apply(rule.forward, syllable) == syllable && apply(rule.backward, syllable) == syllable
                }
                assertTrue("${rule.key} must leave $syllable alone", unchanged)
            }
        }
    }

    @Test
    fun noRuleMeansNoFile() {
        assertNull(Fuzzy.yaml(emptySet()))
        assertEquals(Fuzzy.NONE, Fuzzy.hash(null))
    }

    @Test
    fun fileTextIsExact() {
        val expected = """
            # Kotomo: fuzzy and double pinyin, generated from the settings (chinese.fuzzy.*, chinese.double_pinyin); rewritten on every change.
            # Rules: z_zh an_ang
            patch:
              speller/algebra/+:
                - "fuzz/^z(?!h)/ZH/"
                - "fuzz/^zh/Z/"
                - "fuzz/(?<![iu])an${'$'}/ANG/"
                - "fuzz/(?<![iu])ang${'$'}/AN/"
                - "xlit/ABCDEFGHIJKLMNOPQRSTUVWXYZ/abcdefghijklmnopqrstuvwxyz/"
                - "abbrev/^([a-z]).+${'$'}/${'$'}1/"
                - "abbrev/^([zcs]h).+${'$'}/${'$'}1/"
        """.trimIndent() + "\n"
        assertEquals(expected, Fuzzy.yaml(setOf(FuzzyRule.AN_ANG, FuzzyRule.Z_ZH)))
    }

    @Test
    fun aRuleSetHasOneIdentity() {
        val all = FuzzyRule.entries.toSet()
        assertEquals(Fuzzy.yaml(all), Fuzzy.yaml(all.reversed().toSet()))
        assertEquals(Fuzzy.hash(Fuzzy.yaml(all)), Fuzzy.hash(Fuzzy.yaml(all.reversed().toSet())))
        assertNotEquals(Fuzzy.hash(Fuzzy.yaml(setOf(FuzzyRule.N_L))), Fuzzy.hash(Fuzzy.yaml(setOf(FuzzyRule.L_R))))
        assertEquals(64, Fuzzy.hash(Fuzzy.yaml(all)).length)
    }

    /** T08-double (PLAN §10.4): a scheme alone writes a file; Microsoft also takes the semicolon. */
    @Test
    fun doublePinyinFile() {
        val ms = checkNotNull(Fuzzy.yaml(emptySet(), DoublePinyin.MICROSOFT))
        assertTrue("speller/alphabet: \"zyxwvutsrqponmlkjihgfedcba;\"" in ms)
        assertTrue("\"xform/ing${'$'}/;/\"" in ms)
        val xiaohe = checkNotNull(Fuzzy.yaml(setOf(FuzzyRule.Z_ZH), DoublePinyin.XIAOHE))
        // The fuzzy rules come first, the scheme's rules after them; no semicolon for Xiaohe.
        assertTrue(xiaohe.indexOf("fuzz/") < xiaohe.indexOf("xform/^zh/V/"))
        assertFalse("alphabet" in xiaohe)
        assertNotEquals(Fuzzy.hash(Fuzzy.yaml(emptySet(), DoublePinyin.SOGOU)), Fuzzy.hash(ms))
    }
}
