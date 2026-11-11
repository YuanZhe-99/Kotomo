package com.yuanzhe.kotomo.dictionary

import com.yuanzhe.kotomo.engine.Rime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Host test T17 (PLAN §10.5): the tables [DictionaryStore] writes are fed as written to the host
 * librime on the prebuilt data, and ranked as the keyboard ranks them: a word added by hand
 * first, an imported or LLM-made word on a common reading offered but after the built-in word,
 * nothing once the switch is off.
 *
 * Notes: runs only in tests/conversion/run.sh (`kotomo.corpus.root` set, host libraries built).
 * Re-selecting the schema (setLearning) is how the keyboard reloads the tables.
 */
class DictionaryRimeTest {
    private var tmp: File? = null

    @After
    fun tearDown() {
        tmp?.let {
            Rime.shutdown()
            it.deleteRecursively()
        }
    }

    /** Returns the 0-based rank of [text] among the first 20 candidates for [input], or -1. */
    private fun rank(input: String, text: String): Int {
        Rime.clear()
        Rime.setInput(input)
        return Rime.getCandidates(20).indexOfFirst { it.text == text }
    }

    @Test
    fun storeTablesRankByTrust() = runBlocking {
        val root = System.getProperty("kotomo.corpus.root")?.let(::File)
        assumeTrue("run tests/conversion/run.sh", root?.isDirectory == true)
        val dir = Files.createTempDirectory("kotomo-dict").toFile().also { tmp = it }
        val user = File(dir, "rime").apply { mkdirs() }
        val store = DictionaryStore(File(dir, "dictionaries"), user, Dispatchers.Unconfined) { 0L }
        assertTrue(Rime.initialize(root!!.resolve("build/rime-prebuilt").path, user.path))
        Rime.setOption("zh_hans", true)
        assertEquals("before: a made-up word is not offered", -1, rank("kaotuomo", "考拓莫"))

        store.put(Entry(Lang.ZH, "考拓莫", "kao tuo mo", 90, Source.MANUAL))
        val file = Formats.read("zh\t泥好\tni hao\t100\n".toByteArray(), Format.TSV, Lang.ZH, generated = false)
        val llm = Formats.read("""{"entries": [{"language": "zh", "text": "尼好", "reading": "ni hao", "weight": 100}]}""".toByteArray(), Format.JSON, Lang.ZH, generated = true)
        store.import(file)
        store.import(llm)
        assertTrue(Rime.setLearning(true))
        Rime.setOption("zh_hans", true)
        assertEquals("a word added by hand comes first", 0, rank("kaotuomo", "考拓莫"))
        val imported = rank("nihao", "泥好")
        val generated = rank("nihao", "尼好")
        assertTrue("imported words are offered ($imported) after 你好", imported > 0)
        assertTrue("LLM words are offered ($generated) after 你好", generated > 0)
        assertEquals("你好 stays first", 0, rank("nihao", "你好"))

        store.setSources(custom = false)
        Rime.setLearning(true)
        Rime.setOption("zh_hans", true)
        assertEquals("switched off: gone", -1, rank("kaotuomo", "考拓莫"))
        assertEquals("switched off: gone", -1, rank("nihao", "泥好"))
    }
}
