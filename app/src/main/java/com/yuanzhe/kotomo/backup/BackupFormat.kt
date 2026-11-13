package com.yuanzhe.kotomo.backup

import com.yuanzhe.kotomo.dictionary.Json
import com.yuanzhe.kotomo.dictionary.Protection
import com.yuanzhe.kotomo.dictionary.long
import com.yuanzhe.kotomo.dictionary.obj
import com.yuanzhe.kotomo.dictionary.str
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Names the parts of a backup that can be restored one by one (PLAN §11.6), with the folder its
 * files take inside the backup.
 * Notes: the clipboard history is never part of a backup: it is encrypted with a key that never
 * leaves the device, and it is the most sensitive data Kotomo keeps (PLAN §14). The names are
 * stored, so renaming one is a format change.
 */
enum class BackupModule(val key: String) {
    SETTINGS("settings"),
    THEMES("themes"),
    DICTIONARY("dictionary"),
    LEARNED("learned"),
    MEMORY("memory"),
}

/** What a backup says about one of its modules: when its data was last changed and its size. */
data class ModuleInfo(val updatedUtcMs: Long, val files: Int, val bytes: Long)

/**
 * The manifest at the top of every backup: its format, when and by which version it was made,
 * and its modules.
 * Notes: [extra] keeps members a newer version wrote.
 */
data class BackupManifest(
    val formatVersion: Int,
    val createdUtcMs: Long,
    val appVersion: String,
    val modules: Map<BackupModule, ModuleInfo>,
    val extra: Map<String, Any?> = emptyMap(),
)

/** A backup's manifest and its files by path (`<module>/<name>`). */
data class BackupContent(val manifest: BackupManifest, val files: Map<String, ByteArray>) {
    /** Returns the files of [module], by their name inside it. */
    fun of(module: BackupModule): Map<String, ByteArray> {
        val prefix = module.key + "/"
        return files.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    }
}

/** Why a backup file could not be read. */
enum class BackupProblem { NOT_A_BACKUP, NEWER, TOO_LARGE, WRONG_PASSWORD, NEEDS_PASSWORD }

