package com.yuanzhe.kotomo.dictionary

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Describes one installed dictionary pack, as `packs/<id>/pack.json` keeps it: where it came from
 * ([DictionarySource]), its license and attribution, the version installed (the release date of
 * its files, or the day it was downloaded), whether its files were checked against a published
 * checksum ([checked]; else only HTTPS protected them), and members this version does not know.
 */
data class PackInfo(
    val id: String,
    val titles: Map<String, String>,
    val lang: Lang,
    val category: String,
    val license: String,
    val attribution: String,
    val version: String,
    val checked: Boolean,
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** Whether this pack is compiled into `kotomo_packs_bin.table.bin` in background `:deploy`. */
    val compiled: Boolean get() = (extra["compiled"] as? Boolean) ?: (Sources.of(id)?.compiled == true)

    /** The pack's title in the interface [language] (`en`, `zh-CN`, `zh-TW`, `ja`), else English, else any. */
    fun title(language: String): String = titles[language] ?: titles["en"] ?: titles.values.first()

    /** The pack as `pack.json`'s object, unknown members included. */
    fun toJson(): Map<String, Any?> = LinkedHashMap<String, Any?>(extra).also {
        it["id"] = id; it["titles"] = titles; it["language"] = lang.code; it["category"] = category
        it["license"] = license; it["attribution"] = attribution; it["version"] = version; it["checked"] = checked
    }

    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        private val KNOWN = setOf("id", "titles", "language", "category", "license", "attribution", "version", "checked")

        /** Reads a `pack.json` object, or null when a member is missing or invalid (the id names a directory). */
        fun of(o: Map<String, Any?>): PackInfo? {
            val titles = o.obj("titles")?.mapNotNull { (k, v) -> (v as? String)?.takeIf { it.isNotBlank() }?.let { k to it } }?.toMap()
            return PackInfo(
                id = o.str("id")?.takeIf { ID.matches(it) } ?: return null,
                titles = titles?.takeIf { it.isNotEmpty() } ?: return null,
                lang = Lang.of(o.str("language")) ?: return null,
                category = o.str("category") ?: return null,
                license = o.str("license") ?: return null,
                attribution = o.str("attribution") ?: return null,
                version = o.str("version") ?: return null,
                checked = o["checked"] as? Boolean ?: return null,
                extra = o.filterKeys { it !in KNOWN },
            )
        }
    }
}

/** One installed dictionary pack: what it is, how many entries it holds, its switch, whether a previous version can be restored. */
data class InstalledPack(val meta: PackInfo, val count: Int, val enabled: Boolean, val hasPrevious: Boolean)

/**
 * Everything the dictionary settings show, as one immutable value.
 *
 * Notes: [entries] are the person's own (added by hand, imported, made with an LLM); [readOnly]
 * is set when `user.json` was written by a newer version, which this one never overwrites;
 * [generation] grows with every write of librime's tables.
 */
data class Dictionaries(
    val entries: List<Entry> = emptyList(),
    val packs: List<InstalledPack> = emptyList(),
    val customEnabled: Boolean = true,
    val downloadsEnabled: Boolean = true,
    val canRollBack: Boolean = false,
    val readOnly: Boolean = false,
    val onlineAccepted: Int = 0,
    /** The person's words are encrypted with their password (ADR-012). */
    val protected: Boolean = false,
    /** Protected and not unlocked in this process: the words cannot be read or changed. */
    val locked: Boolean = false,
    val generation: Long = 0,
)

/**
 * Maps the person's dictionary file `user.json` and the switches file `state.json` to and from
 * JSON (pure Kotlin, unit-tested).
 *
 * Notes: both carry `format_version` and `updated_utc_ms` (UTC) and keep members and entry fields
 * this version does not know. An entry that fails the checks of [Readings] (a file edited by hand
 * or damaged) is kept verbatim in [User.kept] and written back unchanged, never dropped.
 * Layouts: `doc/en-us/dictionaries.md`.
 */
