package com.yuanzhe.kotomo.backup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.yuanzhe.kotomo.dictionary.Json
import com.yuanzhe.kotomo.dictionary.list
import com.yuanzhe.kotomo.dictionary.long
import com.yuanzhe.kotomo.dictionary.obj
import com.yuanzhe.kotomo.dictionary.str
import com.yuanzhe.kotomo.settings.readCapped
import com.yuanzhe.kotomo.settings.readVersioned
import com.yuanzhe.kotomo.settings.writeAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/** Where sync keeps its copy (PLAN §11.7): a folder the person chose, or a WebDAV folder over HTTPS. */
enum class SyncKind { OFF, FOLDER, WEBDAV }

/**
 * The sync settings as `files/sync/config.json` stores them: where, what, whether it runs by
 * itself, the WebDAV host the person confirmed, and this device's id (for the lock).
 * Notes: off by default; the clipboard is never synced, and the learned readings and the
 * keyboard's memory (history data) only when the person adds them. The WebDAV password is not
 * here: it is kept encrypted with a Keystore key in `files/sync/password.kenc` and never enters a
 * backup or the synced files. [extra] keeps unknown members.
 */
data class SyncConfig(
    val kind: SyncKind = SyncKind.OFF,
    val folder: String? = null,
    val url: String? = null,
    val user: String = "",
    val confirmedHost: String? = null,
    val modules: Set<BackupModule> = DEFAULT_MODULES,
    val auto: Boolean = false,
    val device: String = "",
    val extra: Map<String, Any?> = emptyMap(),
) {
    companion object {
        /** What is synced unless the person changes it: settings, themes and the dictionary. */
        val DEFAULT_MODULES = setOf(BackupModule.SETTINGS, BackupModule.THEMES, BackupModule.DICTIONARY)
    }
}

/** What sync last saw of one module: this device's data time then, and the hash of the synced copy. */
data class ModuleSync(val localUtcMs: Long, val remoteSha256: String)

/**
 * The sync state as `files/sync/state.json` stores it: per module what was last synced, the
 * modules in conflict (changed on both sides) and when the last sync ran.
 */
data class SyncState(
    val modules: Map<BackupModule, ModuleSync> = emptyMap(),
    val conflicts: Set<BackupModule> = emptySet(),
    val lastUtcMs: Long = 0,
)

/** What one sync does with one module. */
enum class SyncAction { NONE, UPLOAD, DOWNLOAD, CONFLICT }

/** Why a sync could not run. */
enum class SyncProblem { NOT_SET_UP, UNCONFIRMED_HOST, BUSY, NETWORK, AUTH, FOLDER, DAMAGED }

