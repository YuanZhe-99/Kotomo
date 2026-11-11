package com.yuanzhe.kotomo.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks readings and the import formats (T17, PLAN §10.5): schema, readings, duplicates and
 * conflicts, encoding, weights and the preview, each with a positive and a negative case, and
 * that every exported format reads back.
 */
class FormatsTest {
    private fun read(text: String, format: Format, lang: Lang = Lang.ZH, generated: Boolean = false) =
        Formats.read(text.toByteArray(), format, lang, generated)

    // Readings

    @Test
    fun pinyinIsToneless() {
        assertEquals(listOf("ni", "hao"), Readings.pinyin("Nǐ hǎo"))
        assertEquals(listOf("zhong", "guo"), Readings.pinyin("zhong1guo2"))
        assertEquals(listOf("lv"), Readings.pinyin("lü4"))
        assertEquals(listOf("nv", "er"), Readings.pinyin("nu:'er"))
        assertEquals(listOf("xi", "an"), Readings.pinyin("xi'an"))
        assertEquals(listOf("xian"), Readings.pinyin("xian"))
    }

    @Test
    fun pinyinRefusesWhatIsNotPinyin() {
        assertNull(Readings.pinyin("nihaoq"))
        assertNull(Readings.pinyin("hello"))
        assertNull(Readings.pinyin("ni-hao"))
        assertNull(Readings.pinyin("''"))
    }

    @Test
    fun kanaBecomesHiragana() {
        assertEquals("こーひー", Readings.kana("コーヒー"))
        assertEquals("にほん", Readings.kana("にほん"))
        assertNull(Readings.kana("日本"))
        assertNull(Readings.kana("nihon"))
    }

    @Test
    fun chineseWordsNeedOneSyllablePerCharacter() {
        assertEquals("ni hao" to null, Readings.reading(Lang.ZH, "你好", "nihao"))
        assertEquals(null to Problem.READING_COUNT, Readings.reading(Lang.ZH, "你好", "ni"))
        // Mixed words (letters and Han) are not counted.
        assertEquals("ai ke si guang" to null, Readings.reading(Lang.ZH, "X光", "aikesi guang"))
    }

    @Test
    fun englishHasNoReading() {
        assertEquals(null to null, Readings.reading(Lang.EN, "hello", "anything"))
    }

    @Test
    fun wordsRefuseControlCharacters() {
        assertEquals("词" to null, Readings.text("  词 "))
        assertEquals(null to Problem.TEXT_CHARACTERS, Readings.text("a\u0007b"))
        assertEquals(null to Problem.TEXT_CHARACTERS, Readings.text("a\uFFFDb"))
        assertEquals(null to Problem.TEXT_TOO_LONG, Readings.text("字".repeat(Readings.MAX_TEXT + 1)))
        assertEquals(null to Problem.EMPTY_TEXT, Readings.text("   "))
    }

    // Encoding

    @Test
    fun strictUtf8WithoutBom() {
        val p = Formats.read("\uFEFFzh\t你好\tni hao\t60\n".toByteArray(), Format.TSV, Lang.ZH, false)
        assertEquals(listOf("你好"), p.entries.map { it.text })
        val broken = byteArrayOf('z'.code.toByte(), 'h'.code.toByte(), '\t'.code.toByte(), 0xC3.toByte(), 0x28)
        assertEquals(Problem.ENCODING, assertThrows(ImportException::class.java) { Formats.read(broken, Format.TSV, Lang.ZH, false) }.problem)
        val replaced = assertThrows(ImportException::class.java) { read("zh\t你好\tni hao\n\nzh\t\uFFFD\tni\n", Format.TSV) }
        assertEquals(Problem.ENCODING to 3, replaced.problem to replaced.line)
    }

    // Schema

    @Test
    fun tsvWithAndWithoutHeader() {
        val headed = read("word\treading\tweight\tnote\n你好\tni hao\t80\tgreeting\n", Format.TSV)
        assertEquals(listOf(Entry(Lang.ZH, "你好", "ni hao", 80, Source.IMPORT, mapOf("note" to "greeting"))), headed.entries)
        val plain = read("ja\t東京\tトウキョウ\t70\n", Format.TSV)
        assertEquals(listOf(Entry(Lang.JA, "東京", "とうきょう", 70, Source.IMPORT)), plain.entries)
    }

    @Test
    fun tsvRefusesExtraColumnsAndUnknownLanguages() {
        val p = read("zh\t你好\tni hao\t80\textra\nxx\t词\tci\t1\n", Format.TSV)
        assertEquals(listOf(LineError(1, Problem.COLUMNS), LineError(2, Problem.LANGUAGE)), p.firstErrors)
        assertTrue(p.entries.isEmpty())
    }

    @Test
    fun csvFollowsRfc4180() {
        val text = "language,text,reading,weight\r\nen,\"Hello, world\",,40\r\nen,\"say \"\"hi\"\"\",,\r\nzh,\"两\n行\",liang hang,1\r\n"
        val p = read(text, Format.CSV)
        assertEquals(listOf("Hello, world", "say \"hi\""), p.entries.map { it.text })
        // The quoted line break makes line 4's word fail, reported on the line where the record starts.
        assertEquals(listOf(LineError(4, Problem.TEXT_CHARACTERS)), p.firstErrors)
        assertEquals(Problem.SYNTAX, assertThrows(ImportException::class.java) { read("a,\"b\n", Format.CSV) }.problem)
    }

