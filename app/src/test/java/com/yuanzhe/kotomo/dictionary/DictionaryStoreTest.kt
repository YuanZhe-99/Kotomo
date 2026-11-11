package com.yuanzhe.kotomo.dictionary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Checks the dictionary store (T17, PLAN §10.5): the versioned `user.json` (unknown members kept,
 * the previous version for rollback, a newer file never overwritten), the switches, and the three
 * librime tables, where untrusted entries only ever reach the low-priority one.
 */
class DictionaryStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir get() = File(folder.root, "dictionaries")
    private val rime get() = File(folder.root, "rime")
    private fun store() = DictionaryStore(dir, rime, Dispatchers.Unconfined) { NOW }
    private fun table(name: String) = File(rime, name).readText()

    private val manual = Entry(Lang.ZH, "考拓莫", "kao tuo mo", 90, Source.MANUAL)
    private val imported = Entry(Lang.ZH, "泥好", "ni hao", 40, Source.IMPORT)
    private val generated = Entry(Lang.ZH, "尼好", "ni hao", 20, Source.GENERATED)
    private val japanese = Entry(Lang.JA, "東京", "とうきょう", 70, Source.MANUAL)

    @Test
    fun emptyTablesAreEmptyFiles() = runBlocking {
        store().load()
        for (name in listOf(DictionaryFormat.CUSTOM_TABLE, DictionaryFormat.PACKS_TABLE, DictionaryFormat.LOW_TABLE)) {
            assertEquals(name, "", table(name))
        }
    }

    @Test
    fun entriesGoToTheTableOfTheirSource() = runBlocking {
        val s = store()
        assertTrue(s.put(manual))
        assertTrue(s.put(japanese))
        assertTrue(s.import(ImportPreview(listOf(imported, generated), 0, emptyList(), 0)))
        assertEquals("考拓莫\tkaotuomo\t90\n", table(DictionaryFormat.CUSTOM_TABLE))
        // Untrusted entries rank low: only the quality −1 table, never the hand-added one.
        assertEquals("泥好\tnihao\t40\n尼好\tnihao\t20\n", table(DictionaryFormat.LOW_TABLE))
        assertEquals("", table(DictionaryFormat.PACKS_TABLE))
        // Japanese is stored but not used yet.
        assertTrue(japanese in s.state.value.entries)
        assertFalse(japanese.used)
    }

    @Test
    fun theCustomSwitchEmptiesBothOfItsTables() = runBlocking {
        val s = store()
        s.put(manual)
        s.import(ImportPreview(listOf(imported), 0, emptyList(), 0))
        assertTrue(s.setSources(custom = false))
        assertEquals("", table(DictionaryFormat.CUSTOM_TABLE))
        assertEquals("", table(DictionaryFormat.LOW_TABLE))
        // The switch is kept across restarts.
        val again = store().apply { load() }
        assertFalse(again.state.value.customEnabled)
        assertEquals(2, again.state.value.entries.size)
    }

    @Test
    fun userFileIsVersionedAndKeepsUnknownMembers() = runBlocking {
        dir.mkdirs()
        File(dir, "user.json").writeText(
            """{"format_version": 1, "future": {"x": 1}, "entries": [
                 {"language": "zh", "text": "你好", "reading": "ni hao", "weight": 60, "source": "manual", "tags": ["greeting"]},
                 {"language": "zh", "text": "坏", "reading": "???", "weight": 60, "source": "manual"}
               ]}""",
        )
        val s = store()
        s.load()
        assertEquals(listOf("greeting"), s.state.value.entries.single().extra["tags"])
        s.put(manual)
        @Suppress("UNCHECKED_CAST")
        val written = Json.parse(File(dir, "user.json").readText()) as Map<String, Any?>
        assertEquals(1L, written["format_version"])
        assertEquals(NOW, written["updated_utc_ms"])
        assertEquals(mapOf("x" to 1L), written["future"])
        // The damaged entry is kept verbatim, the good one with its unknown field.
        val texts = written.list("entries")!!.map { (it as Map<*, *>)["text"] }
        assertEquals(listOf("你好", "考拓莫", "坏"), texts)
        assertTrue(File(dir, "user.prev.json").isFile)
    }

    @Test
    fun rollBackRestoresThePreviousVersion() = runBlocking {
        val s = store()
        s.put(manual)
        s.delete(manual)
        assertTrue(s.state.value.canRollBack)
        assertTrue(s.rollBack())
        assertEquals(listOf(manual), s.state.value.entries)
        assertEquals("考拓莫\tkaotuomo\t90\n", table(DictionaryFormat.CUSTOM_TABLE))
        // A second rollback undoes the first.
        assertTrue(s.rollBack())
        assertTrue(s.state.value.entries.isEmpty())
    }

    @Test
    fun aNewerFileIsNeverOverwritten() = runBlocking {
        dir.mkdirs()
        val newer = """{"format_version": 2, "entries": [{"language": "zh", "text": "你好", "reading": "ni hao", "weight": 60, "source": "manual"}]}"""
        File(dir, "user.json").writeText(newer)
        val s = store()
        s.load()
        assertTrue(s.state.value.readOnly)
        assertFalse(s.put(manual))
        assertFalse(s.import(ImportPreview(listOf(imported), 0, emptyList(), 0)))
        assertEquals(newer, File(dir, "user.json").readText())
        // It is still used.
        assertEquals("你好\tnihao\t60\n", table(DictionaryFormat.CUSTOM_TABLE))
    }

    @Test
    fun importKeepsExistingEntriesUnlessAskedToReplace() = runBlocking {
        val s = store()
        val mine = Entry(Lang.ZH, "泥好", "ni hao", 70, Source.MANUAL)
        s.put(mine)
        s.import(ImportPreview(listOf(imported), 0, emptyList(), 0))
        assertEquals(listOf(mine), s.state.value.entries)
        s.import(ImportPreview(listOf(imported), 0, emptyList(), 0), replace = true)
        assertEquals(listOf(imported), s.state.value.entries)
    }

    @Test
    fun putRefusesASecondEntryWithTheSameKey() = runBlocking {
        val s = store()
        assertTrue(s.put(manual))
        assertFalse(s.put(manual.copy(weight = 10)))
        assertTrue(s.put(manual.copy(weight = 10), old = manual))
        assertEquals(listOf(10), s.state.value.entries.map { it.weight })
    }

    @Test
    fun packsInstallRollBackAndUninstall() = runBlocking {
        val s = store()
        val pack = OnlineTest.pack("sample-zh", Lang.ZH, ByteArray(1))
        val v1 = listOf(Entry(Lang.ZH, "蔻拓摩", "kou tuo mo", 50, Source.DOWNLOAD))
        val v2 = listOf(Entry(Lang.ZH, "寇拓摩", "kou tuo mo", 60, Source.DOWNLOAD))
        assertTrue(s.install(pack, v1))
        assertEquals("蔻拓摩\tkoutuomo\t50\n", table(DictionaryFormat.PACKS_TABLE))
        assertTrue(s.install(pack.copy(version = "2"), v2))
        assertEquals("寇拓摩\tkoutuomo\t60\n", table(DictionaryFormat.PACKS_TABLE))
        assertTrue(s.state.value.packs.single().hasPrevious)
        assertTrue(s.rollBackPack(pack.id))
        assertEquals("蔻拓摩\tkoutuomo\t50\n", table(DictionaryFormat.PACKS_TABLE))
        assertTrue(s.setPack(pack.id, false))
        assertEquals("", table(DictionaryFormat.PACKS_TABLE))
        assertTrue(s.setPack(pack.id, true))
        assertTrue(s.setSources(downloads = false))
        assertEquals("", table(DictionaryFormat.PACKS_TABLE))
        assertTrue(s.uninstall(pack.id))
        assertTrue(s.state.value.packs.isEmpty())
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