/** A sync that could not run; [problem] says why. */
class SyncException(val problem: SyncProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/** What a sync did: the modules sent, received and left in conflict. */
data class SyncReport(val uploaded: Set<BackupModule>, val downloaded: Set<BackupModule>, val conflicts: Set<BackupModule>)

/**
 * The sync rules and formats, ported from MyApps-DATA's invariants and rewritten in Kotlin
 * (PLAN §11.7): UTC times, unknown members kept, a conflict is never resolved by itself.
 * Notes: pure Kotlin; unit-tested.
 */
internal object SyncFormat {
    const val VERSION = 1
    /** The lock a device holds while it writes; another device waits for it or for its expiry. */
    const val LOCK = "kotomo-sync-lock.json"
    const val LOCK_MS = 5L * 60 * 1000

    /** The synced file of [m]. */
    fun fileOf(m: BackupModule) = "kotomo-sync-${m.key}.kbak"

    /**
     * Decides what to do with one module.
     * Inputs: [last] is what the previous sync saw (null: never synced here); [localUtcMs] when the
     * data here last changed (null: none here); [remoteSha256] the hash of the synced copy (null:
     * none yet).
     * Returns: UPLOAD when only this device changed, DOWNLOAD when only the copy changed, CONFLICT
     * when both did (or, on the first sync, when both sides hold data), NONE otherwise.
     */
    fun decide(last: ModuleSync?, localUtcMs: Long?, remoteSha256: String?): SyncAction {
        val localChanged = localUtcMs != null && (last == null || localUtcMs > last.localUtcMs)
        val remoteChanged = remoteSha256 != null && remoteSha256 != last?.remoteSha256
        return when {
            localChanged && remoteChanged -> SyncAction.CONFLICT
            localChanged -> SyncAction.UPLOAD
            remoteChanged -> SyncAction.DOWNLOAD
            else -> SyncAction.NONE
        }
    }

    /** Whether a lock written as [text] keeps device [me] from writing at [nowUtcMs]. */
    fun lockedOut(text: String?, me: String, nowUtcMs: Long): Boolean {
        @Suppress("UNCHECKED_CAST")
        val o = text?.let { runCatching { Json.parse(it) }.getOrNull() } as? Map<String, Any?> ?: return false
        return o.str("device") != me && (o.long("until_utc_ms") ?: 0) > nowUtcMs
    }

    /** The lock device [me] writes at [nowUtcMs]. */
    fun lock(me: String, nowUtcMs: Long): String = Json.write(linkedMapOf("format_version" to VERSION, "device" to me, "until_utc_ms" to nowUtcMs + LOCK_MS))

    /** Reads the config; null for text that is not a JSON object. */
    fun readConfig(text: String): SyncConfig? {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: return null
        return SyncConfig(
            kind = o.str("kind")?.let { k -> SyncKind.entries.firstOrNull { it.name.lowercase() == k } } ?: SyncKind.OFF,
            folder = o.str("folder")?.takeIf { it.startsWith("content://") },
            url = o.str("url")?.takeIf { it.startsWith("https://") },
            user = o.str("user").orEmpty(),
            confirmedHost = o.str("confirmed_host"),
            modules = o.list("modules")?.mapNotNull { v -> BackupModule.entries.firstOrNull { it.key == v } }?.toSet() ?: SyncConfig.DEFAULT_MODULES,
            auto = o["auto"] as? Boolean ?: false,
            device = o.str("device").orEmpty(),
            extra = o.filterKeys { it !in CONFIG_KEYS || it == "format_version" },
        )
    }

    /** Writes the config. */
    fun writeConfig(c: SyncConfig, nowUtcMs: Long): String {
        val out = LinkedHashMap<String, Any?>()
        out["format_version"] = maxOf((c.extra["format_version"] as? Long)?.toInt() ?: 0, VERSION)
        out["updated_utc_ms"] = nowUtcMs
        out["kind"] = c.kind.name.lowercase()
        c.folder?.let { out["folder"] = it }
        c.url?.let { out["url"] = it }
        if (c.user.isNotEmpty()) out["user"] = c.user
        c.confirmedHost?.let { out["confirmed_host"] = it }
        out["modules"] = BackupModule.entries.filter { it in c.modules }.map { it.key }
        out["auto"] = c.auto
        out["device"] = c.device
        c.extra.forEach { (k, v) -> if (k !in CONFIG_KEYS) out[k] = v }
        return Json.write(out, indent = true)
    }

    /** Reads the state; null for text that is not a JSON object. */
    fun readState(text: String): SyncState? {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: return null
        val modules = o.obj("modules").orEmpty().mapNotNull { (k, v) ->
            val m = BackupModule.entries.firstOrNull { it.key == k } ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val e = v as? Map<String, Any?> ?: return@mapNotNull null
            val sha = e.str("remote_sha256") ?: return@mapNotNull null
            m to ModuleSync(e.long("local_utc_ms") ?: 0, sha)
        }.toMap()
        val conflicts = o.list("conflicts")?.mapNotNull { v -> BackupModule.entries.firstOrNull { it.key == v } }?.toSet().orEmpty()
        return SyncState(modules, conflicts, o.long("last_utc_ms") ?: 0)
    }

    /** Writes the state. */
    fun writeState(s: SyncState, nowUtcMs: Long): String = Json.write(
        linkedMapOf(
            "format_version" to VERSION,
            "updated_utc_ms" to nowUtcMs,
            "modules" to BackupModule.entries.filter { it in s.modules }.associate { m ->
                val e = s.modules.getValue(m)
                m.key to linkedMapOf("local_utc_ms" to e.localUtcMs, "remote_sha256" to e.remoteSha256)
            },
            "conflicts" to BackupModule.entries.filter { it in s.conflicts }.map { it.key },
            "last_utc_ms" to s.lastUtcMs,
        ),
        indent = true,
    )

    /** The lowercase hex SHA-256 of [bytes]. */
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val CONFIG_KEYS = setOf("format_version", "updated_utc_ms", "kind", "folder", "url", "user", "confirmed_host", "modules", "auto", "device")
}

/** Reads and writes the synced files at one place. */
interface Remote {
    /** Returns file [name]'s bytes, or null when there is none. */
    fun get(name: String): ByteArray?
    /** Writes file [name], replacing it. */
    fun put(name: String, bytes: ByteArray)
    /** Deletes file [name] when it is there. */
    fun delete(name: String)
}

/**
 * The synced files in a WebDAV folder over HTTPS (PLAN §11.7), with Basic authentication.
 * Notes: connects only to the host the person confirmed ([host]); redirects are not followed, so
 * a request never leaves that host. The folder must exist; only GET, PUT and DELETE are used.
 * Throws [SyncException] NETWORK, AUTH (401, 403) or FOLDER (any other failure).
 */
class WebDavRemote(private val base: String, private val host: String, user: String, password: CharArray) : Remote {
    private val auth = "Basic " + Base64.getEncoder().encodeToString("$user:${String(password)}".toByteArray())

    private fun open(name: String, method: String): HttpsURLConnection {
        val url = URL(base.trimEnd('/') + "/" + name)
        if (url.protocol != "https" || url.host != host) throw SyncException(SyncProblem.UNCONFIRMED_HOST)
        val c = url.openConnection() as? HttpsURLConnection ?: throw SyncException(SyncProblem.UNCONFIRMED_HOST)
        c.instanceFollowRedirects = false
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.requestMethod = method
        c.setRequestProperty("Authorization", auth)
        return c
    }

    private fun check(c: HttpsURLConnection, ok: (Int) -> Boolean): Int {
        val code = try { c.responseCode } catch (e: IOException) { throw SyncException(SyncProblem.NETWORK, e) }
        if (code == 401 || code == 403) throw SyncException(SyncProblem.AUTH)
        if (!ok(code)) throw SyncException(SyncProblem.FOLDER)
        return code
    }

    override fun get(name: String): ByteArray? {
        val c = open(name, "GET")
        try {
            if (check(c) { it == 200 || it == 404 } == 404) return null
            return c.inputStream.use { readCapped(it, BackupFormat.MAX_BYTES) } ?: throw SyncException(SyncProblem.DAMAGED)
        } catch (e: IOException) {
            throw SyncException(SyncProblem.NETWORK, e)
        } finally {
            c.disconnect()
        }
    }

    override fun put(name: String, bytes: ByteArray) {
        val c = open(name, "PUT")
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(bytes.size)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { it.write(bytes) }
            check(c) { it in 200..299 }
        } catch (e: IOException) {
            throw SyncException(SyncProblem.NETWORK, e)
        } finally {
            c.disconnect()
        }
    }

    override fun delete(name: String) {
        val c = open(name, "DELETE")
        try {
            check(c) { it in 200..299 || it == 404 }
        } catch (e: IOException) {
            throw SyncException(SyncProblem.NETWORK, e)
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
    }
}

/** The synced files in a document tree the person granted (a folder another app may sync). */
class FolderRemote(private val context: Context, private val tree: Uri) : Remote {
    private val resolver get() = context.contentResolver
    private val dir get() = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun find(name: String): Uri? = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            var found: Uri? = null
            while (c.moveToNext()) if (c.getString(1) == name) found = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            found
        }
    } catch (e: Exception) {
        throw SyncException(SyncProblem.FOLDER, e)
    }

    override fun get(name: String): ByteArray? {
        val uri = find(name) ?: return null
        return try {
            resolver.openInputStream(uri)?.use { readCapped(it, BackupFormat.MAX_BYTES) } ?: throw SyncException(SyncProblem.DAMAGED)
        } catch (e: IOException) {
            throw SyncException(SyncProblem.FOLDER, e)
        }
    }

    override fun put(name: String, bytes: ByteArray) {
        try {
            val uri = find(name) ?: DocumentsContract.createDocument(resolver, dir, "application/octet-stream", name) ?: throw SyncException(SyncProblem.FOLDER)
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw SyncException(SyncProblem.FOLDER)
        } catch (e: IOException) {
            throw SyncException(SyncProblem.FOLDER, e)
        } catch (e: SecurityException) {
            throw SyncException(SyncProblem.FOLDER, e)
        }
    }

    override fun delete(name: String) {
        val uri = find(name) ?: return
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }
}

