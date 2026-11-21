package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.ime.Inflection.Form
import com.yuanzhe.kotomo.ime.Inflection.Register
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T16 (PLAN §10.1): conjugation across all 4 registers (`PLAIN`, `POLITE`, `HONORIFIC`, `HUMBLE`),
 * generic `五段動詞` row inference, na-adjectives, curated lexical keigo/expressions,
 * whole-sentence predicate extraction with particle preservation, and half-width katakana.
 */
class InflectionTest {
    private fun forms(text: String, reading: String, pos: String): Map<Form, String> =
        Inflection.forms(checkNotNull(Inflection.word(text, reading, pos)) { "$text $pos" }).associate { it.form to it.text }

    private fun readings(text: String, reading: String, pos: String): Map<Form, String> =
        Inflection.forms(checkNotNull(Inflection.word(text, reading, pos))).associate { it.form to it.reading }

    @Test
    fun godanRows() {
        val write = forms("書く", "かく", "動詞,自立,*,*,五段・カ行イ音便,基本形")
        assertEquals("書きます", write[Form.MASU])
        assertEquals("書いて", write[Form.TE])
        assertEquals("書かない", write[Form.NAI])
        assertEquals("書かなかった", write[Form.PAST_NEGATIVE])
        assertEquals("書け", write[Form.IMPERATIVE])
        assertEquals("書ける", write[Form.POTENTIAL])
        assertEquals("書けます", write[Form.POTENTIAL_POLITE])
        assertEquals("書こう", write[Form.VOLITIONAL])
        assertEquals("書きましょう", write[Form.MASHOU])
        assertEquals("書きませんでした", write[Form.MASENDESHITA])
        assertEquals("書いてください", write[Form.TE_KUDASAI])
        assertEquals("お書きになります", write[Form.HONORIFIC])
        assertEquals("お書きください", write[Form.HONORIFIC_REQUEST])
        assertEquals("お書きいたします", write[Form.HUMBLE_ALT])
        assertEquals("泳いだ", forms("泳ぐ", "およぐ", "動詞,自立,*,*,五段・ガ行,基本形")[Form.TA])
        assertEquals("話して", forms("話す", "はなす", "動詞,自立,*,*,五段・サ行,基本形")[Form.TE])
        assertEquals("待った", forms("待つ", "まつ", "動詞,自立,*,*,五段・タ行,基本形")[Form.TA])
        assertEquals("死んで", forms("死ぬ", "しぬ", "動詞,自立,*,*,五段・ナ行,基本形")[Form.TE])
        assertEquals("遊ばせる", forms("遊ぶ", "あそぶ", "動詞,自立,*,*,五段・バ行,基本形")[Form.CAUSATIVE])
        assertEquals("読まれる", forms("読む", "よむ", "動詞,自立,*,*,五段・マ行,基本形")[Form.PASSIVE])
        assertEquals("帰れば", forms("帰る", "かえる", "動詞,自立,*,*,五段・ラ行,基本形")[Form.BA])
        val buy = forms("買う", "かう", "動詞,自立,*,*,五段・ワ行促音便,基本形")
        assertEquals("買わない", buy[Form.NAI])
        assertEquals("買って", buy[Form.TE])
        assertEquals("買え", buy[Form.IMPERATIVE])
    }

    @Test
    fun genericGodanPosInfersRow() {
        // Mozc's real dictionary tags verbs like 話す, 待つ, 読む, 泳ぐ, 死ぬ, 遊ぶ as 五段動詞 without a row name.
        assertEquals("話します", forms("話す", "はなす", "動詞,自立,*,*,五段動詞,基本形")[Form.MASU])
        assertEquals("待ちます", forms("待ち", "まち", "動詞,自立,*,*,五段動詞,連用形")[Form.MASU])
        assertEquals("読んで", forms("読ま", "よま", "動詞,自立,*,*,五段動詞,未然形")[Form.TE])
        assertEquals("泳ぎましょう", forms("泳げ", "およげ", "動詞,自立,*,*,五段動詞,仮定形")[Form.MASHOU])
        // 死ぬ must never produce お死にになります; it uses お亡くなりになります.
        val die = forms("死ぬ", "しぬ", "動詞,自立,*,*,五段動詞,基本形")
        assertEquals("お亡くなりになります", die[Form.HONORIFIC])
        assertNull(die[Form.HUMBLE])
        assertTrue(Inflection.forms(checkNotNull(Inflection.word("死ぬ", "しぬ", "動詞,自立,*,*,五段動詞,基本形"))).none { it.text.contains("お死に") })
    }

