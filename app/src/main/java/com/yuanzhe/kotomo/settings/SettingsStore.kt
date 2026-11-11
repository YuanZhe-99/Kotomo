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
