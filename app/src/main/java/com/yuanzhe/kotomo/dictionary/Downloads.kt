package com.yuanzhe.kotomo.dictionary

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** One HTTP response a [Transport] opened: the status, the declared length (-1 unknown), a redirect target, the body. */
class Response(val code: Int, val length: Long, val location: String?, val body: InputStream, private val done: () -> Unit = {}) : Closeable {
    override fun close() {
        runCatching { body.close() }
        done()
    }
}

/**
 * Opens one HTTPS request without following redirects: Kotomo's only contact with the network
 * (PLAN §14 "联网"). Tests use [HttpsTransport] against a local HTTPS server.
 */
fun interface Transport {
    /** Requests [url] (GET); throws [IOException] when it cannot be reached. */
    fun get(url: String): Response
}

/**
 * Kotomo's transport: HTTPS only, through the platform's TLS and certificate checks.
 * Inputs: sockets replaces the TLS socket factory (tests trust their own certificate with it).
 * Notes: never follows redirects itself ([Downloader] does, to HTTPS only); sends no cookies, no
 * identifiers and nothing typed, only the request line and a fixed User-Agent.
 */
class HttpsTransport(private val sockets: SSLSocketFactory? = null) : Transport {
    override fun get(url: String): Response {
        val c = URL(url).openConnection() as? HttpsURLConnection ?: throw DownloadException(DownloadProblem.INSECURE)
        sockets?.let { c.sslSocketFactory = it }
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.setRequestProperty("User-Agent", "Kotomo")
        c.setRequestProperty("Accept-Encoding", "identity")
        val code = c.responseCode
        val body = if (code in 200..299) c.inputStream else c.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))
        return Response(code, c.contentLengthLong, c.getHeaderField("Location"), body) { c.disconnect() }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
    }
}

/** Why a download did not install. */
enum class DownloadProblem { INSECURE, NETWORK, HTTP, SIZE, HASH, UNPACK, EMPTY, STORAGE }