    @Test
    fun godanExceptions() {
        val go = forms("行く", "いく", "動詞,非自立,*,*,五段・カ行促音便,基本形")
        assertEquals("行って", go[Form.TE])
        assertEquals("行った", go[Form.TA])
        assertEquals("行かなかった", go[Form.PAST_NEGATIVE])
        assertEquals("いらっしゃいます", go[Form.HONORIFIC])
        assertEquals("いらっしゃる", go[Form.HONORIFIC_PLAIN])
        assertEquals("おいでになります", go[Form.HONORIFIC_ALT])
        assertEquals("行かれます", go[Form.HONORIFIC_PASSIVE])
        assertEquals("参ります", go[Form.HUMBLE])
        assertEquals("参る", go[Form.HUMBLE_PLAIN])
        assertEquals("伺います", go[Form.HUMBLE_ALT])
        assertEquals("いって", readings("行く", "いく", "動詞,非自立,*,*,五段・カ行促音便,基本形")[Form.TE])
        assertEquals("問うて", forms("問う", "とう", "動詞,自立,*,*,五段・ワ行ウ音便,基本形")[Form.TE])
        val come = forms("いらっしゃる", "いらっしゃる", "動詞,自立,*,*,五段・ラ行特殊,基本形")
        assertEquals("いらっしゃいます", come[Form.MASU])
        assertEquals("いらっしゃい", come[Form.IMPERATIVE])
        val aru = forms("ある", "ある", "動詞,自立,*,*,五段・ラ行アル,基本形")
        assertEquals("ない", aru[Form.NAI])
        assertNull(aru[Form.POTENTIAL])
        assertNull(aru[Form.TE_KUDASAI])
    }

    @Test
    fun fromAnyCoveredForm() {
        assertEquals("行く", Inflection.word("行き", "いき", "動詞,非自立,*,*,五段・カ行促音便,連用形")?.text)
        assertEquals("行く", Inflection.word("行っ", "いっ", "動詞,非自立,*,*,五段・カ行促音便,連用タ接続")?.text)
        assertEquals("行く", Inflection.word("行こう", "いこう", "動詞,自立,*,*,五段・カ行促音便,未然ウ接続,行く")?.text)
        assertEquals("飲む", Inflection.word("飲もう", "のもう", "動詞,自立,*,*,五段動詞,未然ウ接続,*")?.text)
        assertEquals("食べる", Inflection.word("食べ", "たべ", "動詞,自立,*,*,一段,連用形")?.text)
        assertEquals("食べる", Inflection.word("食べよう", "たべよう", "動詞,自立,*,*,一段,未然ウ接続,*")?.text)
        assertEquals("来る", Inflection.word("来", "き", "動詞,自立,*,*,カ変・来ル,連用形")?.text)
        assertEquals("来る", Inflection.word("来よう", "こよう", "動詞,自立,*,*,カ変・来ル,未然ウ接続,来る")?.text)
        assertEquals("する", Inflection.word("しよう", "しよう", "動詞,自立,*,*,サ変・スル,未然ウ接続,*")?.text)
        assertEquals("くる", Inflection.word("来", "き", "動詞,自立,*,*,カ変・来ル,連用形")?.reading)
        assertEquals("高い", Inflection.word("高く", "たかく", "形容詞,自立,*,*,形容詞・アウオ段,連用テ接続")?.text)
        assertEquals("静か", Inflection.word("静か", "しずか", "名詞,形容動詞語幹,*,*,*,*")?.text)
    }

