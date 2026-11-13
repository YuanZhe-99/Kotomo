package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.ime.Inflection.Form
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T16 (PLAN §10.1): every conjugation type's dictionary form and forms, from id.def fields. */
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
        assertEquals("書ける", write[Form.POTENTIAL])
        assertEquals("書こう", write[Form.VOLITIONAL])
        assertEquals("お書きになります", write[Form.HONORIFIC])
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
    fun godanExceptions() {
        // 行く: 行って, not 行いて; its keigo is another word.
        val go = forms("行く", "いく", "動詞,非自立,*,*,五段・カ行促音便,基本形")
        assertEquals("行って", go[Form.TE])
        assertEquals("行った", go[Form.TA])
        assertEquals("いらっしゃいます", go[Form.HONORIFIC])
        assertEquals("参ります", go[Form.HUMBLE])
        assertEquals("いって", readings("行く", "いく", "動詞,非自立,*,*,五段・カ行促音便,基本形")[Form.TE])
        assertEquals("問うて", forms("問う", "とう", "動詞,自立,*,*,五段・ワ行ウ音便,基本形")[Form.TE])
        val come = forms("いらっしゃる", "いらっしゃる", "動詞,自立,*,*,五段・ラ行特殊,基本形")
        assertEquals("いらっしゃいます", come[Form.MASU])
        assertEquals("いらっしゃい", come[Form.IMPERATIVE])
        val aru = forms("ある", "ある", "動詞,自立,*,*,五段・ラ行アル,基本形")
        assertEquals("ない", aru[Form.NAI])
        assertNull(aru[Form.POTENTIAL])
    }

    @Test
    fun fromAnyCoveredForm() {
        // 行きます arrives as 行き (連用形), 行った as 行っ (連用タ接続): both find 行く.
        assertEquals("行く", Inflection.word("行き", "いき", "動詞,非自立,*,*,五段・カ行促音便,連用形")?.text)
        assertEquals("行く", Inflection.word("行っ", "いっ", "動詞,非自立,*,*,五段・カ行促音便,連用タ接続")?.text)
        assertEquals("食べる", Inflection.word("食べ", "たべ", "動詞,自立,*,*,一段,連用形")?.text)
        assertEquals("来る", Inflection.word("来", "き", "動詞,自立,*,*,カ変・来ル,連用形")?.text)
        assertEquals("くる", Inflection.word("来", "き", "動詞,自立,*,*,カ変・来ル,連用形")?.reading)
        assertEquals("高い", Inflection.word("高く", "たかく", "形容詞,自立,*,*,形容詞・アウオ段,連用テ接続")?.text)
    }

    @Test
    fun ichidanKuruSuru() {
        val eat = forms("食べる", "たべる", "動詞,自立,*,*,一段,基本形")
        assertEquals("食べます", eat[Form.MASU])
        assertEquals("食べられる", eat[Form.POTENTIAL])
        assertEquals("食べろ", eat[Form.IMPERATIVE])
        assertEquals("召し上がります", eat[Form.HONORIFIC])
        assertEquals("くれ", forms("くれる", "くれる", "動詞,自立,*,*,一段・クレル,基本形")[Form.IMPERATIVE])
        val come = forms("来る", "くる", "動詞,自立,*,*,カ変・来ル,基本形")
        assertEquals("来ない", come[Form.NAI])
        assertEquals("こない", readings("来る", "くる", "動詞,自立,*,*,カ変・来ル,基本形")[Form.NAI])
        assertEquals("来い", come[Form.IMPERATIVE])
        val study = forms("勉強", "べんきょう", "名詞,サ変接続,*,*,*,*")
        assertEquals("勉強します", study[Form.MASU])
        assertEquals("勉強できる", study[Form.POTENTIAL])
        assertEquals("勉強いたします", study[Form.HUMBLE])
        assertEquals("します", forms("する", "する", "動詞,自立,*,*,サ変・スル,基本形")[Form.MASU])
    }

    @Test
    fun adjectives() {
        val high = forms("高い", "たかい", "形容詞,自立,*,*,形容詞・アウオ段,基本形")
        assertEquals("高いです", high[Form.POLITE])
        assertEquals("高かった", high[Form.TA])
        assertEquals("高くない", high[Form.NAI])
        assertEquals("高ければ", high[Form.BA])
        val good = forms("いい", "いい", "形容詞,自立,*,*,形容詞・イイ,基本形")
        assertEquals("よかった", good[Form.TA])
        assertEquals("よくない", good[Form.NAI])
    }

    @Test
    fun nothingForWhatDoesNotConjugate() {
        // Negative: nouns (not サ変), particles, unknown forms and empty input.
        assertNull(Inflection.word("池袋", "いけぶくろ", "名詞,固有名詞,地域,一般,*,*"))
        assertNull(Inflection.word("に", "に", "助詞,格助詞,一般,*,*,*"))
        assertNull(Inflection.word("書か", "かか", "動詞,自立,*,*,五段・カ行イ音便,体言接続特殊"))
        assertNull(Inflection.word("", "", "動詞,自立,*,*,一段,基本形"))
        assertNull(Inflection.word("書く", "かく", "broken"))
    }
}
