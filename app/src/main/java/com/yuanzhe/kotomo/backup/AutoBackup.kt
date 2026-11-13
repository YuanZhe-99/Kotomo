package com.yuanzhe.kotomo.backup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.yuanzhe.kotomo.dictionary.Json
import com.yuanzhe.kotomo.dictionary.long
import com.yuanzhe.kotomo.dictionary.str
import com.yuanzhe.kotomo.settings.readVersioned
import com.yuanzhe.kotomo.settings.writeAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The automatic backup's settings (PLAN §11.6), as `files/backup/config.json` stores them: the
 * folder the person chose, how often, how many to keep, and when the last one was made.
 * Notes: [extra] keeps unknown members. Automatic backups are never encrypted: a password cannot
 * be kept for them, and the page says so.
 */
data class AutoBackupConfig(
    val folder: String? = null,
    val intervalDays: Int = 0,
    val keep: Int = DEFAULT_KEEP,
    val lastUtcMs: Long = 0,
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** Whether automatic backups run. */
    val on: Boolean get() = folder != null && intervalDays > 0

    companion object {
        /** The automatic backups kept in the folder by default; older ones are deleted. */
        const val DEFAULT_KEEP = 5
        /** The intervals offered, in days (0: off). */
        val INTERVALS = listOf(0, 1, 7)
        val KEEP_RANGE = 1..30
    }
}

/** Maps [AutoBackupConfig] to and from JSON (pure Kotlin; unit-tested). */
internal object AutoBackupFormat {
    const val VERSION = 1
    private val KNOWN = setOf("format_version", "updated_utc_ms", "folder", "interval_days", "keep", "last_utc_ms")

    /** Reads the config; null for text that is not a JSON object. */
    fun read(text: String): AutoBackupConfig? {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: return null
        val d = AutoBackupConfig()
        return AutoBackupConfig(
            folder = o.str("folder")?.takeIf { it.startsWith("content://") },
            intervalDays = o.long("interval_days")?.toInt()?.takeIf { it in AutoBackupConfig.INTERVALS } ?: d.intervalDays,
            keep = o.long("keep")?.toInt()?.takeIf { it in AutoBackupConfig.KEEP_RANGE } ?: d.keep,
            lastUtcMs = o.long("last_utc_ms") ?: 0,
            extra = o.filterKeys { it !in KNOWN || it == "format_version" },
        )
    }

    /** Writes the config. */
    fun write(c: AutoBackupConfig, nowUtcMs: Long): String {
        val out = LinkedHashMap<String, Any?>()
        out["format_version"] = maxOf((c.extra["format_version"] as? Long)?.toInt() ?: 0, VERSION)
        out["updated_utc_ms"] = nowUtcMs
        c.folder?.let { out["folder"] = it }
        out["interval_days"] = c.intervalDays
        out["keep"] = c.keep
        out["last_utc_ms"] = c.lastUtcMs
        c.extra.forEach { (k, v) -> if (k !in KNOWN) out[k] = v }
        return Json.write(out, indent = true)
    }

    /** The name of a backup made at [utcMs]: `kotomo-auto-<UTC date and time>.kbak` or `kotomo-backup-…`. */
    fun name(utcMs: Long, auto: Boolean): String {
        val f = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return (if (auto) AUTO_PREFIX else MANUAL_PREFIX) + f.format(Date(utcMs)) + EXTENSION
    }

    /** Among [names], the automatic backups to delete so that the newest [keep] remain. */
    fun toDelete(names: List<String>, keep: Int): List<String> =
        names.filter { it.startsWith(AUTO_PREFIX) && it.endsWith(EXTENSION) }.sortedDescending().drop(keep)

    const val AUTO_PREFIX = "kotomo-auto-"
    const val MANUAL_PREFIX = "kotomo-backup-"
    const val EXTENSION = ".kbak"
}

/** One backup file in the chosen folder. */
data class BackupFile(val uri: Uri, val name: String, val bytes: Long, val modifiedUtcMs: Long)

