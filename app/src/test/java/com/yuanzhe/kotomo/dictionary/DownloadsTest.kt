package com.yuanzhe.kotomo.dictionary

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Checks downloading a dictionary from its source (T17-download, PLAN §10.5, ADR-011) against a
 * local HTTPS server with its own certificate, through Kotomo's real [HttpsTransport]: GitHub
 * release assets checked against their published digest, plain files over HTTPS only, sizes,
 * redirects, cancellation and damaged data, and that a failed download leaves the installed
 * version untouched.
 */
class DownloadsTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: HttpsServer
    private lateinit var transport: HttpsTransport
    private val routes = HashMap<String, (HttpExchange) -> Unit>()
    private val base get() = "https://localhost:${server.address.port}"

    @Before
    fun setUp() {
        val ks = File(folder.root, "test.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.path,
            "-storepass", PASS, "-keypass", PASS,
        ).redirectErrorStream(true).start()
        assertEquals(0, p.waitFor())
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, PASS.toCharArray()) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, PASS.toCharArray()) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val serverTls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val clientTls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        server = HttpsServer.create(InetSocketAddress("localhost", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(serverTls)
            createContext("/") { ex -> (routes[ex.requestURI.path] ?: { it.sendResponseHeaders(404, -1) })(ex); ex.close() }
            start()
        }
        transport = HttpsTransport(clientTls.socketFactory)
    }

    @After
    fun tearDown() = server.stop(0)

    private fun serve(path: String, bytes: ByteArray, length: Long = bytes.size.toLong()) {
        routes[path] = { it.sendResponseHeaders(200, length); it.responseBody.write(bytes) }
    }

    private fun redirect(path: String, to: String) {
        routes[path] = { it.responseHeaders.add("Location", to); it.sendResponseHeaders(302, -1) }
    }

    private fun zip(member: String, text: String): ByteArray = ByteArrayOutputStream().also { b ->
        ZipOutputStream(b).use { z -> z.putNextEntry(ZipEntry(member)); z.write(text.toByteArray()); z.closeEntry() }
    }.toByteArray()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Publishes a release of o/r whose one asset is [bytes] (digest and size as given, else the true ones). */
    private fun release(bytes: ByteArray, digest: String? = "sha256:" + sha(bytes), size: Long = bytes.size.toLong()) {
        serve("/files/cn.zip", bytes)
        val asset = buildMap {
            put("name", "cn.zip"); put("size", size); put("browser_download_url", "$base/files/cn.zip"); put("updated_at", "2026-11-10T06:00:00Z")
            digest?.let { put("digest", it) }
        }
        serve("/api/repos/o/r/releases/latest", Json.write(mapOf("tag_name" to "nightly", "assets" to listOf(asset))).toByteArray())
    }

    private fun source(vararg inputs: Input, format: SourceFormat = SourceFormat.RIME) =
        DictionarySource("test-pack", mapOf("en" to "Test"), Lang.ZH, "test", "GPL-3.0", "https://example.invalid/license", "test", inputs.toList(), format)

    private val released = source(Input.Release("o/r", Regex("cn\\.zip"), "cn/base.dict.yaml"))
    private val words = "---\n...\n蔻拓摩\tkou tuo mo\t100\n寇拓\tkou tuo\t10\n"

    private fun store() = DictionaryStore(File(folder.root, "dict"), File(folder.root, "rime"), Dispatchers.Unconfined) { NOW }
    private fun downloader() = Downloader(transport, "$base/api") { NOW }
    private fun work() = File(folder.root, "work")

    private fun problem(store: DictionaryStore = store(), source: DictionarySource = released) = runBlocking {
        assertThrows(DownloadException::class.java) { runBlocking { downloader().install(source, store, work()) {} } }.problem
    }

    @Test
    fun aReleaseAssetIsCheckedConvertedAndInstalled() = runBlocking {
        release(zip("cn/base.dict.yaml", words))
        val store = store()
        val steps = ArrayList<Progress.Step>()
        val done = downloader().install(released, store, work()) { steps.add(it.step) }
        assertEquals(2, done.count)
        assertEquals(listOf(Progress.Step.DOWNLOAD, Progress.Step.CONVERT, Progress.Step.STORE), steps.distinct())
        val pack = store.state.value.packs.single().meta
        assertTrue("a published digest was compared", pack.checked)
        assertEquals("2026-11-10", pack.version)
        assertEquals("蔻拓摩\tkoutuomo\t100\n寇拓\tkoutuo\t10\n", File(folder.root, "rime/${DictionaryFormat.PACKS_TABLE}").readText())
        assertFalse("the work directory is removed", work().exists())
    }

    @Test
    fun aPlainFileIsProtectedByHttpsOnly() = runBlocking {
        serve("/raw/list.txt", "拓展\t 30\n".toByteArray())
        serve("/raw/8105.dict.yaml", "---\n...\n拓\ttuo\t1\n展\tzhan\t1\n".toByteArray())
        val plain = source(Input.Plain("$base/raw/list.txt"), Input.Plain("$base/raw/8105.dict.yaml"), format = SourceFormat.THUOCL)
        val store = store()
        downloader().install(plain, store, work()) {}
        val pack = store.state.value.packs.single().meta
        assertFalse("no checksum is published", pack.checked)
        assertEquals("2027-01-15", pack.version) // the day of the download (NOW, UTC)
    }

    @Test
    fun aDifferentDigestOrSizeIsRefusedAndTheInstalledVersionStays() {
        val store = store()
        release(zip("cn/base.dict.yaml", words))
        runBlocking { downloader().install(released, store, work()) {} }
        val good = File(folder.root, "rime/${DictionaryFormat.PACKS_TABLE}").readText()
        val other = zip("cn/base.dict.yaml", "---\n...\n恶意\te yi\t100\n")
        release(other, digest = "sha256:" + sha(zip("cn/base.dict.yaml", words)))
        assertEquals(DownloadProblem.HASH, problem(store))
        release(other, size = other.size + 10L)
        assertEquals(DownloadProblem.SIZE, problem(store))
        release(other, digest = null)
        assertEquals("a release without a published digest is refused", DownloadProblem.HASH, problem(store))
        assertEquals(good, File(folder.root, "rime/${DictionaryFormat.PACKS_TABLE}").readText())
        assertFalse(store.state.value.packs.single().hasPrevious)
    }

    @Test
    fun onlyHttpsIsFollowed() {
        assertEquals(DownloadProblem.INSECURE, problem(source = source(Input.Plain("http://localhost/raw/list.txt"))))
        redirect("/raw/moved", "http://localhost:${server.address.port}/raw/list.txt")
        assertEquals("a redirect to plain HTTP", DownloadProblem.INSECURE, problem(source = source(Input.Plain("$base/raw/moved"))))
        serve("/raw/list.dict.yaml", words.toByteArray())
        redirect("/raw/https", "$base/raw/list.dict.yaml")
        runBlocking { downloader().install(source(Input.Plain("$base/raw/https")), store(), work()) {} }
        assertEquals("a missing file", DownloadProblem.HTTP, problem(source = source(Input.Plain("$base/raw/none"))))
    }

    @Test
    fun damagedDataIsRefused() {
        release("not a zip".toByteArray())
        assertEquals(DownloadProblem.UNPACK, problem())
        release(zip("other/file.txt", words))
        assertEquals("the member is missing", DownloadProblem.UNPACK, problem())
        release(zip("cn/base.dict.yaml", "---\n...\nB站\tB zhan\t1\n"))
        assertEquals("no line survives the checks", DownloadProblem.EMPTY, problem())
        // The server announces 100 bytes more than it sends and closes. The client sees a failed read
        // or a clean early end, depending on how the TLS connection closes; both must be NETWORK.
        // A body that matches its own length but not the published size is SIZE
        // (aDifferentDigestOrSizeIsRefusedAndTheInstalledVersionStays).
        serve("/raw/short", words.toByteArray(), length = words.toByteArray().size + 100L)
        assertEquals("a connection that ends before the announced length", DownloadProblem.NETWORK, problem(source = source(Input.Plain("$base/raw/short"))))
        val archive = zip("cn/base.dict.yaml", words)
        release(archive)
        serve("/files/cn.zip", archive, length = archive.size + 100L)
        assertEquals("a release asset cut short is a network failure, not a size mismatch", DownloadProblem.NETWORK, problem())
    }

    @Test
    fun cancellingStopsTheDownloadAndKeepsNothing() = runBlocking {
        val started = CompletableDeferred<Unit>()
        routes["/raw/slow"] = { ex ->
            ex.sendResponseHeaders(200, 0)
            runCatching {
                repeat(200) { ex.responseBody.write(ByteArray(64 * 1024)); ex.responseBody.flush(); Thread.sleep(20) }
            }
        }
        val store = store()
        val job = async(Dispatchers.IO) {
            downloader().install(source(Input.Plain("$base/raw/slow")), store, work()) { if (it.done > 0) started.complete(Unit) }
        }
        started.await()
        job.cancel()
        assertThrows(CancellationException::class.java) { runBlocking { job.await() } }
        assertTrue(store.state.value.packs.isEmpty())
        assertFalse(work().exists())
    }

    private companion object {
        const val PASS = "kotomo-test"
        const val NOW = 1_800_000_000_000L // 2027-01-15 UTC
    }
}
