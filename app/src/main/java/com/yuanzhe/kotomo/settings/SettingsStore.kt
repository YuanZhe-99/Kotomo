package com.yuanzhe.kotomo.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.yuanzhe.kotomo.dictionary.Json
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Keeps the person's [Settings] in a Preferences DataStore file, the theme in its JSON file, and
 * offers them together as a [StateFlow].
 *
 * Inputs: data is the DataStore holding the file; scope runs the reading and the writes; themes is
 * the theme file (null keeps the theme in the Preferences file, as tests do).
 * Notes: until the theme file exists (an install from before it), the theme is read from the
 * Preferences file; the first theme change writes the file, and the old keys stay untouched there.
 * Notes: one instance per process ([get]); the settings app and the keyboard share it. [current]
 * never touches the disk, so the keyboard may read it on the main thread; until the file's first
 * read completes (a few milliseconds after [get]) it holds the defaults.
 */
class SettingsStore(private val data: DataStore<Preferences>, private val scope: CoroutineScope, private val themes: ThemeFile? = null) {
    /** The theme file's content, or null while there is none (or no file is used). */
    private val theme = MutableStateFlow<Theme?>(null)
    private val themeLoaded = scope.launch { theme.value = themes?.load() }

    /**
     * Emits the current theme with everything the editor sets (PLAN §11.4); null until the theme
     * file exists, when the theme is the settings' base and key radius.
     */
    val themeFlow: StateFlow<Theme?> get() = theme

    /**
     * Emits the settings each time a file changes; null until the file's first read, so a reader
     * that must not act on the defaults (the fuzzy-pinyin deploy) can wait for the stored values.
     */
    val loaded: StateFlow<Settings?> = combine(data.data.map(SettingsFormat::read), theme) { s, t -> s.with(t) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Emits the settings each time a file changes; starts with the defaults. */
    val flow: StateFlow<Settings> = loaded.map { it ?: Settings() }.stateIn(scope, SharingStarted.Eagerly, Settings())

    /** Returns the latest settings read from the file, without I/O. */
    val current: Settings get() = flow.value

    /**
     * Applies [transform] to the stored settings and writes what changed.
     * Side effects: rewrites the settings file when anything changed; [flow] then emits.
     * Notes: suspends until the write is durable; unknown keys in the file are kept.
     */
    suspend fun update(transform: (Settings) -> Settings) {
        themeLoaded.join()
        data.updateData { prefs ->
            val stored = SettingsFormat.read(prefs)
            val t = theme.value
            val old = stored.with(t)
            val new = transform(old)
            val now = System.currentTimeMillis()
            val kept = if (themes == null) {
                new
            } else {
                if (new.theme != old.theme || new.keyRadiusDp != old.keyRadiusDp) {
                    val next = (t ?: Theme()).copy(base = new.theme, keyRadiusDp = new.keyRadiusDp)
                    themes.save(next, now)
                    theme.value = next
                }
                // The theme lives in its own file: the Preferences file keeps what it had.
                new.copy(theme = stored.theme, keyRadiusDp = stored.keyRadiusDp)
            }
            if (kept == stored) prefs else prefs.toMutablePreferences().also { SettingsFormat.write(it, stored, kept, now) }
        }
    }

    /**
     * Applies [transform] to the current theme and writes the theme file (PLAN §11.4).
     * Side effects: [themeFlow] and [flow] emit; the keyboard repaints.
     * Notes: without a theme file (tests), only the base and key radius are kept, in the settings.
     */
    suspend fun updateTheme(transform: (Theme) -> Theme) {
        themeLoaded.join()
        if (themes == null) {
            update { s -> transform(Theme(s.theme, s.keyRadiusDp)).let { s.copy(theme = it.base, keyRadiusDp = it.keyRadiusDp) } }
            return
        }
        data.updateData { prefs ->
            val stored = SettingsFormat.read(prefs)
            val old = theme.value ?: Theme(stored.theme, stored.keyRadiusDp)
            val next = transform(old)
            if (next != old || theme.value == null) {
                themes.save(next, System.currentTimeMillis())
                theme.value = next
            }
            prefs
        }
    }

    /**
     * Returns every value of the settings file, typed, as JSON (for a backup, PLAN §11.6).
     * Notes: unknown keys (a newer version's) are included.
     */
    suspend fun exportRaw(): String {
        val prefs = data.data.first()
        val out = sortedMapOf<String, Any?>()
        prefs.asMap().forEach { (k, v) ->
            val (type, value) = when (v) {
                is Boolean -> "boolean" to v
                is Int -> "int" to v.toLong()
                is Long -> "long" to v
                is Float -> "float" to v.toDouble()
                is Double -> "double" to v
                is String -> "string" to v
                is Set<*> -> "string_set" to v.filterIsInstance<String>().sorted()
                else -> return@forEach
            }
            out[k.name] = linkedMapOf("type" to type, "value" to value)
        }
        return Json.write(linkedMapOf("format_version" to RAW_VERSION, "values" to out), indent = true)
    }

    /**
     * Replaces the settings file's values with [text] from [exportRaw] (a restore).
     * Returns: false when the text is not such an export (nothing changes then).
     * Notes: [text] comes from a backup file: values of an unknown type are skipped, and
     * [SettingsFormat.read] range-checks every known key when it is read.
     */
    suspend fun importRaw(text: String): Boolean {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: return false
        @Suppress("UNCHECKED_CAST")
        val values = o["values"] as? Map<String, Any?> ?: return false
        data.updateData {
            mutablePreferencesOf().apply {
                values.forEach { (name, raw) ->
                    @Suppress("UNCHECKED_CAST")
                    val m = raw as? Map<String, Any?> ?: return@forEach
                    val v = m["value"]
                    when (m["type"]) {
                        "boolean" -> (v as? Boolean)?.let { this[booleanPreferencesKey(name)] = it }
                        "int" -> (v as? Long)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.let { this[intPreferencesKey(name)] = it.toInt() }
                        "long" -> (v as? Long)?.let { this[longPreferencesKey(name)] = it }
                        "float" -> (v as? Number)?.let { this[floatPreferencesKey(name)] = it.toFloat() }
                        "double" -> (v as? Number)?.let { this[doublePreferencesKey(name)] = it.toDouble() }
                        "string" -> (v as? String)?.let { this[stringPreferencesKey(name)] = it }
                        "string_set" -> (v as? List<*>)?.let { this[stringSetPreferencesKey(name)] = it.filterIsInstance<String>().toSet() }
                    }
                }
            }
        }
        return true
    }

    /** Replaces the current theme with [t] (a restore); writes the theme file. */
    suspend fun replaceTheme(t: Theme) = updateTheme { t }

    /** Applies the theme file's values over the Preferences file's, when there is a theme file. */
    private fun Settings.with(t: Theme?) = if (t == null) this else copy(theme = t.base, keyRadiusDp = t.keyRadiusDp)

    /**
     * Starts [update] without waiting for it; for taps in the settings app.
     * Notes: starts undispatched, so changes made in a row are queued in the order they were made.
     */
    fun set(transform: (Settings) -> Settings) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { update(transform) }
    }

