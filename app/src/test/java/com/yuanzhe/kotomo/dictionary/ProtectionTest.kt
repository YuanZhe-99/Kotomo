package com.yuanzhe.kotomo.dictionary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Checks the optional password protection of the person's dictionary (T17-protect, ADR-012):
 * the sealed format (right and wrong password, a changed byte, a newer version) and the store
 * (no plain words on disk while protected, locked after a restart with the tables kept, Lock
 * empties them, unlock, turning it off, protected exports, rollback while protected).
 */
class ProtectionTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir get() = File(folder.root, "dictionaries")
    private val rime get() = File(folder.root, "rime")
    /** Few rounds keep the tests fast; the app uses Protection.ITERATIONS. */
    private fun store() = DictionaryStore(dir, rime, Dispatchers.Unconfined, iterations = 2_000) { NOW }
    private val word = Entry(Lang.ZH, "考拓莫", "kao tuo mo", 90, Source.MANUAL)
    private fun table(name: String) = File(rime, name).readText()

    @Test
    fun sealedTextOpensOnlyWithItsPassword() {
        val salt = Protection.salt()
        val key = Protection.derive("correct horse".toCharArray(), salt, 2_000)
        val sealed = Protection.seal("你好".toByteArray(), key, salt, 2_000, "tsv")
        assertTrue(Protection.isSealed(sealed))
        assertEquals("tsv", Protection.header(sealed).kind)
        assertArrayEquals("你好".toByteArray(), Protection.open(sealed, "correct horse".toCharArray()))
        assertEquals(SealProblem.WRONG_PASSWORD, assertThrows(SealException::class.java) { Protection.open(sealed, "wrong".toCharArray()) }.problem)
        val changed = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertEquals(SealProblem.WRONG_PASSWORD, assertThrows(SealException::class.java) { Protection.open(changed, key) }.problem)
        val newer = sealed.copyOf().also { it[4] = (Protection.VERSION + 1).toByte() }
        assertEquals(SealProblem.NEWER, assertThrows(SealException::class.java) { Protection.open(newer, key) }.problem)
        assertFalse(Protection.isSealed("language\ttext".toByteArray()))
    }

    @Test
    fun protectedWordsAreNeverPlainOnDisk() = runBlocking {
        val s = store()
        s.put(word)
        s.put(word.copy(weight = 10), old = word)
        assertTrue(s.protect("secret".toCharArray()))
        assertFalse(File(dir, "user.json").exists())
        assertFalse(File(dir, "user.prev.json").exists())
        for (f in dir.walk().filter { it.isFile }) assertFalse("${f.name} holds the word", f.readBytes().toString(Charsets.UTF_8).contains("考拓莫"))
        // Still unlocked: changes are sealed too, and the tables keep working.
        assertTrue(s.put(word.copy(text = "考拓", reading = "kao tuo")))
        assertEquals("考拓莫\tkaotuomo\t10\n考拓\tkaotuo\t90\n", table(DictionaryFormat.CUSTOM_TABLE))
        assertFalse(File(dir, "user.kenc").readText(Charsets.ISO_8859_1).contains("kaotuo"))
        assertTrue(File(dir, "state.json").readText().contains("pbkdf2-hmac-sha256"))
        assertFalse(File(dir, "state.json").readText().contains("secret"))
    }

    @Test
    fun aRestartIsLockedAndKeepsTheTablesUntilLock() = runBlocking {
        store().apply { put(word); protect("secret".toCharArray()) }
        val again = store().also { it.load() }
        assertTrue(again.state.value.locked)
        assertTrue(again.state.value.entries.isEmpty())
        assertEquals("locked after a restart: the keyboard keeps the words", "考拓莫\tkaotuomo\t90\n", table(DictionaryFormat.CUSTOM_TABLE))
        assertFalse("locked: no change", again.put(word.copy(weight = 1)))
        assertFalse("wrong password", again.unlock("nope".toCharArray()))
        assertTrue(again.state.value.locked)
        assertTrue(again.unlock("secret".toCharArray()))
        assertEquals(listOf("考拓莫"), again.state.value.entries.map { it.text })
        assertTrue(again.lock())
        assertEquals("Lock empties the tables", "", table(DictionaryFormat.CUSTOM_TABLE))
        assertTrue(again.state.value.locked)
    }

    @Test
    fun turningItOffWritesPlainFilesAgain() = runBlocking {
        val s = store()
        s.put(word)
        s.protect("secret".toCharArray())
        s.put(word.copy(weight = 20), old = word)
        assertTrue(s.rollBack())
        assertEquals(listOf(90), s.state.value.entries.map { it.weight })
        assertTrue(s.unprotect())
        assertTrue(File(dir, "user.json").readText().contains("考拓莫"))
        assertFalse(File(dir, "user.kenc").exists())
        assertFalse(store().also { it.load() }.state.value.protected)
    }

    @Test
    fun protectedExportsOpenWithThePasswordAnywhere() = runBlocking {
        val s = store()
        assertEquals("not protected: plain export", null, s.sealExport("x", Format.TSV))
        s.put(word)
        s.protect("secret".toCharArray())
        val text = Formats.write(s.state.value.entries, Format.TSV)
        val sealed = s.sealExport(text, Format.TSV)!!
        assertEquals("tsv", Protection.header(sealed).kind)
        val read = Formats.read(Protection.open(sealed, "secret".toCharArray()), Format.TSV, Lang.ZH, generated = false)
        assertEquals(listOf("考拓莫"), read.entries.map { it.text })
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
