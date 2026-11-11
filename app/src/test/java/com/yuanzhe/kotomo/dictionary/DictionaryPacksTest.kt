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
import java.security.MessageDigest

/**
 * Host test T17-packs (PLAN §10.5 "词库数据"): a converted pack in the host librime, ranked as the
 * keyboard ranks it: a pack word with a reading of its own first, a pack word on a common reading
 * after the built-in word, after a word added by hand and before an imported one, gone when the
 * pack is switched off. With `KOTOMO_PACKS=1` the app's own converters also run over the pinned
 * source files in `.deps/` (SHA-256 from `build-lock.json`), print each pack's counts, time and
 * memory, and one real pack is typed.
 *
 * Notes: runs only in tests/conversion/run.sh (`kotomo.corpus.root` set, host libraries built).
 */
class DictionaryPacksTest {
    private var tmp: File? = null

    @After
    fun tearDown() {
        tmp?.let {
            Rime.shutdown()
            it.deleteRecursively()
        }
    }

    private fun root(): File {
        val root = System.getProperty("kotomo.corpus.root")?.let(::File)
        assumeTrue("run tests/conversion/run.sh", root?.isDirectory == true)
        return root!!
    }

    /** Returns the 0-based rank of [text] among the first 20 candidates for [input], or -1. */
    private fun rank(input: String, text: String): Int {
        Rime.clear()
        Rime.setInput(input)
        return Rime.getCandidates(20).indexOfFirst { it.text == text }
    }

    /** Reloads librime's tables as the keyboard does after a store change. */
    private fun reload() {
        assertTrue(Rime.setLearning(true))
        Rime.setOption("zh_hans", true)
    }

    private fun store(root: File): DictionaryStore {
        val dir = Files.createTempDirectory("kotomo-packs").toFile().also { tmp = it }
        val user = File(dir, "rime").apply { mkdirs() }
        assertTrue(Rime.initialize(root.resolve("build/rime-prebuilt").path, user.path))
        Rime.setOption("zh_hans", true)
        return DictionaryStore(File(dir, "dictionaries"), user, Dispatchers.Unconfined) { 0L }
    }

    private fun info(source: DictionarySource) = PackInfo(source.id, source.titles, source.lang, source.category, source.license, source.attribution, "test", source.checked)

    @Test
    fun aConvertedPackRanksByTrust() = runBlocking {
        val root = root()
        val out = Conversion(Lang.ZH)
        Convert.rime("---\n...\n你好\tni hao\t100\n泥嚎\tni hao\t100\n珂朵摩\tke duo mo\t100\n".lineSequence(), weighted = true, out)
        val store = store(root)
        assertEquals("before: the pack's made-up word is not offered", -1, rank("keduomo", "珂朵摩"))
        val pack = info(Sources.of("zh-thuocl-it")!!)
        assertTrue(store.install(pack, out.entries()))
        reload()
        assertEquals("a pack word with a reading of its own comes first", 0, rank("keduomo", "珂朵摩"))
        assertEquals("你好 stays first", 0, rank("nihao", "你好"))
        assertTrue("a pack word on a common reading is offered after the built-in word", rank("nihao", "泥嚎") > 0)

        store.put(Entry(Lang.ZH, "柯多默", "ke duo mo", 10, Source.MANUAL))
        store.import(Formats.read("zh\t科多模\tke duo mo\t100\n".toByteArray(), Format.TSV, Lang.ZH, generated = false))
        reload()
        val packed = rank("keduomo", "珂朵摩")
        val imported = rank("keduomo", "科多模")
        assertEquals("added by hand first", 0, rank("keduomo", "柯多默"))
        assertTrue("then the pack ($packed), then the import ($imported)", packed in 1..<imported)

        store.setPack(pack.id, false)
        reload()
        assertEquals("pack switched off: gone", -1, rank("keduomo", "珂朵摩"))
    }

    /** The pinned local copy of a source file, checked against build-lock.json. */
    private fun pinned(root: File, source: String, key: String): File {
        @Suppress("UNCHECKED_CAST")
        val lock = Json.parse(root.resolve("build-lock.json").readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val sha = ((lock["dataSources"] as Map<String, Any?>)[source] as Map<String, Any?>).obj("sha256")!![key] as String
        val file = root.resolve(".deps/$source/${key.substringAfterLast('/')}")
        assertTrue("missing ${file.path}", file.isFile)
        val got = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("${file.path} does not match build-lock.json", sha, got)
        return file
    }

    /** The pinned files a source reads, in the order of its inputs. */
    private fun files(root: File, s: DictionarySource): List<File> = when {
        s.id == "en-ice-tech" -> listOf(pinned(root, "rime-ice", "en_dicts/en_ext.dict.yaml"))
        s.id == "zh-web-slang" -> listOf(pinned(root, "zhwiki", "web-slang-20261018.dict.yaml"))
        s.format == SourceFormat.THUOCL -> listOf(
            pinned(root, "thuocl", "data/" + (s.inputs[0] as Input.Plain).url.substringAfterLast('/')),
            pinned(root, "rime-ice", "cn_dicts/8105.dict.yaml"),
        )
        else -> error("no pinned files for ${s.id}")
    }

    @Test
    fun realSourcesConvertWithTheAppsCode() = runBlocking {
        val root = root()
        assumeTrue("KOTOMO_PACKS=1", System.getProperty("kotomo.packs") == "1")
        val rt = Runtime.getRuntime()
        var converted: Conversion? = null
        for (s in Sources.ALL) {
            System.gc()
            val before = rt.totalMemory() - rt.freeMemory()
            val started = System.nanoTime()
            val c = s.convert(files(root, s))
            val entries = c.entries().toList()
            val ms = (System.nanoTime() - started) / 1_000_000
            val used = (rt.totalMemory() - rt.freeMemory() - before) / (1024 * 1024)
            println("packs: ${s.id} read=${c.read} words=${entries.size} dropped=${c.drops} ms=$ms heapMB=$used")
            assertTrue("${s.id}: no words", entries.isNotEmpty())
            // What the converter keeps passes the import checks unchanged.
            val again = Formats.read(Formats.write(entries.take(2000), Format.TSV).toByteArray(), Format.TSV, s.lang, generated = false)
            assertEquals("${s.id}: refused on reading back", 0, again.refused)
            if (s.id == "zh-thuocl-it") converted = c
        }
        val store = store(root)
        val source = Sources.of("zh-thuocl-it")!!
        val entries = converted!!.entries().toList()
        // Long words the built-in dictionary does not put first, before and after the install.
        val long = entries.filter { it.text.length >= 4 }.let { l -> l.filterIndexed { i, _ -> i % (l.size / 200 + 1) == 0 } }
        val before = long.filter { rank(it.rimeCode!!, it.text) != 0 }
        assertTrue(store.install(info(source), entries.asSequence()))
        reload()
        val first = before.count { rank(it.rimeCode!!, it.text) == 0 }
        val shown = before.count { rank(it.rimeCode!!, it.text) in 0..4 }
        println("packs: of ${before.size} long zh-thuocl-it words not first before the install, $first are first after it and $shown in the first five")
        val top = entries.take(20)
        val offered = top.count { rank(it.rimeCode!!, it.text) >= 0 }
        println("packs: $offered of the ${top.size} most frequent zh-thuocl-it words offered in the first 20 candidates")
        assertTrue("most of the pack's top words are offered ($offered/${top.size})", offered >= top.size * 3 / 4)
    }
}