/**
 * Runs sync (PLAN §11.7): the config, the state, the WebDAV password, the lock, and one sync of
 * the chosen modules with conflicts left for the person.
 *
 * Notes: IO dispatcher. Each module is synced as its own file ([SyncFormat.fileOf]), the same
 * container as a backup without a password; WebDAV uses HTTPS only. A sync takes the lock first
 * and waits (2, 4, 8 s) while another device holds it. Receiving a module goes through
 * [Backups.restore], so Undo on the Backup page can take it back.
 */
object Sync {
    private const val CONFIG = "sync/config.json"
    private const val STATE = "sync/state.json"
    private const val PASSWORD = "sync/password.kenc"
    private const val KEY_ALIAS = "kotomo-sync"
    private const val JOB_ID = 0x6b6f7402
    private val RETRY_MS = listOf(2_000L, 4_000L, 8_000L)

    /** Reads the config, giving this device an id the first time. */
    fun config(context: Context): SyncConfig {
        val c = readVersioned(File(context.filesDir, CONFIG), SyncFormat::readConfig) ?: SyncConfig()
        return if (c.device.isNotEmpty()) c else c.copy(device = UUID.randomUUID().toString()).also { saveConfig(context, it) }
    }

    /** Writes [c] and schedules or cancels the automatic sync to match. */
    fun saveConfig(context: Context, c: SyncConfig) {
        writeAtomically(File(context.filesDir, CONFIG), SyncFormat.writeConfig(c, System.currentTimeMillis()))
        schedule(context, c)
    }