    @Test
    fun jsonTakesTheAppendixFields() {
        val text = """
            {"name": "test", "version": "1.0.0", "entries": [
              {"language": "ja", "text": "池袋", "reading": "いけぶくろ", "romaji": "ikebukuro", "pinyin": "chidai", "weight": 90, "tags": ["地名"]},
              {"language": "zh", "word": "苹果", "reading": "pingguo", "weight": 95, "emoji": ["🍎"]},
              {"language": "zh", "text": "坏", "reading": "not pinyin"}
            ]}
        """.trimIndent()
        val p = read(text, Format.JSON, generated = true)
        assertEquals(listOf("池袋", "苹果"), p.entries.map { it.text })
        assertEquals("ikebukuro", p.entries[0].extra["romaji"])
        assertEquals(listOf(Source.GENERATED, Source.GENERATED), p.entries.map { it.source })
        assertEquals(listOf(LineError(3, Problem.READING)), p.firstErrors)
    }

    @Test
    fun jsonRefusesNewerFormatsAndMalformedFiles() {
        assertEquals(Problem.NEWER_FORMAT, assertThrows(ImportException::class.java) { read("""{"format_version": 2, "entries": []}""", Format.JSON) }.problem)
        assertEquals(Problem.SYNTAX, assertThrows(ImportException::class.java) { read("""{"entries": [""", Format.JSON) }.problem)
        assertEquals(Problem.SYNTAX, assertThrows(ImportException::class.java) { read("""[1, 2]""", Format.JSON) }.problem)
        assertEquals(1, read("""{"format_version": 1, "entries": [{"language": "en", "text": "ok"}]}""", Format.JSON).entries.size)
    }

    @Test
    fun rimeListsAfterTheHeader() {
        val text = "# comment\n---\nname: test\nversion: \"1\"\n...\n你好\tni hao\t100\n# skipped\n中国\tzhong guo\n好\n"
        val p = read(text, Format.RIME, lang = Lang.EN)
        assertEquals(listOf("你好" to "ni hao", "中国" to "zhong guo"), p.entries.map { it.text to it.reading })
        assertTrue(p.entries.all { it.lang == Lang.ZH })
        assertEquals(listOf(LineError(9, Problem.NO_READING)), p.firstErrors)
        assertEquals(Problem.SYNTAX, assertThrows(ImportException::class.java) { read("你好\tni hao\n", Format.RIME) }.problem)
    }

    // Duplicates and conflicts

    @Test
    fun duplicatesInTheFileAreCountedOnce() {
        val p = read("zh\t你好\tni hao\t80\nzh\t你好\tnǐ hǎo\t20\nzh\t你好\tni hao\t10\n", Format.TSV)
        assertEquals(1, p.entries.size)
        assertEquals(2, p.duplicates)
        assertEquals(80, p.entries.single().weight)
    }

    @Test
    fun conflictsWithExistingEntriesAreCounted() {
        val existing = listOf(Entry(Lang.ZH, "你好", "ni hao", 50, Source.MANUAL))
        val p = Formats.preview(read("zh\t你好\tni hao\nzh\t您好\tnin hao\n", Format.TSV), existing)
        assertEquals(1, p.conflicts)
        assertEquals(0, Formats.preview(read("zh\t你好\tni hao\n", Format.TSV), emptyList()).conflicts)
    }

    // Weights

    @Test
    fun missingWeightsAreFiftyAndSmallFilesKeepTheirs() {
        assertEquals(listOf(50, 30, 100), read("zh\t一\tyi\nzh\t二\ter\t30\nzh\t三\tsan\t100\n", Format.TSV).entries.map { it.weight })
    }

    @Test
    fun largeWeightsAreScaledByTheFileMaximumBeforeClamping() {
        // Frequencies: the file's maximum 99999 becomes 100 and the others keep their order.
        val p = read("zh\t一\tyi\t99999\nzh\t二\ter\t999\nzh\t三\tsan\t9\nzh\t四\tsi\t0\n", Format.TSV)
        assertEquals(listOf(100, 60, 20, 0), p.entries.map { it.weight })
    }

    @Test
    fun generatedEntriesAreCappedAndNegativeWeightsRefused() {
        assertEquals(listOf(20, 5), read("zh\t一\tyi\t90\nzh\t二\ter\t5\n", Format.TSV, generated = true).entries.map { it.weight })
        val p = read("zh\t一\tyi\t-1\nzh\t二\ter\tmuch\n", Format.TSV)
        assertEquals(listOf(LineError(1, Problem.WEIGHT), LineError(2, Problem.WEIGHT)), p.firstErrors)
    }

    // Preview

    @Test
    fun previewListsTheFirstErrorsWithLineNumbers() {
        val text = (1..8).joinToString("") { "zh\t词$it\tbad\n" } + "zh\t词\tci\n"
        val p = read(text, Format.TSV)
        assertEquals(8, p.refused)
        assertEquals((1..Formats.SHOWN_ERRORS).map { LineError(it, Problem.READING) }, p.firstErrors)
        assertEquals(1, p.entries.size)
    }

    // Export

    @Test
    fun everyFormatReadsBack() {
        val entries = listOf(
            Entry(Lang.ZH, "你好", "ni hao", 80, Source.MANUAL),
            Entry(Lang.JA, "東京", "とうきょう", 70, Source.IMPORT, mapOf("romaji" to "toukyou")),
            Entry(Lang.EN, "Hello, \"you\"", null, 40, Source.GENERATED),
        )
        for (format in listOf(Format.TSV, Format.CSV, Format.JSON)) {
            val back = read(Formats.write(entries, format), format)
            assertEquals(format.name, entries.map { it.copy(source = Source.IMPORT, extra = if (format == Format.JSON) it.extra else emptyMap()) }, back.entries)
            assertEquals(format.name, 0, back.refused)
        }
        val rime = read(Formats.write(entries, Format.RIME), Format.RIME)
        assertEquals(listOf("你好"), rime.entries.map { it.text })
    }
}