/**
 * Runs the automatic backups (PLAN §11.6): the config, the folder the person chose (a document
 * tree they granted), the JobScheduler job and the versions kept in the folder.
 *
 * Notes: the job is not persisted across reboots (that needs a boot permission Kotomo does not
 * ask for); the keyboard and the settings app schedule it again when they start, and a backup
 * that is due runs then. Every backup holds every module that can be read, never the clipboard.
 */
object AutoBackup {
    private const val CONFIG = "backup/config.json"
    private const val JOB_ID = 0x6b6f7401

    /** Reads the config (small file; any thread). */
    fun config(context: Context): AutoBackupConfig =
        readVersioned(File(context.filesDir, CONFIG), AutoBackupFormat::read) ?: AutoBackupConfig()

    /** Writes [c] and schedules or cancels the job to match. */
    fun save(context: Context, c: AutoBackupConfig) {
        writeAtomically(File(context.filesDir, CONFIG), AutoBackupFormat.write(c, System.currentTimeMillis()))
        schedule(context)
    }

    /**
     * Schedules the job when automatic backups are on and none is pending, cancels it when off.
     * Notes: cheap; called when the keyboard and the settings app start.
     */
    fun schedule(context: Context) {
        val c = config(context)
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (!c.on) return jobs.cancel(JOB_ID)
        val period = c.intervalDays * DAY_MS
        val pending = jobs.getPendingJob(JOB_ID)
        if (pending != null && pending.intervalMillis == period) return
        jobs.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, AutoBackupJob::class.java))
                .setPeriodic(period)
                .setRequiresBatteryNotLow(true)
                .build(),
        )
    }

    /**
     * Makes one automatic backup now if it is due (or [force]), into the chosen folder, and
     * deletes the oldest automatic backups beyond the number kept.
     * Returns: false when there is no folder, it can no longer be written, or nothing was due.
     */
    suspend fun run(context: Context, force: Boolean = false): Boolean {
        val c = config(context)
        val folder = c.folder?.let(Uri::parse) ?: return false
        val now = System.currentTimeMillis()
        if (!force && (!c.on || now - c.lastUtcMs < c.intervalDays * DAY_MS - SLACK_MS)) return false
        val backups = Backups(context)
        val bytes = BackupFormat.pack(backups.collect(backups.available()), null)
        if (!write(context, folder, AutoBackupFormat.name(now, auto = true), bytes)) return false
        val files = list(context, folder)
        AutoBackupFormat.toDelete(files.map { it.name }, c.keep).forEach { name ->
            files.firstOrNull { it.name == name }?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it.uri) } }
        }
        save(context, c.copy(lastUtcMs = now))
        return true
    }

    /**
     * Writes [bytes] as a new file [name] in the document tree [folder].
     * Returns: false when the folder cannot be written (permission gone, provider failed).
     */
    fun write(context: Context, folder: Uri, name: String, bytes: ByteArray): Boolean = runCatching {
        val dir = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val doc = DocumentsContract.createDocument(context.contentResolver, dir, "application/octet-stream", name) ?: return false
        context.contentResolver.openOutputStream(doc)?.use { it.write(bytes) } != null
    }.getOrDefault(false)

    /** Lists the backups (`.kbak`) in the document tree [folder], newest first; empty when it cannot be read. */
    fun list(context: Context, folder: Uri): List<BackupFile> = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        context.contentResolver.query(children, cols, null, null, null)?.use { cur ->
            buildList {
                while (cur.moveToNext()) {
                    val name = cur.getString(1) ?: continue
                    if (!name.endsWith(AutoBackupFormat.EXTENSION)) continue
                    add(BackupFile(DocumentsContract.buildDocumentUriUsingTree(folder, cur.getString(0)), name, cur.getLong(2), cur.getLong(3)))
                }
            }.sortedByDescending { it.name.substringAfter("kotomo-").substringAfter('-') }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private const val DAY_MS = 24L * 60 * 60 * 1000
    /** A backup counts as due a little early, so a job run that comes slightly before the interval still makes one. */
    private const val SLACK_MS = 60L * 60 * 1000
}

/**
 * The JobScheduler job that makes the automatic backup ([AutoBackup.run]).
 * Notes: runs on its own coroutine on the IO dispatcher, never on the keyboard's threads.
 */
class AutoBackupJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            runCatching { AutoBackup.run(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }
}
