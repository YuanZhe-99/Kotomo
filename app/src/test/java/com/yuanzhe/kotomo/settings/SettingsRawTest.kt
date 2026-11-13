package com.yuanzhe.kotomo.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Covers the settings part of a backup (T19-roundtrip, PLAN §11.6): every value and its type, unknown keys included. */
class SettingsRawTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun rawExportRestoresEveryValue() = runBlocking { // T19-roundtrip
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(folder.root, "s.preferences_pb") })
        val store = SettingsStore(data, scope)
        store.update { it.copy(incognito = true, split = SplitLayout.NEVER) }
        data.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("future.key")] = "kept"; this[intPreferencesKey("future.n")] = 7 } }
        val raw = store.exportRaw()
        store.update { it.copy(incognito = false, split = SplitLayout.ALWAYS) }
        data.updateData { it.toMutablePreferences().apply { remove(stringPreferencesKey("future.key")) } }
        assert(store.importRaw(raw))
        val s = store.flow.first { it.incognito }
        assertEquals(SplitLayout.NEVER, s.split)
        val prefs = data.data.first()
        assertEquals("kept", prefs[stringPreferencesKey("future.key")])
        assertEquals(7, prefs[intPreferencesKey("future.n")])
        // Negative: text that is not an export changes nothing.
        assertFalse(store.importRaw("""{"hello":1}"""))
        assertEquals(SplitLayout.NEVER, store.current.split)
        scope.cancel()
    }
}
