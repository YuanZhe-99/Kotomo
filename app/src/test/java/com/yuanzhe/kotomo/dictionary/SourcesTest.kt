package com.yuanzhe.kotomo.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Checks the converters of the downloadable sources (T17-sources, PLAN §10.5 "词库数据") on tiny
 * original samples in each format: what becomes a word, with which reading and weight, and what is
 * dropped and why; and the list of sources the settings offer.
 */
class SourcesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun rime(body: String) = "# sample\n---\nname: sample\n...\n$body".lineSequence()

    private fun Conversion.words() = entries().map { it.text to it.reading }.toList()

    @Test
    fun rimeListsKeepValidWordsAndCountTheRest() {
        val out = Conversion(Lang.ZH)
        Convert.rime(rime("你好\tni hao\t900\n泥嚎\tni hao\t9\n你好\tni hao\t1000\nB站\tB zhan\t5\n好\tni hao\t3\n考拓\tkao xx\t1\n# comment\n\n多\tduo\t1\textra\tcolumn\n"), weighted = true, out)
        assertEquals(listOf("你好" to "ni hao", "泥嚎" to "ni hao"), out.words())
        assertEquals(mapOf(Drop.DUPLICATE to 1, Drop.READING to 2, Drop.READING_COUNT to 1, Drop.FORMAT to 1), out.drops.toMap())
        assertEquals(7, out.read)
        // Frequencies above 100 are scaled by their logarithm against the largest; a repeat keeps the larger one.
        val w = out.entries().associate { it.text to it.weight }
        assertEquals(100, w["你好"])
        assertTrue(w.getValue("泥嚎") in 1..99)
        assertTrue(out.entries().all { it.source == Source.DOWNLOAD })
    }

    @Test
    fun headerLinesBeforeTheBodyAreNotWords() {
        val out = Conversion(Lang.ZH)
        Convert.rime("name: x\n你好\tni hao\n".lineSequence(), weighted = true, out)
        assertEquals(0, out.read)
    }

    @Test
    fun unweightedListsGiveEveryWordFifty() {
        val out = Conversion(Lang.ZH)
        Convert.rime(rime("考拓站\tkao tuo zhan\t\n"), weighted = false, out)
        assertEquals(listOf(50), out.entries().map { it.weight }.toList())
    }

    @Test
    fun englishListsKeepWordsNotMarkup() {
        val out = Conversion(Lang.EN)
        Convert.rime(rime("Kotlin\tkotlin\n&nbsp;\t&nbsp;\nKotlin\tkotlin\nversion control\tvc\n"), weighted = false, out)
        assertEquals(listOf("Kotlin" to null, "version control" to null), out.words())
        assertEquals(mapOf(Drop.FILTERED to 1, Drop.DUPLICATE to 1), out.drops.toMap())
    }

    @Test
    fun thuoclReadingsComeOnlyFromTrustedCharacters() {
        val chars = Convert.characters(rime("银\tyin\t100\n行\txing\t100\n行\thang\t50\n拓\ttuo\t5\n展\tzhan\t90\n长\tchang\t1000\n长\tzhang\t50\n"))
        assertEquals(mapOf('银'.code to "yin", '拓'.code to "tuo", '展'.code to "zhan", '长'.code to "chang"), chars.trusted)
        assertEquals(setOf('行'.code), chars.polyphones)
        val out = Conversion(Lang.ZH)
        Convert.thuocl("拓展\t 30\n银行\t 20\nC语言\t 10\n长银\t 5\nbroken\n".lineSequence(), chars, out)
        assertEquals(listOf("拓展" to "tuo zhan", "长银" to "chang yin"), out.words())
        // 行 is a polyphone without a dominant reading: 银行 is left out rather than read yin xing.
        assertEquals(mapOf(Drop.AMBIGUOUS to 1, Drop.NO_READING to 1, Drop.FORMAT to 1), out.drops.toMap())
    }

    @Test
    fun sourcesConvertTheirFilesInOrder() {
        val list = File(folder.root, "list").apply { writeText("拓展\t 30\n") }
        val chars = File(folder.root, "chars").apply { writeText("---\n...\n拓\ttuo\t1\n展\tzhan\t1\n") }
        val out = Sources.of("zh-thuocl-it")!!.convert(listOf(list, chars))
        assertEquals(listOf("拓展" to "tuo zhan"), out.words())
    }

    @Test
    fun theOfferedSourcesAreComplete() {
        val ids = Sources.ALL.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(15, ids.size)
        for (s in Sources.ALL) {
            assertEquals(s.id, setOf("en", "zh-CN", "zh-TW", "ja"), s.titles.keys)
            assertTrue(s.id, s.license.isNotBlank() && s.attribution.isNotBlank() && s.licenseUrl.startsWith("https://"))
            assertTrue(s.id, s.inputs.all { it !is Input.Plain || it.url.startsWith("https://") })
            assertTrue(s.id, s.hosts.isNotEmpty())
        }
        // GitHub release assets carry a published digest; plain files do not.
        assertTrue(Sources.of("zh-web-slang")!!.checked)
        assertTrue(Sources.of("zh-ice-base")!!.checked)
        assertTrue(Sources.of("zh-ice-base")!!.compiled)
        assertTrue(Sources.of("zh-ice-ext")!!.compiled)
        assertTrue(!Sources.of("zh-thuocl-it")!!.checked)
        assertTrue(!Sources.of("zh-thuocl-it")!!.compiled)
        assertEquals(listOf("raw.githubusercontent.com"), Sources.of("zh-thuocl-it")!!.hosts)
    }
}
