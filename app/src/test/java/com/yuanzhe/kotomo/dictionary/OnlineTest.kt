package com.yuanzhe.kotomo.dictionary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Checks the online dictionary client (T17, PLAN §10.5) with a fake transport that answers by the
 * full URL: the catalog's required members and version, size and SHA-256 checks with progress,
 * reading a pack (bad lines dropped and counted) and installing the original sample packs.
 */
class OnlineTest {
    @get:Rule val folder = TemporaryFolder()

    /** Answers only the URLs it was given, byte for byte; anything else fails like a network error. */
    private class FakeTransport(private val files: Map<String, ByteArray>) : Transport {
        val opened = ArrayList<String>()
        override fun open(url: String) = files[url]?.also { opened.add(url) }?.inputStream() ?: throw IOException("no such URL")
    }

    private fun catalogJson(vararg packs: Map<String, Any?>, version: Long = 1) =
        Json.write(mapOf("format_version" to version, "publisher" to "test", "packs" to packs.toList()))

    @Test
    fun catalogRequiresEveryMember() {
        val good = pack("sample-zh", Lang.ZH, gzip(resource("sample-zh.tsv"))).toJson() + ("future" to "kept")
        val noSize = good - "size"
        val noHash = good - "sha256"
        val badId = good + ("id" to "../etc")
        val plainHttp = good + ("id" to "plain") + ("url" to "http://example.invalid/p.tsv.gz")
        val catalog = Online.catalog(catalogJson(good, noSize, noHash, badId, plainHttp))
        assertEquals(listOf("sample-zh"), catalog.packs.map { it.id })
        assertEquals(4, catalog.refused)
        assertEquals("kept", catalog.packs.single().extra["future"])
        assertEquals("test", catalog.extra["publisher"])
    }

    @Test
    fun catalogRefusesUnknownVersionsAndGarbage() {
        assertEquals(OnlineProblem.CATALOG_VERSION, assertThrows(OnlineException::class.java) { Online.catalog(catalogJson(version = 2)) }.problem)
        assertEquals(OnlineProblem.CATALOG_VERSION, assertThrows(OnlineException::class.java) { Online.catalog("""{"packs": []}""") }.problem)
        assertEquals(OnlineProblem.CATALOG, assertThrows(OnlineException::class.java) { Online.catalog("<html>") }.problem)
    }

    @Test
    fun downloadChecksSizeAndHashWithProgress() {
        val bytes = gzip(resource("sample-zh.tsv"))
        val pack = pack("sample-zh", Lang.ZH, bytes)
        val transport = FakeTransport(mapOf(pack.url to bytes))
        val seen = ArrayList<Long>()
        assertTrue(Online.download(pack, transport) { done, total -> assertEquals(pack.size, total); seen.add(done) }.contentEquals(bytes))
        assertEquals(listOf(pack.url), transport.opened)
        assertEquals(bytes.size.toLong(), seen.last())
        assertEquals(seen.sorted(), seen)

        val short = FakeTransport(mapOf(pack.url to bytes.copyOf(bytes.size - 1)))
        assertEquals(OnlineProblem.SIZE, assertThrows(OnlineException::class.java) { Online.download(pack, short) { _, _ -> } }.problem)
        val long = FakeTransport(mapOf(pack.url to bytes + byteArrayOf(0)))
        assertEquals(OnlineProblem.SIZE, assertThrows(OnlineException::class.java) { Online.download(pack, long) { _, _ -> } }.problem)
        val tampered = FakeTransport(mapOf(pack.url to bytes.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }))
        assertEquals(OnlineProblem.HASH, assertThrows(OnlineException::class.java) { Online.download(pack, tampered) { _, _ -> } }.problem)
        // The transport answers the full URL only: a URL ending the same way is not the pack.
        val elsewhere = FakeTransport(mapOf("https://other.invalid/sample-zh.tsv.gz" to bytes))
        assertEquals(OnlineProblem.NETWORK, assertThrows(OnlineException::class.java) { Online.download(pack, elsewhere) { _, _ -> } }.problem)
    }

    @Test
    fun packsDropBadLinesAndOtherLanguages() {
        val text = resource("sample-zh.tsv") + "zh\t坏\tnot pinyin\t1\nja\t東京\tとうきょう\t1\n"
        val bytes = gzip(text)
        val read = Online.read(pack("mixed", Lang.ZH, bytes), bytes)
        assertEquals(11, read.entries.size)
        assertEquals(2, read.refused)
        assertTrue(read.entries.all { it.source == Source.DOWNLOAD && it.lang == Lang.ZH })
        assertEquals(OnlineProblem.UNPACK, assertThrows(OnlineException::class.java) { Online.read(pack("x", Lang.ZH, bytes), text.toByteArray()) }.problem)
    }

    @Test
    fun noCatalogAddressUntilTheUserDecides() {
        // PLAN §2.5 decisions 2 and 10: no hosting address is invented, so nothing is downloaded.
        assertNull(Online.CATALOG_URL)
    }

    @Test
    fun samplePacksInstallEndToEnd() = runBlocking {
        val store = DictionaryStore(File(folder.root, "d"), File(folder.root, "rime"), Dispatchers.Unconfined) { 0L }
        val files = LinkedHashMap<String, ByteArray>()
        val packs = listOf(Lang.ZH, Lang.JA, Lang.EN).map { lang ->
            val bytes = gzip(resource("sample-${lang.code}.tsv"))
            pack("sample-${lang.code}", lang, bytes).also { files[it.url] = bytes }
        }
        val transport = FakeTransport(files)
        val catalog = Online.catalog(catalogJson(*packs.map { it.toJson() }.toTypedArray()))
        for (p in catalog.packs) {
            val read = Online.read(p, Online.download(p, transport) { _, _ -> })
            assertEquals(p.id, 0, read.refused)
            assertTrue(store.install(p, read.entries))
        }
        assertEquals(listOf(10, 10, 11), store.state.value.packs.map { it.count })
        // Only Chinese reaches librime; Japanese and English packs are stored, not used yet.
        val table = File(folder.root, "rime/${DictionaryFormat.PACKS_TABLE}").readLines()
        assertEquals(11, table.size)
        assertTrue("你好\tnihao\t90" in table)
    }

    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/dictionary/$name")).reader().readText()

    companion object {
        /** Gzips [text] as a pack file. */
        fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

        /** Makes the catalog entry of a pack whose file is [bytes]. */
        fun pack(id: String, lang: Lang, bytes: ByteArray) = CatalogPack(
            id = id,
            titles = mapOf("en" to "Sample $id", "zh-CN" to "样例 $id"),
            lang = lang,
            category = "sample",
            license = "GPL-3.0-or-later",
            attribution = "Kotomo test sample (original)",
            version = "1",
            size = bytes.size.toLong(),
            url = "https://packs.invalid/$id.tsv.gz",
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            format = Online.PACK_FORMAT,
        )
    }
}