internal object DictionaryFormat {
    const val VERSION = 1

    /** The contents of `user.json`: valid entries, entries kept verbatim, the version, other members. */
    data class User(val entries: List<Entry>, val kept: List<Any?> = emptyList(), val version: Int = VERSION, val extra: Map<String, Any?> = emptyMap())

    /** The contents of `state.json`. */
    data class State(
        val custom: Boolean = true,
        val downloads: Boolean = true,
        val packs: Map<String, Boolean> = emptyMap(),
        /** The version of the download notice the person accepted; 0 for none (PLAN §14 "联网"). */
        val onlineAccepted: Int = 0,
        /** The password protection in force, or null (ADR-012). */
        val protection: Protected? = null,
        val version: Int = VERSION,
        val extra: Map<String, Any?> = emptyMap(),
    )

    /**
     * The password protection of `state.json` (`protection`): the key's salt and rounds, and a
     * small sealed check that tells a right password from a wrong one. Never the password or the key.
     */
    data class Protected(val iterations: Int, val salt: ByteArray, val check: ByteArray)

    private val ENTRY_KNOWN = setOf("language", "text", "reading", "weight", "source")
    private val B64 = java.util.Base64.getEncoder()
    private val B64D = java.util.Base64.getDecoder()

    /** Reads `user.json`; null when the text is not a JSON object. */
    fun readUser(text: String): User? {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: return null
        val entries = ArrayList<Entry>()
        val kept = ArrayList<Any?>()
        for (raw in o.list("entries").orEmpty()) {
            @Suppress("UNCHECKED_CAST")
            val e = (raw as? Map<String, Any?>)?.let(::entry)
            if (e != null) entries.add(e) else kept.add(raw)
        }
        return User(entries, kept, o.long("format_version")?.toInt() ?: VERSION, o.filterKeys { it !in setOf("format_version", "updated_utc_ms", "entries") })
    }

    /** Reads one stored entry, or null when it fails a check. */
    private fun entry(m: Map<String, Any?>): Entry? {
        val lang = Lang.of(m.str("language")) ?: return null
        val text = Readings.text(m.str("text") ?: return null).first ?: return null
        val (reading, problem) = Readings.reading(lang, text, m.str("reading"))
        if (problem != null) return null
        val weight = m.long("weight")?.takeIf { it in 0..Weights.MAX.toLong() }?.toInt() ?: return null
        val source = Source.of(m.str("source"))?.takeIf { it != Source.DOWNLOAD } ?: return null
        return Entry(lang, text, reading, weight, source, m.filterKeys { it !in ENTRY_KNOWN })
    }

    /** Writes `user.json`; the version never goes below the one read. */
    fun writeUser(user: User, nowUtcMs: Long): String = Json.write(
        LinkedHashMap<String, Any?>().apply {
            put("format_version", maxOf(user.version, VERSION).toLong())
            put("updated_utc_ms", nowUtcMs)
            putAll(user.extra)
            put("entries", user.entries.map { e ->
                LinkedHashMap<String, Any?>().apply {
                    put("language", e.lang.code)
                    put("text", e.text)
                    e.reading?.let { put("reading", it) }
                    put("weight", e.weight.toLong())
                    put("source", e.source.key)
                    putAll(e.extra.filterKeys { it !in ENTRY_KNOWN })
                }
            } + user.kept)
        },
        indent = true,
    ) + "\n"