    /** Reads the state. */
    fun state(context: Context): SyncState = readVersioned(File(context.filesDir, STATE), SyncFormat::readState) ?: SyncState()

    private fun saveState(context: Context, s: SyncState) = writeAtomically(File(context.filesDir, STATE), SyncFormat.writeState(s, System.currentTimeMillis()))

    /**
     * Turns the automatic sync off before a restore (PLAN §11.7), so the restored data is not
     * sent or replaced before the person looks at it.
     * Returns: whether it was on.
     */
    fun pauseAuto(context: Context): Boolean {
        val c = config(context)
        if (!c.auto) return false
        saveConfig(context, c.copy(auto = false))
        return true
    }

    /** Keeps the WebDAV [password] encrypted with a Keystore key. */
    fun savePassword(context: Context, password: CharArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(String(password).toByteArray())
        writeAtomically(File(context.filesDir, PASSWORD), sealed)
    }

    /** Reads the WebDAV password, or null when none is kept or it cannot be decrypted. */
    private fun password(context: Context): CharArray? = runCatching {
        val bytes = File(context.filesDir, PASSWORD).readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV)) }
        String(cipher.doFinal(bytes, IV, bytes.size - IV)).toCharArray()
    }.getOrNull()

    /** Deletes the kept password (sync turned off). */
    fun forgetPassword(context: Context) {
        File(context.filesDir, PASSWORD).delete()
    }

    /** Returns where [c] syncs. Throws [SyncException] NOT_SET_UP or UNCONFIRMED_HOST. */
    private fun remote(context: Context, c: SyncConfig): Remote = when (c.kind) {
        SyncKind.OFF -> throw SyncException(SyncProblem.NOT_SET_UP)
        SyncKind.FOLDER -> FolderRemote(context, Uri.parse(c.folder ?: throw SyncException(SyncProblem.NOT_SET_UP)))
        SyncKind.WEBDAV -> {
            val url = c.url ?: throw SyncException(SyncProblem.NOT_SET_UP)
            val host = runCatching { URL(url).host }.getOrNull()
            if (host == null || host != c.confirmedHost) throw SyncException(SyncProblem.UNCONFIRMED_HOST)
            WebDavRemote(url, host, c.user, password(context) ?: throw SyncException(SyncProblem.AUTH))
        }
    }

    /**
     * Syncs the chosen modules once.
     * Inputs: [resolve] decides modules in conflict the person resolved: true keeps this device's
     * data (sent over the copy), false takes the copy; [at] replaces the configured place (tests).
     * Returns: what was sent, received and is still in conflict.
     * Side effects: takes and releases the lock; writes the synced files; restores received
     * modules; records the state. A module changed on both sides is never resolved by itself.
     */
    suspend fun run(context: Context, resolve: Map<BackupModule, Boolean> = emptyMap(), at: Remote? = null): SyncReport = withContext(Dispatchers.IO) {
        val c = config(context)
        val remote = at ?: remote(context, c)
        val backups = Backups(context)
        acquire(remote, c.device)
        try {
            var state = state(context)
            val up = mutableSetOf<BackupModule>()
            val down = mutableSetOf<BackupModule>()
            val conflicts = mutableSetOf<BackupModule>()
            for (m in BackupModule.entries.filter { it in c.modules && it in backups.available() }) {
                val copy = remote.get(SyncFormat.fileOf(m))
                val copySha = copy?.let(SyncFormat::sha256)
                val decided = SyncFormat.decide(state.modules[m], backups.updated(m), copySha)
                val action = when {
                    decided == SyncAction.CONFLICT && resolve[m] == true -> SyncAction.UPLOAD
                    decided == SyncAction.CONFLICT && resolve[m] == false -> SyncAction.DOWNLOAD
                    else -> decided
                }
                when (action) {
                    SyncAction.NONE -> Unit
                    SyncAction.CONFLICT -> conflicts += m
                    SyncAction.UPLOAD -> {
                        val bytes = BackupFormat.pack(backups.collect(setOf(m)), null)
                        remote.put(SyncFormat.fileOf(m), bytes)
                        state = state.copy(modules = state.modules + (m to ModuleSync(backups.updated(m) ?: 0, SyncFormat.sha256(bytes))))
                        up += m
                    }
                    SyncAction.DOWNLOAD -> {
                        val content = try {
                            BackupFormat.unpack(copy!!, null)
                        } catch (e: BackupException) {
                            throw SyncException(SyncProblem.DAMAGED, e)
                        }
                        if (m in backups.restore(content, setOf(m))) {
                            state = state.copy(modules = state.modules + (m to ModuleSync(backups.updated(m) ?: 0, copySha!!)))
                            down += m
                        }
                    }
                }
            }
            saveState(context, state.copy(conflicts = conflicts, lastUtcMs = System.currentTimeMillis()))
            SyncReport(up, down, conflicts)
        } finally {
            runCatching { remote.delete(SyncFormat.LOCK) }
        }
    }

    /** Takes the lock, waiting while another device holds it. Throws [SyncException] BUSY. */
    private suspend fun acquire(remote: Remote, me: String) {
        for (wait in RETRY_MS + 0L) {
            val held = remote.get(SyncFormat.LOCK)?.toString(Charsets.UTF_8)
            if (!SyncFormat.lockedOut(held, me, System.currentTimeMillis())) {
                remote.put(SyncFormat.LOCK, SyncFormat.lock(me, System.currentTimeMillis()).toByteArray())
                return
            }
            if (wait == 0L) break
            delay(wait)
        }
        throw SyncException(SyncProblem.BUSY)
    }

    /** Schedules the daily automatic sync when it is on, cancels it otherwise. */
    fun schedule(context: Context, c: SyncConfig = config(context)) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (!c.auto || c.kind == SyncKind.OFF) return jobs.cancel(JOB_ID)
        if (jobs.getPendingJob(JOB_ID) != null) return
        jobs.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, AutoSyncJob::class.java))
                .setPeriodic(DAY_MS)
                .setRequiredNetworkType(if (c.kind == SyncKind.WEBDAV) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_NONE)
                .setRequiresBatteryNotLow(true)
                .build(),
        )
    }

    /** The sync password's AES-256-GCM key from the Android Keystore, created when missing. */
    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private const val IV = 12
    private const val DAY_MS = 24L * 60 * 60 * 1000
}

/** The JobScheduler job of the automatic sync; conflicts it finds wait for the person. */
class AutoSyncJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            val retry = runCatching { Sync.run(applicationContext) }.exceptionOrNull().let { (it as? SyncException)?.problem == SyncProblem.NETWORK }
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }
}