    @Test
    fun ichidanKuruSuruAndOneMoraGuard() {
        val eat = forms("食べる", "たべる", "動詞,自立,*,*,一段,基本形")
        assertEquals("食べます", eat[Form.MASU])
        assertEquals("食べられる", eat[Form.POTENTIAL])
        assertEquals("食べられます", eat[Form.POTENTIAL_POLITE])
        assertEquals("食べろ", eat[Form.IMPERATIVE])
        assertEquals("召し上がります", eat[Form.HONORIFIC])
        assertEquals("くれ", forms("くれる", "くれる", "動詞,自立,*,*,一段・クレル,基本形")[Form.IMPERATIVE])

        // 1-mora stem Ichidan (出る) skips お出になる and offers 出られます instead.
        val deru = Inflection.forms(checkNotNull(Inflection.word("出る", "でる", "動詞,自立,*,*,一段,基本形")))
        assertFalse(deru.any { it.text.startsWith("お出") })
        assertTrue(deru.any { it.register == Register.HONORIFIC && it.text == "出られます" })

        val come = forms("来る", "くる", "動詞,自立,*,*,カ変・来ル,基本形")
        assertEquals("来ない", come[Form.NAI])
        assertEquals("こない", readings("来る", "くる", "動詞,自立,*,*,カ変・来ル,基本形")[Form.NAI])
        assertEquals("来い", come[Form.IMPERATIVE])
        assertEquals("来られます", come[Form.HONORIFIC_PASSIVE])

        val study = forms("勉強", "べんきょう", "名詞,サ変接続,*,*,*,*")
        assertEquals("勉強します", study[Form.MASU])
        assertEquals("勉強できる", study[Form.POTENTIAL])
        assertEquals("勉強いたします", study[Form.HUMBLE])
        assertEquals("ご勉強ください", study[Form.HONORIFIC_REQUEST])
        assertEquals("ご勉強いたします", study[Form.HUMBLE_ALT])
        assertEquals("ご説明ください", forms("説明", "せつめい", "名詞,サ変接続,*,*,*,*")[Form.HONORIFIC_REQUEST])
        assertEquals("お電話ください", forms("電話", "でんわ", "名詞,サ変接続,*,*,*,*")[Form.HONORIFIC_REQUEST])
        assertEquals("します", forms("する", "する", "動詞,自立,*,*,サ変・スル,基本形")[Form.MASU])
    }

    @Test
    fun adjectivesAndNaAdjectives() {
        val high = forms("高い", "たかい", "形容詞,自立,*,*,形容詞・アウオ段,基本形")
        assertEquals("高いです", high[Form.POLITE])
        assertEquals("高かった", high[Form.TA])
        assertEquals("高くない", high[Form.NAI])
        assertEquals("高くなかった", high[Form.PAST_NEGATIVE])
        assertEquals("高ければ", high[Form.BA])
        assertEquals("高くありません", high[Form.POLITE_NEGATIVE])
        assertEquals("高くありませんでした", high[Form.MASENDESHITA])
        val good = forms("いい", "いい", "形容詞,自立,*,*,形容詞・イイ,基本形")
        assertEquals("よかった", good[Form.TA])
        assertEquals("よくない", good[Form.NAI])
        assertEquals("よろしいです", good[Form.HONORIFIC])
        assertEquals("結構です", good[Form.HONORIFIC_REQUEST])

        val quiet = forms("静か", "しずか", "名詞,形容動詞語幹,*,*,*,*")
        assertEquals("静かだ", quiet[Form.PLAIN])
        assertEquals("静かな", quiet[Form.ADNOMINAL])
        assertEquals("静かに", quiet[Form.ADVERBIAL])
        assertEquals("静かです", quiet[Form.POLITE])
        assertEquals("静かでした", quiet[Form.MASHITA])
        assertEquals("静かではありません", quiet[Form.MASEN])
        assertEquals("静かではありませんでした", quiet[Form.MASENDESHITA])
    }

    @Test
    fun lexicalKeigoAndExpressions() {
        val kaisha = Inflection.forms(checkNotNull(Inflection.lexical("会社", "かいしゃ"))).associate { it.form to it.text }
        assertEquals("御社", kaisha[Form.HONORIFIC])
        assertEquals("貴社", kaisha[Form.HONORIFIC_ALT])
        assertEquals("弊社", kaisha[Form.HUMBLE])

        val who = Inflection.forms(checkNotNull(Inflection.lexical("誰", "だれ"))).associate { it.form to it.text }
        assertEquals("どなた", who[Form.HONORIFIC])

        val today = Inflection.forms(checkNotNull(Inflection.lexical("今日", "きょう"))).associate { it.form to it.text }
        assertEquals("本日", today[Form.POLITE])

        val thanks = Inflection.forms(checkNotNull(Inflection.lexical("ありがとう", "ありがとう"))).associate { it.form to it.text }
        assertEquals("誠にありがとうございます", thanks[Form.HONORIFIC])
        assertEquals("感謝申し上げます", thanks[Form.HUMBLE])

        val understood = Inflection.forms(checkNotNull(Inflection.lexical("わかった", "わかった"))).associate { it.form to it.text }
        assertEquals("承知いたしました", understood[Form.HUMBLE])
        assertEquals("かしこまりました", understood[Form.HUMBLE_ALT])
    }