    /** Reads `state.json`; the defaults when it is not a JSON object. */
    fun readState(text: String): State {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: return State()
        val packs = o.obj("packs").orEmpty().mapNotNull { (id, v) -> (v as? Map<*, *>)?.get("enabled")?.let { it as? Boolean }?.let { id to it } }.toMap()
        return State(
            custom = o["custom_enabled"] as? Boolean ?: true,
            downloads = o["downloads_enabled"] as? Boolean ?: true,
            packs = packs,
            onlineAccepted = o.long("online_accepted")?.toInt()?.coerceAtLeast(0) ?: 0,
            protection = o.obj("protection")?.let { pr ->
                runCatching {
                    Protected(pr.long("iterations")!!.toInt(), B64D.decode(pr.str("salt")!!), B64D.decode(pr.str("check")!!))
                }.getOrNull()
            },
            version = o.long("format_version")?.toInt() ?: VERSION,
            extra = o.filterKeys { it !in setOf("format_version", "updated_utc_ms", "custom_enabled", "downloads_enabled", "packs", "online_accepted", "protection") },
        )
    }

    /** Writes `state.json`. */
    fun writeState(state: State, nowUtcMs: Long): String = Json.write(
        LinkedHashMap<String, Any?>().apply {
            put("format_version", maxOf(state.version, VERSION).toLong())
            put("updated_utc_ms", nowUtcMs)
            putAll(state.extra)
            put("custom_enabled", state.custom)
            put("downloads_enabled", state.downloads)
            put("packs", state.packs.mapValues { (_, on) -> mapOf("enabled" to on) })
            if (state.onlineAccepted > 0) put("online_accepted", state.onlineAccepted.toLong())
            state.protection?.let {
                put("protection", linkedMapOf("kdf" to "pbkdf2-hmac-sha256", "iterations" to it.iterations.toLong(), "salt" to B64.encodeToString(it.salt), "check" to B64.encodeToString(it.check)))
            }
        },
        indent = true,
    ) + "\n"

    /**
     * Writes two of librime's three run-time tables from the person's entries that are in use
     * (Chinese) and switched on (PLAN §10.5): `custom_phrase.txt` the entries added by hand
     * (quality 1), `kotomo_low.txt` the imported and LLM-made ones (quality −1), each line
     * `word<TAB>pinyin without spaces<TAB>weight`. The third, `kotomo_packs.txt` (downloaded,
     * quality 0), joins the enabled packs' own tables ([DictionaryStore]).
     * Returns: file name → content; an empty table is an empty file.
     */
    fun tables(user: List<Entry>, custom: Boolean): Map<String, String> = linkedMapOf(
        CUSTOM_TABLE to if (custom) table(user.filter { it.source == Source.MANUAL }) else "",
        LOW_TABLE to if (custom) table(user.filter { it.source == Source.IMPORT || it.source == Source.GENERATED }) else "",
    )

    /** One librime table: a line per entry the keyboard uses; empty for none. */
    fun table(entries: List<Entry>): String = entries.filter { it.used }.joinToString("") { "${it.text}\t${it.rimeCode}\t${it.weight}\n" }

    const val CUSTOM_TABLE = "custom_phrase.txt"
    const val PACKS_TABLE = "kotomo_packs.txt"
    const val PACKS_BIN_DICT = "kotomo_packs_bin.dict.yaml"
    const val LOW_TABLE = "kotomo_low.txt"
    const val PACKS_BIN_HEADER = "---\nname: kotomo_packs_bin\nversion: \"1\"\nsort: by_weight\ncolumns:\n  - text\n  - code\n  - weight\n...\n"
}

/**
 * Keeps the person's dictionaries (PLAN §10.5): `user.json` with the entries added by hand,
 * imported or made with an LLM (the previous version as `user.prev.json` for rollback),
 * downloaded packs under `packs/<id>/` (the previous version in `packs/<id>.prev/`), the switches
 * in `state.json`, and librime's three run-time tables in librime's user directory.
 *
 * Inputs: dir holds the dictionary files; rimeUser is librime's user directory; io runs the file
 * work; iterations are the PBKDF2 rounds for new password protection; clock gives UTC milliseconds.
 * Notes: one instance per process ([get]); the settings and the keyboard share it. Every change
 * runs on [io] under one [Mutex], writes files through a temporary file and an atomic rename,
 * rewrites the three tables and publishes a new [state]; the keyboard re-selects librime's schema
 * when [Dictionaries.generation] grows, which reloads the tables. Nothing here reaches the key
 * path. A `user.json` from a newer version makes the store read-only.
 */