/** A backup that could not be read; [problem] says why. */
class BackupException(val problem: BackupProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/**
 * Packs a backup into its file and reads one back: a ZIP (compressed) of `manifest.json` and the
 * modules' files, optionally sealed with a password (AES-256-GCM with a PBKDF2 key, the same
 * container as a protected dictionary, ADR-012).
 *
 * Notes: pure Kotlin; unit-tested. Reading is a true boundary: the file may come from anywhere,
 * so entry names, counts and sizes are checked and nothing is trusted. The layout is documented in
 * `doc/en-us/backup.md`.
 */
object BackupFormat {
    /** The format version this build writes. */
    const val VERSION = 1
    /** The file name of the manifest inside the ZIP. */
    const val MANIFEST = "manifest.json"
    /** What a sealed backup's header names as its content. */
    const val KIND = "backup"
    /** The largest backup read, in bytes, before and after unpacking. */
    const val MAX_BYTES = 64L shl 20
    /** The most files a backup may hold. */
    const val MAX_FILES = 2000
    private val NAME = Regex("[a-z]+/[A-Za-z0-9._-]{1,96}(/[A-Za-z0-9._-]{1,96})?")

    /**
     * Writes [content] as a backup file.
     * Inputs: [password] seals it when not null (the derivation takes about a second).
     */
    fun pack(content: BackupContent, password: CharArray?): ByteArray {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry(MANIFEST)); z.write(writeManifest(content.manifest).toByteArray()); z.closeEntry()
                content.files.toSortedMap().forEach { (path, bytes) -> z.putNextEntry(ZipEntry(path)); z.write(bytes); z.closeEntry() }
            }
        }.toByteArray()
        if (password == null) return zip
        val salt = Protection.salt()
        return Protection.seal(zip, Protection.derive(password, salt, Protection.ITERATIONS), salt, Protection.ITERATIONS, KIND)
    }

    /** Whether [bytes] are a sealed backup (it then needs its password). */
    fun sealed(bytes: ByteArray): Boolean = Protection.isSealed(bytes)

    /**
     * Reads a backup file.
     * Inputs: [password] for a sealed one.
     * Throws [BackupException]: NEEDS_PASSWORD for a sealed file without one, WRONG_PASSWORD,
     * NEWER for a newer format, TOO_LARGE, NOT_A_BACKUP for anything else.
     */
    fun unpack(bytes: ByteArray, password: CharArray?): BackupContent {
        if (bytes.size > MAX_BYTES) throw BackupException(BackupProblem.TOO_LARGE)
        val zip = if (Protection.isSealed(bytes)) {
            if (password == null) throw BackupException(BackupProblem.NEEDS_PASSWORD)
            try {
                if (Protection.header(bytes).kind != KIND) throw BackupException(BackupProblem.NOT_A_BACKUP)
                Protection.open(bytes, password)
            } catch (e: com.yuanzhe.kotomo.dictionary.SealException) {
                throw BackupException(
                    when (e.problem) {
                        com.yuanzhe.kotomo.dictionary.SealProblem.WRONG_PASSWORD -> BackupProblem.WRONG_PASSWORD
                        com.yuanzhe.kotomo.dictionary.SealProblem.NEWER -> BackupProblem.NEWER
                        com.yuanzhe.kotomo.dictionary.SealProblem.DAMAGED -> BackupProblem.NOT_A_BACKUP
                    },
                    e,
                )
            }
        } else {
            bytes
        }
        val files = LinkedHashMap<String, ByteArray>()
        var manifest: BackupManifest? = null
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val entry = z.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (name != MANIFEST && (!NAME.matches(name) || ".." in name)) throw BackupException(BackupProblem.NOT_A_BACKUP)
                    if (files.size >= MAX_FILES) throw BackupException(BackupProblem.TOO_LARGE)
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw BackupException(BackupProblem.TOO_LARGE)
                        out.write(buf, 0, n)
                    }
                    if (name == MANIFEST) manifest = readManifest(out.toString(Charsets.UTF_8.name())) else files[name] = out.toByteArray()
                }
            }
        } catch (e: IOException) {
            throw BackupException(BackupProblem.NOT_A_BACKUP, e)
        }
        val m = manifest ?: throw BackupException(BackupProblem.NOT_A_BACKUP)
        return BackupContent(m, files)
    }

    /** Writes the manifest's JSON. */
    fun writeManifest(m: BackupManifest): String {
        val out = LinkedHashMap<String, Any?>()
        out["format_version"] = maxOf(m.formatVersion, VERSION)
        out["created_utc_ms"] = m.createdUtcMs
        out["app_version"] = m.appVersion
        out["modules"] = BackupModule.entries.filter { it in m.modules }.associate { mod ->
            val i = m.modules.getValue(mod)
            mod.key to linkedMapOf("updated_utc_ms" to i.updatedUtcMs, "files" to i.files, "bytes" to i.bytes)
        }
        m.extra.forEach { (k, v) -> if (k !in KNOWN) out[k] = v }
        return Json.write(out, indent = true)
    }

    /**
     * Reads the manifest.
     * Throws [BackupException]: NEWER when a newer format wrote it, NOT_A_BACKUP when it is not one.
     */
    fun readManifest(text: String): BackupManifest {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: throw BackupException(BackupProblem.NOT_A_BACKUP)
        val version = o.long("format_version")?.toInt() ?: throw BackupException(BackupProblem.NOT_A_BACKUP)
        if (version > VERSION) throw BackupException(BackupProblem.NEWER)
        val modules = o.obj("modules").orEmpty().mapNotNull { (k, v) ->
            val mod = BackupModule.entries.firstOrNull { it.key == k } ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val i = v as? Map<String, Any?> ?: return@mapNotNull null
            mod to ModuleInfo(i.long("updated_utc_ms") ?: 0, (i.long("files") ?: 0).toInt(), i.long("bytes") ?: 0)
        }.toMap()
        return BackupManifest(version, o.long("created_utc_ms") ?: 0, o.str("app_version").orEmpty(), modules, o.filterKeys { it !in KNOWN })
    }

    private val KNOWN = setOf("format_version", "created_utc_ms", "app_version", "modules")
    private const val BUFFER = 64 * 1024
}
