package com.yuanzhe.kotomo.backup

import android.content.Context
import com.yuanzhe.kotomo.dictionary.DictionaryStore
import com.yuanzhe.kotomo.ime.KeyboardMemory
import com.yuanzhe.kotomo.ime.MemoryFormat
import com.yuanzhe.kotomo.ime.MixedLearning
import com.yuanzhe.kotomo.settings.SettingsStore
import com.yuanzhe.kotomo.settings.Theme
import com.yuanzhe.kotomo.settings.ThemeFormat
import com.yuanzhe.kotomo.settings.ThemeLibrary
import com.yuanzhe.kotomo.settings.ThemeLibraryFormat
import com.yuanzhe.kotomo.settings.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One module of a backup next to what this device holds, for the restore preview. */
data class ModulePreview(val module: BackupModule, val inBackup: ModuleInfo, val onDeviceUtcMs: Long?) {
    /** Whether this device's data changed after the backup's: restoring it would replace newer data. */
    val deviceIsNewer: Boolean get() = onDeviceUtcMs != null && onDeviceUtcMs > inBackup.updatedUtcMs
}

/**
 * Makes and restores Kotomo's backups (PLAN §11.6) from the stores that own the data: the settings
 * file, the theme and its library and pictures, the person's dictionary, Mixed mode's learned
 * readings and the keyboard's memory.
 *
 * Notes: IO dispatcher. Every module goes through its owner's own export and import, so the
 * keyboard, which shares the process, follows a restore at once and every check of those imports
 * applies. The clipboard history is never included ([BackupModule]). A locked password-protected
 * dictionary cannot be read, so it is left out of a backup then.
 */
class Backups(context: Context) {
    private val app = context.applicationContext
    private val store = SettingsStore.get(app)
    private val library = ThemeLibrary.get(app)
    private val dictionaries = DictionaryStore.get(app)
    private val learning = MixedLearning.get(app)
    private val memory = KeyboardMemory.get(app)

    /** The modules this device can back up now (a locked dictionary cannot be read). */
    fun available(): Set<BackupModule> = BackupModule.entries.toSet() -
        if (dictionaries.state.value.locked) setOf(BackupModule.DICTIONARY) else emptySet()