class DictionaryStore(
    private val dir: File,
    private val rimeUser: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val iterations: Int = Protection.ITERATIONS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(Dictionaries())
    /** The dictionaries as last loaded or changed. */
    val state: StateFlow<Dictionaries> get() = _state

    private val userFile = File(dir, "user.json")
    private val prevFile = File(dir, "user.prev.json")
    private val sealedFile = File(dir, "user.kenc")
    private val sealedPrev = File(dir, "user.prev.kenc")
    /** The key derived from the person's password while unlocked; only in memory. */
    private var key: javax.crypto.SecretKey? = null
    private val isLocked get() = switches.protection != null && key == null
    private val stateFile = File(dir, "state.json")
    private val packsDir = File(dir, "packs")
    private var user = DictionaryFormat.User(emptyList())
    private var switches = DictionaryFormat.State()
    private var loaded = false

    /**
     * Reads the files and writes the tables once.
     * Side effects: rewrites librime's three tables from the files, so they always match them.
     */
    suspend fun load() = change(user = false) { true }

    /**
     * Adds or replaces one entry made in the settings.
     * Inputs: replacing the entry with [old]'s key (null to add).
     * Returns: false when read-only, or when another entry already has the new key.
     */
    suspend fun put(entry: Entry, old: Entry? = null) = change {
        val others = user.entries.filter { it.key != old?.key }
        if (others.any { it.key == entry.key }) return@change false
        val at = user.entries.indexOfFirst { it.key == old?.key }
        val entries = if (at < 0) user.entries + entry else user.entries.toMutableList().also { it[at] = entry }
        saveUser(user.copy(entries = entries))
    }

    /** Deletes the entry with [entry]'s key. Returns: false when read-only or absent. */
    suspend fun delete(entry: Entry) = change {
        if (user.entries.none { it.key == entry.key }) return@change false
        saveUser(user.copy(entries = user.entries.filter { it.key != entry.key }))
    }

    /**
     * Stores the entries of an import the person confirmed.
     * Inputs: replace true lets an imported entry replace an existing one with the same key;
     * false (the default) keeps the existing entry.
     * Returns: false when read-only.
     */
    suspend fun import(preview: ImportPreview, replace: Boolean = false) = change {
        val incoming = preview.entries.associateBy { it.key }
        val kept = user.entries.filter { if (replace) it.key !in incoming else true }
        val keys = kept.mapTo(HashSet()) { it.key }
        saveUser(user.copy(entries = kept + preview.entries.filter { it.key !in keys }))
    }

    /** Returns the person's list as the text of `user.json` (for a backup, PLAN §11.6). */
    suspend fun exportUser(): String = withContext(io) { DictionaryFormat.writeUser(user, System.currentTimeMillis()) }

    /**
     * Replaces the person's list with [text] from [exportUser] (a restore, PLAN §11.6).
     * Returns: false when the text is not such a list or the store is read-only (locked).
     * Notes: [text] comes from a backup file: every entry is checked as when `user.json` is read;
     * the list before the restore becomes the previous version, so Roll back undoes it.
     */
    suspend fun restoreUser(text: String): Boolean {
        val read = DictionaryFormat.readUser(text) ?: return false
        return change { saveUser(read) }
    }

    /**
     * Swaps `user.json` with the previous version (rollback; a second call undoes it).
     * Returns: false when read-only or there is no previous version.
     */
    suspend fun rollBack() = change {
        val prevText = loadText(prev = true) ?: return@change false
        val previous = DictionaryFormat.readUser(prevText) ?: return@change false
        if (previous.version > DictionaryFormat.VERSION) return@change false
        val current = loadText(prev = false)
        storeText(prev = false, prevText)
        if (current != null) storeText(prev = true, current) else currentFile(prev = true).delete()
        user = previous
        true
    }

    /** Turns the person's own entries (custom) or the downloaded packs on or off as a whole. */
    suspend fun setSources(custom: Boolean = switches.custom, downloads: Boolean = switches.downloads) = change(user = false) {
        saveState(switches.copy(custom = custom, downloads = downloads))
    }

    /** Turns one installed pack on or off. */
    suspend fun setPack(id: String, enabled: Boolean) = change(user = false) {
        saveState(switches.copy(packs = switches.packs + (id to enabled)))
    }

    /**
     * Installs a downloaded and converted pack: `entries.tsv` (the normalized TSV), its plain
     * librime table `table.txt`, spaced `dict.txt` (used by compiled packs), and `pack.json`; the
     * previous version moves to `packs/<id>.prev/`.
     * Returns: false when the files cannot be written.
     */
    suspend fun install(pack: PackInfo, entries: Sequence<Entry>) = change(user = false) {
        val target = File(packsDir, pack.id)
        val previous = File(packsDir, "${pack.id}.prev")
        val staging = File(packsDir, "${pack.id}.new").apply { deleteRecursively(); mkdirs() }
        // One pass over the entries, written as they come (a pack can hold half a million).
        var count = 0L
        File(staging, ENTRIES).bufferedWriter().use { tsv ->
            File(staging, TABLE).bufferedWriter().use { table ->
                File(staging, DICT).bufferedWriter().use { dict ->
                    tsv.write(Formats.COLUMNS.joinToString("\t", postfix = "\n"))
                    for (e in entries) {
                        count++
                        tsv.write(Formats.tsvLine(e))
                        if (e.used) {
                            table.write("${e.text}\t${e.rimeCode}\t${e.weight}\n")
                            if (e.reading != null) dict.write("${e.text}\t${e.reading}\t${e.weight}\n")
                        }
                    }
                }
            }
        }
        writeAtomically(File(staging, META), Json.write(pack.toJson() + ("count" to count), indent = true) + "\n")
        if (target.isDirectory) {
            previous.deleteRecursively()
            if (!target.renameTo(previous)) return@change false
        }
        if (!staging.renameTo(target)) return@change false
        saveState(switches.copy(packs = switches.packs + (pack.id to (switches.packs[pack.id] ?: true))))
    }

    /** Restores a pack's previous version (a second call undoes it). Returns: false without one. */
    suspend fun rollBackPack(id: String) = change(user = false) {
        val target = File(packsDir, id)
        val previous = File(packsDir, "$id.prev")
        if (!previous.isDirectory) return@change false
        val swap = File(packsDir, "$id.swap").apply { deleteRecursively() }
        target.renameTo(swap) && previous.renameTo(target) && swap.renameTo(previous)
    }

    /**
     * Turns password protection on (ADR-012): `user.json` and `user.prev.json` become
     * `user.kenc` and `user.prev.kenc`, sealed with a key derived from [password]; the plain
     * files are deleted. The store stays unlocked in this process.
     * Returns: false when already protected, read-only, or the files cannot be written.
     * Notes: the password is not kept; the caller clears its array afterwards. Slow (key derivation).
     */
    suspend fun protect(password: CharArray) = change(user = false) {
        if (switches.protection != null || user.version > DictionaryFormat.VERSION) return@change false
        val salt = Protection.salt()
        val k = Protection.derive(password, salt, iterations)
        val p = DictionaryFormat.Protected(iterations, salt, Protection.seal(CHECK, k, salt, iterations, "check"))
        val plainCurrent = userFile.takeIf { it.isFile }?.readText()
        val plainPrev = prevFile.takeIf { it.isFile }?.readText()
        plainCurrent?.let { writeAtomically(sealedFile, Protection.seal(it.toByteArray(), k, salt, iterations, "user")) }
        plainPrev?.let { writeAtomically(sealedPrev, Protection.seal(it.toByteArray(), k, salt, iterations, "user")) }
        key = k
        saveState(switches.copy(protection = p))
        userFile.delete()
        prevFile.delete()
        true
    }

    /**
     * Unlocks a protected dictionary for this process.
     * Returns: false for a wrong password (the files are untouched) or when not protected.
     * Side effects: reads the words and rewrites librime's tables from them.
     */
    suspend fun unlock(password: CharArray) = change(user = false) {
        val p = switches.protection ?: return@change false
        val k = Protection.derive(password, p.salt, p.iterations)
        try {
            Protection.open(p.check, k)
        } catch (_: SealException) {
            return@change false
        }
        key = k
        user = readUserFile()
        true
    }

    /**
     * Locks a protected dictionary: forgets the key and empties the two librime tables made from
     * the person's words, so the keyboard stops offering them until the next unlock.
     */
    suspend fun lock() = change(user = false) {
        if (switches.protection == null || key == null) return@change false
        key = null
        user = DictionaryFormat.User(emptyList())
        rimeUser.mkdirs()
        writeAtomically(File(rimeUser, DictionaryFormat.CUSTOM_TABLE), "")
        writeAtomically(File(rimeUser, DictionaryFormat.LOW_TABLE), "")
        true
    }

    /**
     * Turns password protection off: the words are written back as plain `user.json` (and the
     * previous version as `user.prev.json`), the sealed files deleted. Returns: false while locked.
     */
    suspend fun unprotect() = change(user = false) {
        if (switches.protection == null || key == null) return@change false
        val current = loadText(prev = false)
        val previous = loadText(prev = true)
        current?.let { writeAtomically(userFile, it) }
        previous?.let { writeAtomically(prevFile, it) }
        saveState(switches.copy(protection = null))
        key = null
        sealedFile.delete()
        sealedPrev.delete()
        true
    }

    /**
     * Seals an export of the person's words with their key while protected.
     * Returns: the sealed file for [text] in [format], or null when not protected (export plain).
     */
    suspend fun sealExport(text: String, format: Format): ByteArray? = withContext(io) {
        lock.withLock {
            val p = switches.protection ?: return@withLock null
            Protection.seal(text.toByteArray(), key ?: return@withLock null, p.salt, p.iterations, format.extension)
        }
    }

    /**
     * Records that the person accepted version [notice] of the download notice (PLAN §14): only
     * then do the settings start downloads.
     */
    suspend fun acceptOnline(notice: Int) = change(user = false) {
        saveState(switches.copy(onlineAccepted = notice))
    }

    /** Uninstalls a pack and its previous version. */
    suspend fun uninstall(id: String) = change(user = false) {
        File(packsDir, id).deleteRecursively()
        File(packsDir, "$id.prev").deleteRecursively()
        saveState(switches.copy(packs = switches.packs - id))
    }

    /**
     * Runs one change under the lock on [io], then rewrites the tables and publishes the state.
     * Inputs: user true for a change to `user.json`, refused while it is read-only; block returns
     * whether it changed anything.
     */
    private suspend fun change(user: Boolean = true, block: () -> Boolean): Boolean = withContext(io) {
        lock.withLock {
            // A full disk or a removed directory fails the change, never the caller.
            try {
                if (!loaded) read()
                val ok = if (user && (isLocked || this@DictionaryStore.user.version > DictionaryFormat.VERSION)) false else block()
                if (ok) publish()
                ok
            } catch (_: IOException) {
                false
            }
        }
    }

    /** Reads `user.json` and `state.json` (missing files are empty; a damaged `user.json` is moved aside). */
    private fun read() {
        loaded = true
        switches = stateFile.takeIf { it.isFile }?.let { DictionaryFormat.readState(it.readText()) } ?: DictionaryFormat.State()
        user = readUserFile()
    }

    /**
     * Reads the person's entries: `user.json`, or `user.kenc` with the key while protected (empty
     * while locked). A plain file that is not a dictionary is moved aside; a sealed one that does
     * not open stays as it is (a wrong key never destroys it).
     */
    private fun readUserFile(): DictionaryFormat.User {
        if (switches.protection != null) {
            val text = try {
                loadText(prev = false)
            } catch (e: SealException) {
                // A file from a newer version is never overwritten: read-only, like a newer user.json.
                if (e.problem == SealProblem.NEWER) return DictionaryFormat.User(emptyList(), version = DictionaryFormat.VERSION + 1)
                null
            } ?: return DictionaryFormat.User(emptyList())
            return DictionaryFormat.readUser(text) ?: DictionaryFormat.User(emptyList())
        }
        return userFile.takeIf { it.isFile }?.let { f ->
            DictionaryFormat.readUser(f.readText()) ?: run {
                runCatching { Files.move(f.toPath(), File(dir, "user.json.unreadable").toPath(), StandardCopyOption.REPLACE_EXISTING) }
                null
            }
        } ?: DictionaryFormat.User(emptyList())
    }

    /** The file that holds the current ([prev] false) or previous entries in the mode in force. */
    private fun currentFile(prev: Boolean): File = when {
        switches.protection != null -> if (prev) sealedPrev else sealedFile
        else -> if (prev) prevFile else userFile
    }

    /** Reads the current or previous entries' text; null when there is none or it is locked. */
    private fun loadText(prev: Boolean): String? {
        val f = currentFile(prev).takeIf { it.isFile } ?: return null
        if (switches.protection == null) return f.readText()
        val k = key ?: return null
        return Protection.open(f.readBytes(), k).toString(Charsets.UTF_8)
    }

    /** Writes the current or previous entries' text, sealed while protected. */
    private fun storeText(prev: Boolean, text: String) {
        val p = switches.protection
        val f = currentFile(prev)
        if (p == null) writeAtomically(f, text) else writeAtomically(f, Protection.seal(text.toByteArray(), key!!, p.salt, p.iterations, "user"))
    }

    /** Saves the person's entries, keeping the file it replaces as `user.prev.json`. */
    private fun saveUser(next: DictionaryFormat.User): Boolean {
        dir.mkdirs()
        val current = currentFile(prev = false)
        if (current.isFile) Files.copy(current.toPath(), currentFile(prev = true).toPath(), StandardCopyOption.REPLACE_EXISTING)
        storeText(prev = false, DictionaryFormat.writeUser(next, clock()))
        user = next
        return true
    }

    /** Saves the switches. */
    private fun saveState(next: DictionaryFormat.State): Boolean {
        dir.mkdirs()
        writeAtomically(stateFile, DictionaryFormat.writeState(next, clock()))
        switches = next
        return true
    }

    /**
     * Writes the three tables and publishes the new state.
     * Notes: the packs' table is joined from each enabled pack's `table.txt` by copying files, so
     * a change never parses hundreds of thousands of pack lines again.
     */
    private fun publish() {
        val packs = installed()
        rimeUser.mkdirs()
        // Locked: the tables written at the last unlock stay, so the keyboard keeps the words
        // (ADR-012); Lock empties them on purpose.
        if (!isLocked) {
            DictionaryFormat.tables(user.entries, switches.custom).forEach { (name, text) ->
                writeAtomically(File(rimeUser, name), text)
            }
        }
        val joined = File(rimeUser, DictionaryFormat.PACKS_TABLE + ".tmp")
        FileOutputStream(joined).use { out ->
            if (switches.downloads) {
                packs.filter { it.enabled && !it.meta.compiled }.forEach { p -> File(File(packsDir, p.meta.id), TABLE).takeIf { it.isFile }?.inputStream()?.use { it.copyTo(out) } }
            }
            out.fd.sync()
        }
        Files.move(joined.toPath(), File(rimeUser, DictionaryFormat.PACKS_TABLE).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        val compiledDict = File(rimeUser, DictionaryFormat.PACKS_BIN_DICT)
        val compiledEnabled = if (switches.downloads) packs.filter { it.enabled && it.meta.compiled } else emptyList()
        if (compiledEnabled.isEmpty()) {
            compiledDict.delete()
        } else {
            val tmp = File(rimeUser, DictionaryFormat.PACKS_BIN_DICT + ".tmp")
            FileOutputStream(tmp).use { raw ->
                raw.write(DictionaryFormat.PACKS_BIN_HEADER.toByteArray())
                for (p in compiledEnabled) {
                    val dir = File(packsDir, p.meta.id)
                    val dict = File(dir, DICT)
                    if (dict.isFile) {
                        dict.inputStream().use { it.copyTo(raw) }
                    } else {
                        File(dir, ENTRIES).takeIf { it.isFile }?.useLines { lines ->
                            val w = raw.bufferedWriter()
                            lines.drop(1).forEach { line ->
                                val c = line.split('\t')
                                if (c.size >= 4 && c[0] == Lang.ZH.code && c[2].isNotEmpty()) {
                                    w.write("${c[1]}\t${c[2]}\t${c[3]}\n")
                                }
                            }
                            w.flush()
                        }
                    }
                }
                raw.fd.sync()
            }
            Files.move(tmp.toPath(), compiledDict.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        _state.value = Dictionaries(
            entries = user.entries,
            packs = packs,
            customEnabled = switches.custom,
            downloadsEnabled = switches.downloads,
            canRollBack = prevFile.isFile,
            readOnly = user.version > DictionaryFormat.VERSION,
            protected = switches.protection != null,
            locked = isLocked,
            onlineAccepted = switches.onlineAccepted,
            generation = _state.value.generation + 1,
        )
    }

    /**
     * Returns pack [id]'s librime table (`text\tcode\tweight` lines) while it is installed and on
     * and downloaded dictionaries are on, otherwise null (idiom completion, PLAN §10.8).
     */
    fun packTable(id: String): File? {
        val s = _state.value
        if (!s.downloadsEnabled || s.packs.none { it.meta.id == id && it.enabled }) return null
        return File(File(packsDir, id), TABLE).takeIf { it.isFile }
    }

    /** Lists the installed packs with their switches. */
    private fun installed(): List<InstalledPack> = packsDir.listFiles().orEmpty()
        .filter { it.isDirectory && '.' !in it.name }
        .sortedBy { it.name }
        .mapNotNull { d ->
            @Suppress("UNCHECKED_CAST")
            val meta = runCatching { Json.parse(File(d, META).readText()) as Map<String, Any?> }.getOrNull()?.let(PackInfo::of) ?: return@mapNotNull null
            val count = meta.extra.long("count")?.toInt() ?: runCatching { File(d, ENTRIES).useLines { lines -> lines.count() - 1 } }.getOrDefault(0)
            InstalledPack(meta, count, switches.packs[meta.id] ?: true, File(packsDir, "${d.name}.prev").isDirectory)
        }

    /** Writes [text] through a temporary file, synced, then an atomic rename. */
    private fun writeAtomically(file: File, text: String) = writeAtomically(file, text.toByteArray())

    /** Writes [bytes] through a temporary file, synced, then an atomic rename. */
    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private const val ENTRIES = "entries.tsv"
        private const val META = "pack.json"
        private const val TABLE = "table.txt"
        private const val DICT = "dict.txt"
        /** The text sealed as the password check. */
        private val CHECK = "kotomo-dictionary-check".toByteArray()

        @Volatile private var instance: DictionaryStore? = null

        /**
         * Returns the process's store, creating it on first use.
         * Side effects: the first call starts [load] on a background thread.
         * Notes: the files live in credential-protected storage (`files/dictionaries/`); the
         * tables in librime's user directory (`files/rime/user/`), which the keyboard opens only
         * after the first unlock.
         */
        fun get(context: Context): DictionaryStore = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                DictionaryStore(File(app.filesDir, "dictionaries"), File(app.filesDir, "rime/user")).also { store ->
                    instance = store
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { store.load() }
                }
            }
        }
    }
}
