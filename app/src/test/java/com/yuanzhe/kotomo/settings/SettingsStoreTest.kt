package com.yuanzhe.kotomo.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yuanzhe.kotomo.ime.FuzzyRule
import com.yuanzhe.kotomo.ime.Mode
import com.yuanzhe.kotomo.ime.Tool
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
        modeOrder = listOf(Mode.CHINESE, Mode.JAPANESE, Mode.MIXED, Mode.ENGLISH, Mode.PC),
        hiddenModes = setOf(Mode.MIXED),
        mixed = Mixed(
            level = MixedLevel.AGGRESSIVE,
            cross = CrossLanguage(englishToKatakana = false, emoji = false),
            weightEmoji = 0,
            space = CompositionSpace.COMMIT_FIRST,
        ),
        japanese = CrossLanguage(readings = false),
        chineseScript = ChineseScript.TAIWAN,
        chineseFuzzy = setOf(FuzzyRule.Z_ZH, FuzzyRule.AN_ANG),
        englishAutoCapitalize = false,
        split = SplitLayout.NEVER,
        thumbZoneDp = 300,
        oneHanded = OneHanded.LEFT,
        heightPercent = 85,
        keyHints = false,
        keySound = true,
        gestures = Gestures(spaceCursor = false, deleteSwipe = false, doubleSpacePeriod = false, spaceLanguage = false, smartSpace = false),
        incognito = true,
        clipboard = ClipboardSettings(history = false, retentionDays = 30, quickPaste = false, excludedApps = setOf("com.example.bank", "org.example.notes")),
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

    /** CLIP-25: the excluded apps are package names; anything else in the stored list is dropped. */
    @Test
    fun excludedAppsKeepOnlyPackageNames() {
        withData { data, _ ->
            data.edit { it[stringPreferencesKey("clipboard.excluded_apps")] = "com.example.bank, not a package,,org.x" }
            assertEquals(setOf("com.example.bank", "org.x"), SettingsFormat.read(data.data.first()).clipboard.excludedApps)
        }
    }

    /** T08: each fuzzy-pinyin rule is its own switch, `chinese.fuzzy.<rule>`, all off by default. */
    @Test
    fun fuzzyRulesAreOneKeyEach() {
        assertEquals(emptySet<FuzzyRule>(), Settings().chineseFuzzy)
        withData { data, scope ->
            val store = SettingsStore(data, scope)
            store.update { it.copy(chineseFuzzy = setOf(FuzzyRule.N_L)) }
            val prefs = data.data.first()
            assertEquals(true, prefs[booleanPreferencesKey("chinese.fuzzy.n_l")])
            // Rules never turned on are not written.
            assertEquals(null, prefs[booleanPreferencesKey("chinese.fuzzy.l_r")])
            store.update { it.copy(chineseFuzzy = emptySet()) }
            assertEquals(false, data.data.first()[booleanPreferencesKey("chinese.fuzzy.n_l")])
            assertEquals(emptySet<FuzzyRule>(), SettingsFormat.read(data.data.first()).chineseFuzzy)
        }
    }

    /** The keyboard's fuzzy-pinyin deploy waits for [SettingsStore.loaded], which carries the stored values. */
    @Test
    fun loadedCarriesTheStoredValues() {
        withData { data, scope -> SettingsStore(data, scope).update { changed } }
        withData { data, scope ->
            val store = SettingsStore(data, scope)
            assertEquals(changed, store.loaded.first { it != null })
        }
    }

    @Test
    fun unchangedUpdateWritesNothing() {
        withData { data, scope ->
            SettingsStore(data, scope).update { it }
            assertEquals(emptyPreferences(), data.data.first())
        }
    }

    /** PLAN §2.3: the language choice follows the order, skips hidden modes and never empties. */
    @Test
    fun modeOrderAndHiddenModes() {
        val s = Settings(modeOrder = listOf(Mode.ENGLISH, Mode.JAPANESE, Mode.CHINESE, Mode.MIXED), hiddenModes = setOf(Mode.MIXED, Mode.CHINESE))
        assertEquals(listOf(Mode.ENGLISH, Mode.JAPANESE), s.modes)
        // The default (Mixed) is hidden: the first mode shown is used instead, and a hidden wish too.
        assertEquals(Mode.ENGLISH, s.usable(null))
        assertEquals(Mode.ENGLISH, s.usable(Mode.CHINESE))
        assertEquals(Mode.JAPANESE, s.usable(Mode.JAPANESE))
        // PC is never offered as the fallback for "everything hidden" (PLAN §10.11).
        assertEquals(Mode.entries - Mode.PC, Settings(hiddenModes = Mode.entries.toSet()).modes)
    }

    /** Unknown mode names (a newer version's) are skipped when read and kept when the order changes. */
    @Test
    fun modeListsKeepUnknownNames() {
        val order = stringPreferencesKey("modes.order")
        withData { data, _ -> data.edit { it[order] = "english,korean,japanese" } }
        withData { data, scope ->
            val read = SettingsFormat.read(data.data.first())
            // Modes missing from the stored order (PC, from an older file) join at the end.
            assertEquals(listOf(Mode.ENGLISH, Mode.JAPANESE, Mode.MIXED, Mode.CHINESE, Mode.PC), read.modeOrder)
            SettingsStore(data, scope).update { it.copy(modeOrder = listOf(Mode.JAPANESE, Mode.ENGLISH, Mode.MIXED, Mode.CHINESE)) }
            assertEquals("japanese,english,mixed,chinese,korean", data.data.first()[order])
        }
    }

    /** T14-toolbar-1: the toolbar's order and hidden tools survive reopening; More is never in the list. */
    @Test
    fun toolbarOrderAndHiddenTools() {
        assertEquals(listOf(Tool.CLIPBOARD, Tool.AI, Tool.TRANSLATE, Tool.SETTINGS), Settings().tools)
        val wanted = Settings(toolOrder = listOf(Tool.EDIT) + (Tool.configurable - Tool.EDIT), hiddenTools = setOf(Tool.AI, Tool.TRANSLATE))
        withData { data, scope -> SettingsStore(data, scope).update { wanted } }
        withData { data, _ ->
            val read = SettingsFormat.read(data.data.first())
            assertEquals(wanted.toolOrder, read.toolOrder)
            assertEquals(listOf(Tool.EDIT, Tool.CLIPBOARD, Tool.SETTINGS, Tool.EMOJI, Tool.LANGUAGE, Tool.DICTIONARIES, Tool.THEMES), read.tools)
            assertFalse(Tool.MORE in read.toolOrder)
        }
    }

    /**
     * T14-toolbar-2: a tool missing from a stored list (added by a later version) takes its default place:
     * under More when it starts there; an unknown tool name is kept.
     */
    @Test
    fun toolListsKeepUnknownNamesAndDefaultNewTools() {
        val order = stringPreferencesKey("toolbar.order")
        val hidden = stringPreferencesKey("toolbar.hidden")
        withData { data, _ -> data.edit { it[order] = "settings,hologram,clipboard,ai,translate"; it[hidden] = "ai" } }
        withData { data, scope ->
            val read = SettingsFormat.read(data.data.first())
            assertEquals(listOf(Tool.SETTINGS, Tool.CLIPBOARD, Tool.TRANSLATE), read.tools)
            assertEquals(setOf(Tool.AI) + Tool.offBar, read.hiddenTools)
            SettingsStore(data, scope).update { it.copy(hiddenTools = it.hiddenTools - Tool.EDIT) }
            assertEquals("settings,clipboard,ai,translate,edit,emoji,language,dictionaries,themes,hologram", data.data.first()[order])
            assertEquals("ai,emoji,language,dictionaries,themes", data.data.first()[hidden])
        }
    }
}