    @Test
    fun wholeSentenceExtractionAndParticles() {
        val mixedSentence = listOf("池袋" to "いけぶくろ", "に" to "に", "買いに" to "かいに", "行こう" to "いこう")
        val target = checkNotNull(
            Inflection.extractTarget(mixedSentence) { s, r ->
                if (s == "行こう" && r == "いこう") Inflection.DescribedWord("行こ", "いこ", "動詞,自立,*,*,五段・カ行促音便,未然ウ接続") else null
            },
        )
        assertEquals("池袋に買いに", target.prefix)
        assertEquals("行こう", target.currentSurface)
        assertEquals("", target.suffix)
        assertEquals("行きましょう", target.forms.first { it.form == Form.MASHOU }.text)
        assertEquals("参ります", target.forms.first { it.form == Form.HUMBLE }.text)

        val questionSentence = listOf("明日は" to "あしたは", "行く" to "いく", "か" to "か")
        val qTarget = checkNotNull(
            Inflection.extractTarget(questionSentence) { s, r ->
                if (s == "行く" && r == "いく") Inflection.DescribedWord("行く", "いく", "動詞,非自立,*,*,五段・カ行促音便,基本形") else null
            },
        )
        assertEquals("明日は", qTarget.prefix)
        assertEquals("か", qTarget.suffix)
        assertTrue(qTarget.forms.any { it.text == "行きます" })
        assertTrue(qTarget.forms.any { it.text == "いらっしゃいます" })
        assertFalse(qTarget.forms.any { it.form == Form.IMPERATIVE })

        val naQuestion = listOf("静かですか" to "しずかですか")
        val naTarget = checkNotNull(
            Inflection.extractTarget(naQuestion) { s, r ->
                if (s == "静かです" && r == "しずかです") Inflection.DescribedWord("静か", "しずか", "名詞,形容動詞語幹,*,*,*,*") else null
            },
        )
        assertEquals("か", naTarget.suffix)
        assertFalse(naTarget.forms.any { it.text == "静かだ" })
        assertTrue(naTarget.forms.any { it.text == "静かでした" })

        val suruCompound = listOf("明日" to "あした", "勉強" to "べんきょう", "します" to "します")
        val sTarget = checkNotNull(
            Inflection.extractTarget(suruCompound) { s, _ ->
                when (s) {
                    "勉強", "勉強します" -> Inflection.DescribedWord("勉強", "べんきょう", "名詞,サ変接続,*,*,*,*")
                    "します" -> Inflection.DescribedWord("し", "し", "動詞,自立,*,*,サ変・スル,連用形")
                    else -> null
                }
            },
        )
        assertEquals("明日", sTarget.prefix)
        assertEquals("勉強する", sTarget.word.text)
        assertTrue(sTarget.forms.any { it.text == "勉強いたします" })
    }

    @Test
    fun halfWidthKatakana() {
        assertEquals("ｲｸ", Inflection.halfWidthKatakana("いく"))
        assertEquals("ｶｲｼｬ", Inflection.halfWidthKatakana("かいしゃ"))
        assertEquals("ﾊﾟﾝ", Inflection.halfWidthKatakana("ぱん"))
        assertEquals("ｺｰﾋｰ", Inflection.halfWidthKatakana("こーひー"))
    }

    @Test
    fun nothingForWhatDoesNotConjugate() {
        assertNull(Inflection.word("池袋", "いけぶくろ", "名詞,固有名詞,地域,一般,*,*"))
        assertNull(Inflection.word("に", "に", "助詞,格助詞,一般,*,*,*"))
        assertNull(Inflection.word("こんな", "こんな", "名詞,形容動詞語幹,*,*,*,*"))
        assertNull(Inflection.word("同じ", "おなじ", "名詞,形容動詞語幹,*,*,*,*"))
        assertNull(Inflection.word("書か", "かか", "動詞,自立,*,*,五段・カ行イ音便,体言接続特殊"))
        assertNull(Inflection.word("", "", "動詞,自立,*,*,一段,基本形"))
        assertNull(Inflection.word("書く", "かく", "broken"))
    }
}
