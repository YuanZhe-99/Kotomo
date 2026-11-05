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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Keeps the person's [Settings] in a Preferences DataStore file and offers them as a [StateFlow].
 *
 * Inputs: data is the DataStore holding the file; scope runs the reading and the writes.
 * Notes: one instance per process ([get]); the settings app and the keyboard share it. [current]
 * never touches the disk, so the keyboard may read it on the main thread; until the file's first
 * read completes (a few milliseconds after [get]) it holds the defaults.
 */
class SettingsStore(private val data: DataStore<Preferences>, private val scope: CoroutineScope) {
    /** Emits the settings each time the file changes; starts with the defaults. */
    val flow: StateFlow<Settings> = data.data.map(SettingsFormat::read).stateIn(scope, SharingStarted.Eagerly, Settings())

    /** Returns the latest settings read from the file, without I/O. */
    val current: Settings get() = flow.value

    /**
     * Applies [transform] to the stored settings and writes what changed.
     * Side effects: rewrites the settings file when anything changed; [flow] then emits.
     * Notes: suspends until the write is durable; unknown keys in the file are kept.
     */
    suspend fun update(transform: (Settings) -> Settings) {
        data.updateData { prefs ->
            val old = SettingsFormat.read(prefs)
            val new = transform(old)
            if (new == old) prefs else prefs.toMutablePreferences().also {
                SettingsFormat.write(it, old, new, System.currentTimeMillis())
            }
        }
    }

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
                SettingsStore(data, scope).also { instance = it }
            }
        }
    }
}
