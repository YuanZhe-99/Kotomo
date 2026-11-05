package com.yuanzhe.kotomo.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yuanzhe.kotomo.ime.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Checks the settings store's format: defaults, round trip, unknown keys and a missing file. */
class SettingsStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val file get() = File(folder.root, "settings.preferences_pb")

    /**
     * Opens a DataStore over [file] and runs [block]; closes it so the file can be reopened.
     * Notes: uses the okio storage, whose replacing move also works on a Windows host (the Android
     * file storage renames, which Windows refuses over an existing file).
     */
    private fun <T> withData(block: suspend (DataStore<Preferences>, CoroutineScope) -> T): T = runBlocking {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        try {
            val storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.toOkioPath() })
            block(DataStoreFactory.create(storage, scope = scope), scope)
        } finally {
            job.cancelAndJoin()
        }
    }

    private val changed = Settings(
        defaultMode = Mode.JAPANESE,
        mixed = Mixed(
            level = MixedLevel.AGGRESSIVE,
            cross = CrossLanguage(englishToKatakana = false, emoji = false),
            weightEmoji = 0,
            space = CompositionSpace.COMMIT_FIRST,
        ),
        japanese = CrossLanguage(readings = false),
        chineseScript = ChineseScript.TAIWAN,
        englishAutoCapitalize = false,
        split = SplitLayout.NEVER,
        thumbZoneDp = 300,
        oneHanded = OneHanded.LEFT,
        heightPercent = 85,
        keyHints = false,
        keySound = true,
        gestures = Gestures(spaceCursor = false, deleteSwipe = false, doubleSpacePeriod = false, spaceLanguage = false, smartSpace = false),
        incognito = true,
        clipboard = ClipboardSettings(history = false, retentionDays = 30, quickPaste = false),
        ai = AiSettings(enabled = true),
    )

    @Test
    fun emptyFileReadsAsDefaults() {
        val s = SettingsFormat.read(emptyPreferences())
        assertEquals(Settings(), s)
        assertEquals(Mode.MIXED, s.defaultMode)
        assertEquals(MixedLevel.BALANCED, s.mixed.level)
        assertEquals(CrossLanguage(), s.japanese)
        assertFalse(s.incognito)
        assertEquals(ClipboardSettings(history = true, retentionDays = 7, quickPaste = true), s.clipboard)
        // The behavior contract: AI is off until the person turns it on (PLAN §11).
        assertFalse(s.ai.enabled)
    }

    @Test
    fun noFileMigratesToCurrentVersionOnFirstWrite() {
        assertFalse(file.exists())
        withData { data, scope ->
            val store = SettingsStore(data, scope)
            assertEquals(Settings(), store.current)
            assertEquals(Settings(), SettingsFormat.read(data.data.first()))
            store.update { it.copy(haptics = false) }
            val prefs = data.data.first()
            assertEquals(SettingsFormat.VERSION, prefs[SettingsFormat.version])
            assertEquals(false, store.flow.first { !it.haptics }.haptics)
        }
    }

    @Test
    fun changesSurviveReopening() {
        withData { data, scope -> SettingsStore(data, scope).update { changed } }
        val read = withData { data, _ -> SettingsFormat.read(data.data.first()) }
        assertEquals(changed, read)
    }

    @Test
    fun unknownKeysAndNewerValuesAreKept() {
        val future = stringPreferencesKey("future.setting")
        val split = stringPreferencesKey("layout.split")
        withData { data, _ ->
            data.edit {
                it[future] = "kept"
                it[split] = "sometimes"
                it[SettingsFormat.version] = SettingsFormat.VERSION + 1
            }
        }
        withData { data, scope ->
            val store = SettingsStore(data, scope)
            store.update { it.copy(heightPercent = 110) }
            val prefs = data.data.first()
            assertEquals("kept", prefs[future])
            assertEquals("sometimes", prefs[split])
            assertEquals(SettingsFormat.VERSION + 1, prefs[SettingsFormat.version])
            val s = SettingsFormat.read(prefs)
            assertEquals(SplitLayout.AUTO, s.split)
            assertEquals(110, s.heightPercent)
        }
    }

    @Test
    fun outOfRangeNumbersReadAsDefaults() {
        withData { data, _ ->
            data.edit {
                it[intPreferencesKey("layout.height_percent")] = 400
                it[intPreferencesKey("mixed.weight_emoji")] = -1
                it[intPreferencesKey("clipboard.retention_days")] = 0
            }
            val s = SettingsFormat.read(data.data.first())
            assertEquals(Settings().heightPercent, s.heightPercent)
            assertEquals(Settings().mixed.weightEmoji, s.mixed.weightEmoji)
            assertEquals(Settings().clipboard.retentionDays, s.clipboard.retentionDays)
        }
    }

    @Test
    fun unchangedUpdateWritesNothing() {
        withData { data, scope ->
            SettingsStore(data, scope).update { it }
            assertEquals(emptyPreferences(), data.data.first())
        }
    }
}