    /**
     * Collects [modules] into a backup.
     * Returns: the content, with a manifest that names when each module's data last changed.
     */
    suspend fun collect(modules: Set<BackupModule>): BackupContent = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val files = LinkedHashMap<String, ByteArray>()
        val infos = LinkedHashMap<BackupModule, ModuleInfo>()
        for (m in BackupModule.entries.filter { it in modules && it in available() }) {
            val part = files(m, now)
            part.forEach { (name, bytes) -> files[m.key + "/" + name] = bytes }
            infos[m] = ModuleInfo(updated(m) ?: now, part.size, part.values.sumOf { it.size.toLong() })
        }
        BackupContent(BackupManifest(BackupFormat.VERSION, now, versionName(), infos), files)
    }

    /** Compares [content]'s modules with this device's data, for the restore preview. */
    fun preview(content: BackupContent): List<ModulePreview> =
        BackupModule.entries.mapNotNull { m -> content.manifest.modules[m]?.let { ModulePreview(m, it, updated(m)) } }

    /**
     * Restores [modules] from [content].
     * Returns: the modules that were restored (a module whose files do not read is skipped).
     * Side effects: before anything changes, the current data of the chosen modules is kept as
     * `files/backup/before-restore.kbak` so [undo] can bring it back; then each owner imports its module. The learned readings are
     * merged (a reading learned on this device since is kept); the other modules are replaced.
     */
    suspend fun restore(content: BackupContent, modules: Set<BackupModule>): Set<BackupModule> = withContext(Dispatchers.IO) {
        val chosen = modules.filter { it in content.manifest.modules }.toSet()
        writeAtomically(before, BackupFormat.pack(collect(chosen), null))
        chosen.filter { restore(it, content.of(it)) }.toSet()
    }

    /** Whether a restore can be taken back. */
    fun canUndo(): Boolean = before.isFile

    /**
     * Takes the last restore back: restores every module from the copy kept before it.
     * Side effects: deletes that copy afterwards.
     */
    suspend fun undo(): Boolean = withContext(Dispatchers.IO) {
        val bytes = runCatching { before.readBytes() }.getOrNull() ?: return@withContext false
        val content = runCatching { BackupFormat.unpack(bytes, null) }.getOrNull() ?: return@withContext false
        content.manifest.modules.keys.forEach { restore(it, content.of(it)) }
        before.delete()
        true
    }

    private val before get() = File(app.filesDir, BEFORE)

    /** Reads module [m]'s files from its owner. */
    private suspend fun files(m: BackupModule, now: Long): Map<String, ByteArray> = when (m) {
        BackupModule.SETTINGS -> mapOf(SETTINGS to store.exportRaw().toByteArray())
        BackupModule.THEMES -> buildMap {
            val theme = store.themeFlow.value ?: store.current.let { Theme(it.theme, it.keyRadiusDp) }
            put(THEME, ThemeFormat.write(theme, now).toByteArray())
            put(LIBRARY, ThemeLibraryFormat.write(library.state.value, now).toByteArray())
            val used = (library.state.value.saved.map { it.theme.image } + theme.image).filterNotNull().toSet()
            library.images.listFiles()?.filter { it.name in used }?.forEach { put("images/" + it.name, it.readBytes()) }
        }
        BackupModule.DICTIONARY -> mapOf(DICTIONARY to dictionaries.exportUser().toByteArray())
        BackupModule.LEARNED -> mapOf(LEARNED to learning.export().toByteArray())
        BackupModule.MEMORY -> mapOf(MEMORY to MemoryFormat.write(memory.current, now).toByteArray())
    }

    /** Puts module [m]'s [files] back through its owner. Returns: false when they do not read. */
    private suspend fun restore(m: BackupModule, files: Map<String, ByteArray>): Boolean = when (m) {
        BackupModule.SETTINGS -> files[SETTINGS]?.let { store.importRaw(it.toString(Charsets.UTF_8)) } ?: false
        BackupModule.THEMES -> {
            val theme = files[THEME]?.let { ThemeFormat.read(it.toString(Charsets.UTF_8)) }
            val lib = files[LIBRARY]?.let { ThemeLibraryFormat.read(it.toString(Charsets.UTF_8)) }
            if (theme == null || lib == null) {
                false
            } else {
                files.filterKeys { it.startsWith("images/") }.forEach { (name, bytes) ->
                    val file = name.removePrefix("images/")
                    if (IMAGE.matches(file)) writeAtomically(File(library.images, file), bytes)
                }
                library.update { lib }
                store.replaceTheme(theme)
                true
            }
        }
        BackupModule.DICTIONARY -> files[DICTIONARY]?.let { dictionaries.restoreUser(it.toString(Charsets.UTF_8)) } ?: false
        BackupModule.LEARNED -> files[LEARNED]?.let { learning.import(it.toString(Charsets.UTF_8)) != null } ?: false
        BackupModule.MEMORY -> files[MEMORY]?.let { bytes ->
            val read = MemoryFormat.read(bytes.toString(Charsets.UTF_8)) ?: return@let false
            memory.update { read }
            true
        } ?: false
    }

    /** When module [m]'s data on this device last changed (its files' times), or null when there is none. */
    private fun updated(m: BackupModule): Long? {
        val files = app.filesDir
        val paths = when (m) {
            BackupModule.SETTINGS -> listOf(File(files, "datastore/settings.preferences_pb"))
            BackupModule.THEMES -> listOf(File(files, "themes/current.json"), File(files, "themes/library.json"))
            BackupModule.DICTIONARY -> listOf(File(files, "dictionaries/user.json"), File(files, "dictionaries/user.kenc"))
            BackupModule.LEARNED -> listOf(File(files, "keyboard/learned.json"), File(files, "keyboard/learned.kenc"))
            BackupModule.MEMORY -> listOf(File(files, "keyboard/memory.json"))
        }
        return paths.filter { it.isFile }.maxOfOrNull { it.lastModified() }
    }

    private fun versionName(): String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    companion object {
        private const val SETTINGS = "settings.json"
        private const val THEME = "current.json"
        private const val LIBRARY = "library.json"
        private const val DICTIONARY = "user.json"
        private const val LEARNED = "learned.json"
        private const val MEMORY = "memory.json"
        private const val BEFORE = "backup/before-restore.kbak"
        private val IMAGE = Regex("[A-Za-z0-9_-]{1,64}\\.png")
    }
}
