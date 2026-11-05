package com.yuanzhe.kotomo.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yuanzhe.kotomo.dictionary.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the theme file (PLAN §8.6, the behavior contract's versioned formats): round trip,
 * unknown members kept, newer version kept, damaged files, and the store keeping the theme in
 * its own file while older installs read it from the settings.
 */
class ThemeFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun roundTripKeepsUnknownMembersAndNewerVersions() {
        val text = """{"format_version": 7, "base": "dark", "key_radius_dp": 6, "colors": {"key": "#112233"}}"""
        val theme = ThemeFormat.read(text)!!
        assertEquals(KeyboardTheme.DARK, theme.base)
        assertEquals(6, theme.keyRadiusDp)
        @Suppress("UNCHECKED_CAST")
        val written = Json.parse(ThemeFormat.write(theme.copy(keyRadiusDp = 8), 1000L)) as Map<String, Any?>
        assertEquals(7L, written["format_version"])
        assertEquals(8L, written["key_radius_dp"])
        assertEquals(mapOf("key" to "#112233"), written["colors"])
        assertEquals(1000L, written["updated_utc_ms"])
    }

    @Test
    fun badValuesReadAsDefaultsAndDamageIsSetAside() {
        val theme = ThemeFormat.read("""{"base": "neon", "key_radius_dp": 999}""")!!
        assertEquals(Theme().base, theme.base)
        assertEquals(Theme().keyRadiusDp, theme.keyRadiusDp)
        val file = File(folder.root, "current.json").apply { writeText("{not json") }
        assertNull(ThemeFile(file).load())
        assertTrue(File(folder.root, "current.json.unreadable").isFile)
    }

    @Test
    fun theStoreWritesTheThemeToItsOwnFile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefsFile = File(folder.root, "settings.preferences_pb")
        val themeFile = File(folder.root, "themes/current.json")
        val data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { prefsFile })
        // An install from before the theme file: the theme sits in the settings file.
        data.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("theme.choice")] = "light" } }
        val store = SettingsStore(data, scope, ThemeFile(themeFile))
        assertEquals(KeyboardTheme.LIGHT, store.flow.first { it.theme == KeyboardTheme.LIGHT }.theme)
        store.update { it.copy(theme = KeyboardTheme.DARK, keyRadiusDp = 4) }
        assertEquals(Theme(KeyboardTheme.DARK, 4), ThemeFile(themeFile).load()!!.copy(extra = emptyMap()))
        val prefs: Preferences = data.data.first()
        // Negative: the old key keeps its value (rollback-safe), and the settings file gets no theme.
        assertEquals("light", prefs[stringPreferencesKey("theme.choice")])
        assertEquals(KeyboardTheme.DARK, store.flow.first { it.theme == KeyboardTheme.DARK }.theme)
        scope.cancel()
    }
}