    companion object {
        /** The format version of [exportRaw]'s JSON. */
        private const val RAW_VERSION = 1L

        /** The settings file's name under `files/datastore/` (`.preferences_pb` added). */
        private const val FILE = "settings"
        /** The current theme, under `files/`. */
        private const val THEME_FILE = "themes/current.json"

        /**
         * Returns a store for use before the first unlock (Direct Boot): the defaults, kept in a
         * throwaway file in device-protected storage, never the person's settings file.
         * Notes: a new instance each call; the keyboard swaps it for [get] after the unlock.
         */
        fun forDirectBoot(context: Context): SettingsStore {
            val device = context.applicationContext.createDeviceProtectedStorageContext()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            File(device.cacheDir, LOCKED_FILE).delete()
            val data = PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { File(device.cacheDir, LOCKED_FILE) },
            )
            return SettingsStore(data, scope)
        }

        /** The throwaway settings file before the first unlock, in device-protected cache. */
        private const val LOCKED_FILE = "locked-settings.preferences_pb"

        @Volatile private var instance: SettingsStore? = null

        /**
         * Returns the process's store, creating it on first use.
         * Side effects: the first call starts reading `files/datastore/settings.preferences_pb` on
         * a background thread.
         * Notes: a corrupt file is replaced by an empty one (the defaults) rather than failing the
         * keyboard.
         */
        fun get(context: Context): SettingsStore = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val data = PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = scope,
                    produceFile = { app.preferencesDataStoreFile(FILE) },
                )
                SettingsStore(data, scope, ThemeFile(File(app.filesDir, THEME_FILE))).also { instance = it }
            }
        }
    }
}
