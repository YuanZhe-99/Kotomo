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

/** One installed dictionary pack: its catalog entry, how many entries it holds, its switch, whether a previous version can be restored. */
data class InstalledPack(val meta: CatalogPack, val count: Int, val enabled: Boolean, val hasPrevious: Boolean)

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
        val version: Int = VERSION,
        val extra: Map<String, Any?> = emptyMap(),
    )

    private val ENTRY_KNOWN = setOf("language", "text", "reading", "weight", "source")

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
            version = o.long("format_version")?.toInt() ?: VERSION,
            extra = o.filterKeys { it !in setOf("format_version", "updated_utc_ms", "custom_enabled", "downloads_enabled", "packs") },
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
        },
        indent = true,
    ) + "\n"

    /**
     * Writes librime's three run-time tables from the entries that are in use (Chinese) and
     * switched on (PLAN §10.5): `custom_phrase.txt` the entries added by hand (quality 1),
     * `kotomo_packs.txt` the downloaded ones (quality 0), `kotomo_low.txt` the imported and
     * LLM-made ones (quality −1), each line `word<TAB>pinyin without spaces<TAB>weight`.
     * Returns: file name → content; an empty table is an empty file.
     */
    fun tables(user: List<Entry>, packs: List<Entry>, custom: Boolean, downloads: Boolean): Map<String, String> {
        fun table(entries: List<Entry>) = entries.filter { it.used }.joinToString("") { "${it.text}\t${it.rimeCode}\t${it.weight}\n" }
        return linkedMapOf(
            CUSTOM_TABLE to if (custom) table(user.filter { it.source == Source.MANUAL }) else "",
            PACKS_TABLE to if (downloads) table(packs) else "",
            LOW_TABLE to if (custom) table(user.filter { it.source == Source.IMPORT || it.source == Source.GENERATED }) else "",
        )
    }

    const val CUSTOM_TABLE = "custom_phrase.txt"
    const val PACKS_TABLE = "kotomo_packs.txt"
    const val LOW_TABLE = "kotomo_low.txt"
}

/**
 * Keeps the person's dictionaries (PLAN §10.5): `user.json` with the entries added by hand,
 * imported or made with an LLM (the previous version as `user.prev.json` for rollback),
 * downloaded packs under `packs/<id>/` (the previous version in `packs/<id>.prev/`), the switches
 * in `state.json`, and librime's three run-time tables in librime's user directory.
 *
 * Inputs: dir holds the dictionary files; rimeUser is librime's user directory; io runs the file
 * work; clock gives UTC milliseconds.
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(Dictionaries())
    /** The dictionaries as last loaded or changed. */
    val state: StateFlow<Dictionaries> get() = _state

    private val userFile = File(dir, "user.json")
    private val prevFile = File(dir, "user.prev.json")
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

    /**
     * Swaps `user.json` with the previous version (rollback; a second call undoes it).
     * Returns: false when read-only or there is no previous version.
     */
    suspend fun rollBack() = change {
        if (!prevFile.isFile) return@change false
        val previous = DictionaryFormat.readUser(prevFile.readText()) ?: return@change false
        if (previous.version > DictionaryFormat.VERSION) return@change false
        val current = userFile.takeIf { it.isFile }?.readText()
        writeAtomically(userFile, prevFile.readText())
        if (current != null) writeAtomically(prevFile, current) else prevFile.delete()
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
     * Installs a downloaded and checked pack: the previous version moves to `packs/<id>.prev/`.
     * Returns: false when the files cannot be written.
     */
    suspend fun install(pack: CatalogPack, entries: List<Entry>) = change(user = false) {
        val target = File(packsDir, pack.id)
        val previous = File(packsDir, "${pack.id}.prev")
        val staging = File(packsDir, "${pack.id}.new").apply { deleteRecursively(); mkdirs() }
        writeAtomically(File(staging, ENTRIES), Formats.write(entries, Format.TSV))
        writeAtomically(File(staging, META), Json.write(pack.toJson(), indent = true) + "\n")
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
                val ok = if (user && this@DictionaryStore.user.version > DictionaryFormat.VERSION) false else block()
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
        user = userFile.takeIf { it.isFile }?.let { f ->
            DictionaryFormat.readUser(f.readText()) ?: run {
                runCatching { Files.move(f.toPath(), File(dir, "user.json.unreadable").toPath(), StandardCopyOption.REPLACE_EXISTING) }
                null
            }
        } ?: DictionaryFormat.User(emptyList())
        switches = stateFile.takeIf { it.isFile }?.let { DictionaryFormat.readState(it.readText()) } ?: DictionaryFormat.State()
    }

    /** Saves the person's entries, keeping the file it replaces as `user.prev.json`. */
    private fun saveUser(next: DictionaryFormat.User): Boolean {
        dir.mkdirs()
        if (userFile.isFile) Files.copy(userFile.toPath(), prevFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        writeAtomically(userFile, DictionaryFormat.writeUser(next, clock()))
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

    /** Writes the three tables and publishes the new state. */
    private fun publish() {
        val packs = installed()
        val packEntries = packs.filter { it.enabled }.flatMap { readPack(it.meta.id) }
        rimeUser.mkdirs()
        DictionaryFormat.tables(user.entries, packEntries, switches.custom, switches.downloads).forEach { (name, text) ->
            writeAtomically(File(rimeUser, name), text)
        }
        _state.value = Dictionaries(
            entries = user.entries,
            packs = packs,
            customEnabled = switches.custom,
            downloadsEnabled = switches.downloads,
            canRollBack = prevFile.isFile,
            readOnly = user.version > DictionaryFormat.VERSION,
            generation = _state.value.generation + 1,
        )
    }

    /** Lists the installed packs with their switches. */
    private fun installed(): List<InstalledPack> = packsDir.listFiles().orEmpty()
        .filter { it.isDirectory && '.' !in it.name }
        .sortedBy { it.name }
        .mapNotNull { d ->
            @Suppress("UNCHECKED_CAST")
            val meta = runCatching { Json.parse(File(d, META).readText()) as Map<String, Any?> }.getOrNull()?.let(Online::pack) ?: return@mapNotNull null
            val count = runCatching { File(d, ENTRIES).useLines { lines -> lines.count() - 1 } }.getOrDefault(0)
            InstalledPack(meta, count, switches.packs[meta.id] ?: true, File(packsDir, "${d.name}.prev").isDirectory)
        }

    /** Reads an installed pack's entries (Kotomo's own normalized TSV). */
    private fun readPack(id: String): List<Entry> {
        val file = File(File(packsDir, id), ENTRIES)
        return runCatching { Formats.read(file.readBytes(), Format.TSV, Lang.ZH, generated = false).entries.map { it.copy(source = Source.DOWNLOAD) } }
            .getOrDefault(emptyList())
    }

    /** Writes [text] through a temporary file, synced, then an atomic rename. */
    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { it.write(text.toByteArray()); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private const val ENTRIES = "entries.tsv"
        private const val META = "pack.json"

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
