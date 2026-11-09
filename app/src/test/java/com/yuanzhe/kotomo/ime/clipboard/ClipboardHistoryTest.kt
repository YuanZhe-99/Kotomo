package com.yuanzhe.kotomo.ime.clipboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.yuanzhe.kotomo.settings.ClipboardSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Checks the clipboard history (PLAN §10.7, §14; tests CLIP-01 to CLIP-12): recording rules,
 * dedupe, pins and phrases, retention and the cap, the encrypted versioned file, and the
 * classification of clips.
 */
class ClipboardHistoryTest {
    @get:Rule val folder = TemporaryFolder()

    private val file get() = File(folder.root, "clipboard/history.kcb")
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var now = 1_800_000_000_000L
    private var settings = ClipboardSettings()

    /** Opens a history over [file] and runs [block]; waits for its writes, then stops it. */
    private fun <T> withHistory(key: SecretKey = this.key, block: suspend (ClipboardHistory) -> T): T = runBlocking {
        val job = Job()
        try {
            val history = ClipboardHistory(file, { key }, CoroutineScope(Dispatchers.IO + job), { settings }, { now })
            history.flush()
            block(history).also { history.flush() }
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun texts(h: ClipboardHistory) = h.items.value.map { it.text }

    @Test
    fun recordsNewestFirstAndDedupes() { // CLIP-01
        withHistory { h ->
            h.add("one", now)
            h.add("two", now + 1)
            h.add("one", now + 2)
            h.flush()
            assertEquals(listOf("one", "two"), texts(h))
            // The same clip seen again (no newer time) changes nothing.
            h.add("one", now + 2)
            h.flush()
            assertEquals(2, h.items.value.size)
        }
    }

    @Test
    fun historyOffRecordsNothingButStillOffersTheClip() { // CLIP-02 (negative)
        settings = ClipboardSettings(history = false)
        withHistory { h ->
            val copied = h.add("Your code is 482913", now)
            h.flush()
            assertTrue(h.items.value.isEmpty())
            assertEquals(listOf("482913"), copied?.codes?.map { it.value })
        }
        assertFalse(file.exists())
    }

    @Test
    fun blankAndOverLongClipsAreSkipped() { // CLIP-03 (negative)
        withHistory { h ->
            assertNull(h.add("   ", now))
            assertNull(h.add("x".repeat(ClipboardHistory.MAX_TEXT + 1), now))
            h.flush()
            assertTrue(h.items.value.isEmpty())
        }
    }

    @Test
    fun unpinnedItemsExpirePinnedAndPhrasesStay() { // CLIP-04
        withHistory { h ->
            h.add("old", now)
            h.add("pinned", now)
            h.addPhrase("phrase")
            h.flush()
            h.pin(h.items.value.first { it.text == "pinned" }.id, true)
            h.flush()
            now += 8 * 86_400_000L
            h.expire()
            h.flush()
            assertEquals(setOf("pinned", "phrase"), texts(h).toSet())
        }
    }

    @Test
    fun capKeepsTheNewestUnpinned() { // CLIP-05
        withHistory { h ->
            h.add("keep", now)
            h.flush()
            h.pin(h.items.value.single().id, true)
            repeat(ClipboardHistory.MAX_ITEMS + 5) { h.add("clip $it", now + 1 + it) }
            h.flush()
            val items = h.items.value
            assertEquals(ClipboardHistory.MAX_ITEMS + 1, items.size)
            assertTrue(items.any { it.text == "keep" })
            assertTrue(items.none { it.text == "clip 0" })
        }
    }

    @Test
    fun deleteAndClearUnpinned() { // CLIP-06
        withHistory { h ->
            h.add("a", now)
            h.add("b", now + 1)
            h.addPhrase("p")
            h.flush()
            h.delete(h.items.value.first { it.text == "a" }.id)
            h.flush()
            assertEquals(setOf("b", "p"), texts(h).toSet())
            h.clearUnpinned()
            h.flush()
            assertEquals(listOf("p"), texts(h))
        }
    }

    @Test
    fun survivesReopeningAndIsEncrypted() { // CLIP-07
        withHistory { h ->
            h.add("东京都豊島区南池袋 1-28-1", now)
            h.add("secret-looking text", now + 1)
        }
        val bytes = file.readBytes()
        assertArrayEquals("KCLP".toByteArray(), bytes.copyOfRange(0, 4))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("secret-looking"))
        withHistory { h -> assertEquals(listOf("secret-looking text", "东京都豊島区南池袋 1-28-1"), texts(h)) }
    }

    @Test
    fun unknownFieldsAndNewerFormatAreKept() { // CLIP-08
        val records = listOf(
            mapOf("format_version" to "9", "future.header" to "h"),
            mapOf("id" to "5", "text" to "kept", "time_utc_ms" to now.toString(), "pinned" to "1", "phrase" to "0", "future.field" to "f"),
        )
        file.parentFile!!.mkdirs()
        file.writeBytes(ClipFile.seal(records, key))
        withHistory { h ->
            assertEquals(listOf("kept"), texts(h))
            h.add("new", now + 1)
        }
        val read = ClipFile.open(file.readBytes(), key)
        assertEquals("9", read[0]["format_version"])
        assertEquals("h", read[0]["future.header"])
        assertEquals("f", read.first { it["text"] == "kept" }["future.field"])
    }

    @Test
    fun newerContainerIsNeverOverwritten() { // CLIP-09
        file.parentFile!!.mkdirs()
        val newer = "KCLP".toByteArray() + byteArrayOf(2) + ByteArray(40)
        file.writeBytes(newer)
        withHistory { h ->
            h.add("x", now)
            h.flush()
            assertEquals(listOf("x"), texts(h))
        }
        assertArrayEquals(newer, file.readBytes())
    }

    @Test
    fun undecryptableFileIsSetAside() { // CLIP-10
        withHistory { h -> h.add("a", now) }
        file.setLastModified(now) // written "now" on the test clock, so the leftover is not yet stale
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        withHistory(other) { h -> assertTrue(h.items.value.isEmpty()) }
        assertTrue(File(file.path + ".unreadable").exists())
        assertFalse(file.exists())
    }

    @Test
    fun sensitiveFieldsRecordNothing() { // CLIP-11 (negative)
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(ClipboardHistory.sensitiveField(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0))
        assertTrue(ClipboardHistory.sensitiveField(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0))
        assertTrue(ClipboardHistory.sensitiveField(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0))
        assertTrue(ClipboardHistory.sensitiveField(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertFalse(ClipboardHistory.sensitiveField(text or InputType.TYPE_TEXT_VARIATION_URI, 0))
        assertFalse(ClipboardHistory.sensitiveField(text, 0))
    }

    @Test
    fun classifiesClips() { // CLIP-12
        assertEquals(ClipKind.CODE, ClipKind.of("482913"))
        assertEquals(ClipKind.CODE, ClipKind.of("Your verification code is 482913"))
        assertEquals(ClipKind.LINK, ClipKind.of("https://example.com/form/2026"))
        assertEquals(ClipKind.PHONE, ClipKind.of("+1 415 555 0100"))
        assertEquals(ClipKind.PHONE, ClipKind.of("13800000000"))
        assertEquals(ClipKind.ADDRESS, ClipKind.of("东京都豊島区南池袋 1-28-1"))
        assertEquals(ClipKind.ADDRESS, ClipKind.of("上海市浦东新区世纪大道100号"))
        assertEquals(ClipKind.ADDRESS, ClipKind.of("221 Baker Street, London"))
        assertEquals(ClipKind.TEXT, ClipKind.of("明天上午十点在池袋站东口见，带上在留卡和护照复印件。"))
        assertEquals(ClipKind.TEXT, ClipKind.of("The meeting has been moved to Friday afternoon."))
        assertEquals(ClipKind.TEXT, ClipKind.of("我的邮箱：name@example.com"))
        val code = Clip(1, "482913", now)
        assertNotNull(code.code)
    }

    private fun textsOnDisk() = ClipFile.open(file.readBytes(), key).drop(1).map { it["text"] }

    @Test
    fun expiredItemsLeaveTheFileWhenRead() { // CLIP-13
        withHistory { h ->
            h.add("old", now)
            h.add("recent", now + 6 * DAY)
        }
        now += 8 * DAY
        withHistory { h -> assertEquals(listOf("recent"), texts(h)) }
        // The unexpired item is kept on disk; the expired one is gone without any change being made.
        assertEquals(listOf("recent"), textsOnDisk())
        now += 7 * DAY
        withHistory { h -> assertTrue(h.items.value.isEmpty()) }
        assertFalse(file.exists())
    }

    @Test
    fun unreadableLeftoverIsDroppedAfterTheRetention() { // CLIP-14
        val leftover = File(file.path + ".unreadable")
        withHistory { h -> h.add("a", now) }
        file.setLastModified(now)
        withHistory(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) { }
        withHistory { h ->
            h.addPhrase("b")
            h.flush()
            // Negative: a leftover within the retention stays, and the new readable file is no leftover.
            assertTrue(leftover.exists())
            now += 8 * DAY
            h.expire()
            h.flush()
            assertFalse(leftover.exists())
        }
        // The same when the history is read: a stale leftover goes, the readable file stays.
        leftover.writeBytes(ByteArray(40))
        leftover.setLastModified(now - 8 * DAY)
        withHistory { h -> assertEquals(listOf("b"), texts(h)) }
        assertFalse(leftover.exists())
        assertEquals(listOf("b"), textsOnDisk())
    }

    @Test
    fun historyOffKeepsSavedItemsAndStillExpiresThem() { // CLIP-15
        withHistory { h ->
            h.add("old", now)
            h.add("pinned", now)
            h.flush()
            h.pin(h.items.value.first { it.text == "pinned" }.id, true)
        }
        settings = ClipboardSettings(history = false)
        withHistory { h ->
            // Negative: switching the history off deletes nothing by itself.
            assertEquals(setOf("old", "pinned"), texts(h).toSet())
            now += 8 * DAY
            h.add("new", now)
            h.flush()
            assertEquals(listOf("pinned"), texts(h))
        }
        assertEquals(listOf("pinned"), textsOnDisk())
    }

    @Test
    fun clipRefusedInAPasswordFieldStaysRefused() { // CLIP-16 (negative)
        val password = !ClipboardHistory.sensitiveField(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0)
        withHistory { h ->
            assertNull(h.onCopy(now, password) { "hunter2" })
            // Shown next in an ordinary field with the same clip still on the clipboard.
            assertNull(h.onCopy(now, true) { "hunter2" })
            h.flush()
            assertTrue(h.items.value.isEmpty())
        }
        assertFalse(file.exists())
    }

    @Test
    fun aNewerClipAfterARefusalIsTakenNormally() { // CLIP-17
        withHistory { h ->
            assertNull(h.onCopy(now, false) { "hunter2" })
            val copied = h.onCopy(now + 1, true) { "Your code is 482913" }
            h.flush()
            assertEquals(listOf("482913"), copied?.codes?.map { it.value })
            assertEquals(listOf("Your code is 482913"), texts(h))
        }
    }

    @Test
    fun historyOffIsNoRefusal() { // CLIP-18 (negative)
        settings = ClipboardSettings(history = false)
        withHistory { h ->
            assertNotNull(h.onCopy(now, true) { "Your code is 482913" })
            // Seen again when the keyboard is next shown: still offered, still not recorded.
            assertNotNull(h.onCopy(now, true) { "Your code is 482913" })
            h.flush()
            assertTrue(h.items.value.isEmpty())
        }
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