/** Thrown by [Downloader] with the reason. */
class DownloadException(val problem: DownloadProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/** Where an install is: downloading file [file] of [files] ([done] of [total] bytes, -1 unknown), converting, or storing. */
data class Progress(val step: Step, val file: Int = 0, val files: Int = 0, val done: Long = 0, val total: Long = -1) {
    enum class Step { DOWNLOAD, CONVERT, STORE }
}

/** What an install did: the pack, its words and the lines dropped by reason. */
data class Installed(val pack: PackInfo, val count: Int, val drops: Map<Drop, Int>)

/**
 * Downloads a dictionary straight from where it is published and converts it on the device
 * (PLAN §10.5, ADR-011): GitHub release assets checked against the SHA-256 GitHub publishes,
 * plain files protected by HTTPS only, then [DictionarySource.convert] and [DictionaryStore.install].
 *
 * Inputs: transport opens requests; api is GitHub's API root (a local server in tests); clock
 * gives UTC milliseconds.
 * Notes: blocking I/O inside suspend functions: call on the IO dispatcher. Cancellation is
 * checked between chunks and between steps; a cancelled or failed install leaves the installed
 * version untouched. Everything received is untrusted: redirects must stay on HTTPS, sizes are
 * capped while reading, digests are compared, archives are unpacked to a capped size.
 */
class Downloader(
    private val transport: Transport,
    private val api: String = "https://api.github.com",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One release asset: its download address, size, SHA-256 (lowercase hex) and update date. */
    data class Asset(val url: String, val size: Long, val sha256: String, val date: String)

    /**
     * Opens [url], following at most [MAX_REDIRECTS] redirects, each to an HTTPS address.
     * Throws [DownloadException]: INSECURE for a non-HTTPS address, NETWORK when unreachable,
     * HTTP for any status but 200.
     */
    private fun open(url: String): Response {
        var at = url
        repeat(MAX_REDIRECTS + 1) {
            if (!at.startsWith("https://")) throw DownloadException(DownloadProblem.INSECURE)
            val r = try {
                transport.get(at)
            } catch (e: DownloadException) {
                throw e
            } catch (e: IOException) {
                throw DownloadException(DownloadProblem.NETWORK, e)
            }
            if (r.code in 300..399 && r.location != null) {
                r.close()
                at = URI(at).resolve(r.location).toString()
                return@repeat
            }
            if (r.code != 200) {
                r.close()
                throw DownloadException(DownloadProblem.HTTP)
            }
            return r
        }
        throw DownloadException(DownloadProblem.HTTP)
    }

    /**
     * Finds the asset of [repo]'s latest release whose name matches [name].
     * Throws [DownloadException] HASH when GitHub lists no SHA-256 digest for it, HTTP when the
     * release or the asset is missing.
     */
    fun asset(repo: String, name: Regex): Asset {
        val text = open("$api/repos/$repo/releases/latest").use { r -> read(r, MAX_API) { _, _ -> }.toString(Charsets.UTF_8) }
        @Suppress("UNCHECKED_CAST")
        val release = runCatching { Json.parse(text) as Map<String, Any?> }.getOrNull() ?: throw DownloadException(DownloadProblem.HTTP)
        @Suppress("UNCHECKED_CAST")
        val asset = release.list("assets")?.mapNotNull { it as? Map<String, Any?> }?.firstOrNull { name.matches(it.str("name").orEmpty()) }
            ?: throw DownloadException(DownloadProblem.HTTP)
        val digest = asset.str("digest")?.removePrefix("sha256:")?.takeIf { SHA256.matches(it) } ?: throw DownloadException(DownloadProblem.HASH)
        val url = asset.str("browser_download_url")?.takeIf { it.startsWith("https://") } ?: throw DownloadException(DownloadProblem.INSECURE)
        val size = asset.long("size")?.takeIf { it in 1..MAX_DOWNLOAD } ?: throw DownloadException(DownloadProblem.SIZE)
        return Asset(url, size, digest, asset.str("updated_at")?.take(10) ?: today())
    }

    /**
     * Downloads [url] into [dest].
     * Inputs: size and sha256, when the source publishes them, must match; progress receives the
     * bytes so far and the expected total (-1 unknown).
     * Throws [DownloadException]: SIZE above [MAX_DOWNLOAD] or not the published size, HASH when
     * the digest differs, NETWORK when the connection breaks, STORAGE when [dest] cannot be written.
     */
    suspend fun fetch(url: String, dest: File, size: Long?, sha256: String?, progress: (Long, Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        open(url).use { r ->
            val total = size ?: r.length
            if (total > MAX_DOWNLOAD) throw DownloadException(DownloadProblem.SIZE)
            val out = try {
                dest.parentFile?.mkdirs()
                dest.outputStream()
            } catch (e: IOException) {
                throw DownloadException(DownloadProblem.STORAGE, e)
            }
            out.use {
                val buffer = ByteArray(CHUNK)
                var got = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = try {
                        r.body.read(buffer)
                    } catch (e: IOException) {
                        throw DownloadException(DownloadProblem.NETWORK, e)
                    }
                    if (n < 0) break
                    got += n
                    if (got > MAX_DOWNLOAD || (size != null && got > size)) throw DownloadException(DownloadProblem.SIZE)
                    digest.update(buffer, 0, n)
                    try {
                        it.write(buffer, 0, n)
                    } catch (e: IOException) {
                        throw DownloadException(DownloadProblem.STORAGE, e)
                    }
                    progress(got, total)
                }
                if ((size != null && got != size) || (r.length >= 0 && got != r.length)) throw DownloadException(DownloadProblem.SIZE)
            }
        }
        if (sha256 != null && hex(digest.digest()) != sha256) throw DownloadException(DownloadProblem.HASH)
    }

    /**
     * Downloads, converts and installs [source] into [store], working in [work] (emptied first,
     * deleted after).
     * Returns: what was installed. Throws [DownloadException] (EMPTY when no word survives the
     * checks) or [CancellationException]; the installed version stays as it was then.
     */
    suspend fun install(source: DictionarySource, store: DictionaryStore, work: File, progress: (Progress) -> Unit): Installed {
        work.deleteRecursively()
        try {
            val files = ArrayList<File>()
            var version: String? = null
            source.inputs.forEachIndexed { i, input ->
                val raw = File(work, "download-$i")
                val report = { done: Long, total: Long -> progress(Progress(Progress.Step.DOWNLOAD, i + 1, source.inputs.size, done, total)) }
                when (input) {
                    is Input.Release -> {
                        val asset = asset(input.repo, input.asset)
                        fetch(asset.url, raw, asset.size, asset.sha256, report)
                        version = version ?: asset.date
                        files.add(input.member?.let { unzip(raw, it, File(work, "file-$i")) } ?: raw)
                    }
                    is Input.Plain -> {
                        fetch(input.url, raw, null, null, report)
                        files.add(raw)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            progress(Progress(Progress.Step.CONVERT))
            val conversion = try {
                source.convert(files)
            } catch (e: IOException) {
                throw DownloadException(DownloadProblem.UNPACK, e)
            }
            if (conversion.size == 0) throw DownloadException(DownloadProblem.EMPTY)
            val entries = conversion.entries()
            currentCoroutineContext().ensureActive()
            progress(Progress(Progress.Step.STORE))
            val pack = PackInfo(
                id = source.id, titles = source.titles, lang = source.lang, category = source.category, license = source.license,
                attribution = source.attribution, version = version ?: today(), checked = source.checked,
                extra = buildMap {
                    put("license_url", source.licenseUrl)
                    source.notice?.let { put("notice", it) }
                    put("dropped", conversion.drops.entries.associate { (k, v) -> k.name.lowercase() to v.toLong() })
                },
            )
            if (!store.install(pack, entries)) throw DownloadException(DownloadProblem.STORAGE)
            return Installed(pack, conversion.size, conversion.drops.toMap())
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Unpacks [member] of the zip archive [zip] into [dest].
     * Throws [DownloadException] UNPACK when it is missing, the archive is damaged, or it unpacks
     * to more than [MAX_UNPACKED] bytes.
     */
    private suspend fun unzip(zip: File, member: String, dest: File): File {
        try {
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name != member) continue
                    dest.outputStream().use { out ->
                        val buffer = ByteArray(CHUNK)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = z.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_UNPACKED) throw DownloadException(DownloadProblem.UNPACK)
                            out.write(buffer, 0, n)
                        }
                    }
                    return dest
                }
            }
        } catch (e: IOException) {
            throw DownloadException(DownloadProblem.UNPACK, e)
        }
        throw DownloadException(DownloadProblem.UNPACK)
    }

    /** Reads a whole response of at most [max] bytes. */
    private fun read(r: Response, max: Long, progress: (Long, Long) -> Unit): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK)
        try {
            while (true) {
                val n = r.body.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > max) throw DownloadException(DownloadProblem.SIZE)
                progress(out.size().toLong(), r.length)
            }
        } catch (e: IOException) {
            throw DownloadException(DownloadProblem.NETWORK, e)
        }
        return out.toByteArray()
    }

    private fun today(): String = Instant.ofEpochMilli(clock()).atOffset(ZoneOffset.UTC).toLocalDate().toString()

    companion object {
        /** The largest file downloaded, and the largest file unpacked from an archive. */
        const val MAX_DOWNLOAD = 64L * 1024 * 1024
        const val MAX_UNPACKED = 256L * 1024 * 1024
        private const val MAX_API = 4L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val CHUNK = 64 * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Runs the settings' dictionary downloads in the background, outside any screen, so they survive
 * the page being rebuilt (dark mode, rotation) and can be cancelled (PLAN §10.5).
 *
 * Notes: one install per source at a time; [tasks] holds each source's last state until the
 * settings clear it. Nothing here runs on the key path; the keyboard sees a finished install
 * through [DictionaryStore.state].
 */
object DictionaryDownloads {
    /** One source's download: running with its progress, finished, failed or cancelled. */
    sealed interface Task {
        data class Running(val progress: Progress) : Task
        data class Done(val installed: Installed) : Task
        data class Failed(val problem: DownloadProblem) : Task
        data object Cancelled : Task
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val _tasks = MutableStateFlow<Map<String, Task>>(emptyMap())
    /** The downloads started in this process, by source id. */
    val tasks: StateFlow<Map<String, Task>> get() = _tasks

    /**
     * Starts installing [source] unless it is already running.
     * Side effects: connects to the source's hosts; writes into the app's cache and dictionary
     * files. The caller checks the person accepted the notice first ([DictionaryStore.acceptOnline]).
     */
    @Synchronized
    fun start(context: Context, source: DictionarySource) {
        if (jobs[source.id]?.isActive == true) return
        val app = context.applicationContext
        val store = DictionaryStore.get(app)
        set(source.id, Task.Running(Progress(Progress.Step.DOWNLOAD)))
        jobs[source.id] = scope.launch {
            val result = try {
                Task.Done(Downloader(HttpsTransport()).install(source, store, File(app.cacheDir, "dictionaries/${source.id}")) { set(source.id, Task.Running(it)) })
            } catch (_: CancellationException) {
                Task.Cancelled
            } catch (e: DownloadException) {
                Task.Failed(e.problem)
            }
            set(source.id, result)
        }
    }

    /** Cancels [id]'s download; the installed version stays. */
    @Synchronized
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
    }

    /** Forgets a finished task's state. */
    fun clear(id: String) = _tasks.update { if (it[id] is Task.Running) it else it - id }

    private fun set(id: String, task: Task) = _tasks.update { it + (id to task) }
}
