package com.yuanzhe.kotomo.ime.clipboard

import com.yuanzhe.kotomo.ime.Fields
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
        assertTrue(Fields.sensitive(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0))
        assertTrue(Fields.sensitive(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0))
        assertTrue(Fields.sensitive(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0))
        assertTrue(Fields.sensitive(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertFalse(Fields.sensitive(text or InputType.TYPE_TEXT_VARIATION_URI, 0))
        assertFalse(Fields.sensitive(text, 0))
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
        val password = !Fields.sensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0)
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

    @Test
    fun aRefusalSurvivesARestart() { // CLIP-19 (negative)
        withHistory { h -> assertNull(h.onCopy(now, false) { "hunter2" }) }
        // A new process sees the same clip still on the clipboard in an ordinary field.
        withHistory { h ->
            assertNull(h.onCopy(now, true) { "hunter2" })
            h.flush()
            assertTrue(h.items.value.isEmpty())
        }
        assertEquals(now.toString(), File(file.parentFile, "refused").readText())
        assertFalse("only the timestamp is kept", File(file.parentFile, "refused").readText().contains("hunter2"))
    }

    @Test
    fun anExcludedAppRecordsNothing() { // CLIP-20 (negative) and positive
        val excluded = com.yuanzhe.kotomo.settings.Settings(clipboard = ClipboardSettings(excludedApps = setOf("com.example.bank")))
        val text = InputType.TYPE_CLASS_TEXT
        assertFalse(ClipboardHistory.allowedIn(text, 0, "com.example.bank", excluded))
        assertTrue(ClipboardHistory.allowedIn(text, 0, "com.example.notes", excluded))
        assertTrue(ClipboardHistory.allowedIn(text, 0, "com.example.bank", com.yuanzhe.kotomo.settings.Settings()))
    }

    @Test
    fun expiredItemsGoWithoutTheKeyboardBeingUsed() { // CLIP-21
        withHistory { h ->
            h.add("old", now - 7 * DAY + 300)
            h.add("older", now - 30 * DAY)
            h.flush()
            assertEquals(listOf("old"), texts(h)) // the 30-day-old one expired at once
            // Nothing else happens: the arranged expiry drops "old" once its time has come.
            now += 1000
            kotlinx.coroutines.delay(1500)
            h.flush()
            assertTrue(h.items.value.isEmpty())
        }
        assertFalse(file.exists())
    }

    @Test
    fun aLeftoverTemporaryFileIsDeleted() { // CLIP-22
        withHistory { h -> h.add("one", now) }
        val tmp = File(file.path + ".tmp").apply { writeText("partial") }
        withHistory { h -> assertEquals(listOf("one"), texts(h)) }
        assertFalse(tmp.exists())
    }

    @Test
    fun editingKeepsPinAndMergesATwin() { // CLIP-23
        withHistory { h ->
            h.add("one", now)
            h.add("two", now + 1)
            h.flush()
            val one = h.items.value.first { it.text == "one" }
            h.pin(one.id, true)
            h.edit(one.id, "Your code is 482913")
            h.flush()
            val edited = h.items.value.first { it.id == one.id }
            assertTrue(edited.pinned)
            assertEquals(ClipKind.CODE, edited.kind)
            // Editing it into another item's text merges the two.
            h.edit(one.id, "two")
            h.edit(one.id, " ") // blank text is ignored
            h.flush()
            assertEquals(listOf("two"), texts(h))
            assertTrue(h.items.value.single().pinned)
        }
    }

    @Test
    fun severalItemsAreDeletedAtOnceAndPhrasesToggle() { // CLIP-24
        withHistory { h ->
            listOf("a", "b", "c").forEachIndexed { i, t -> h.add(t, now + i) }
            h.flush()
            val ids = h.items.value.filter { it.text != "b" }.map { it.id }
            h.delete(ids)
            h.flush()
            assertEquals(listOf("b"), texts(h))
            val b = h.items.value.single()
            h.setPhrase(b.id, true)
            now += 30 * DAY
            h.expire()
            h.flush()
            assertEquals("a phrase never expires", listOf("b"), texts(h))
            h.setPhrase(b.id, false)
            h.flush()
            assertTrue(h.items.value.isEmpty())
        }
    }

    private fun fakePng(tag: Byte): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        tag, 0x01, 0x02, 0x03,
    ) + "kotomo-secret-image-marker-$tag".toByteArray()

    private val imagesDir get() = File(file.parentFile, "images")

    @Test
    fun imageClipIsEncryptedAndSurvivesReopening() { // CLIP-28
        val raw = fakePng(1)
        withHistory { h ->
            val copied = h.addImage(raw, "IMAGE/PNG", 120, 80, now)
            h.flush()
            assertNotNull(copied?.image)
            val clip = h.items.value.single()
            assertTrue(clip.isImage)
            assertEquals(ClipKind.IMAGE, clip.kind)
            assertEquals("image/png", clip.image?.mime)
            assertEquals(120, clip.image?.width)
            assertEquals(80, clip.image?.height)

            val encFile = File(imagesDir, clip.image!!.file)
            assertTrue(encFile.isFile)
            val encBytes = encFile.readBytes()
            assertArrayEquals("KCLI".toByteArray(), encBytes.copyOfRange(0, 4))
            assertFalse(String(encBytes, Charsets.ISO_8859_1).contains("kotomo-secret-image-marker"))
            assertArrayEquals(raw, h.readImageBytes(clip))
        }
        val header = ClipFile.open(file.readBytes(), key).first()
        assertEquals("2", header["format_version"])
        withHistory { h ->
            val clip = h.items.value.single()
            assertEquals(ClipKind.IMAGE, clip.kind)
            assertArrayEquals(raw, h.readImageBytes(clip))
        }
    }

    @Test
    fun imageClipDedupesAndKeepsPin() { // CLIP-29
        val a = fakePng(1)
        val b = fakePng(2)
        withHistory { h ->
            h.addImage(a, "image/png", 10, 10, now)
            h.flush()
            val aId = h.items.value.single().id
            h.pin(aId, true)
            h.addImage(b, "image/jpeg", 20, 20, now + 1)
            // Same image copied again moves to front and keeps its pin.
            h.addImage(a, "image/png", 10, 10, now + 2)
            // Same timestamp is a no-op.
            assertNull(h.addImage(a, "image/png", 10, 10, now + 2))
            h.flush()
            assertEquals(2, h.items.value.size)
            assertTrue(h.items.value.first().pinned)
            assertEquals(10, h.items.value.first().image?.width)
            assertEquals(2, imagesDir.listFiles()?.size)
        }
    }

    @Test
    fun expiringDeletingAndCapRemoveImageFiles() { // CLIP-30
        withHistory { h ->
            h.addImage(fakePng(100), "image/png", 32, 32, now)
            h.flush()
            val pinnedClip = h.items.value.single()
            h.pin(pinnedClip.id, true)

            repeat(ClipboardHistory.MAX_IMAGES + 3) { i ->
                h.addImage(fakePng((i + 1).toByte()), "image/png", 16, 16, now + 1 + i)
            }
            h.flush()
            val images = h.items.value.filter { it.isImage }
            assertEquals(ClipboardHistory.MAX_IMAGES + 1, images.size)
            assertTrue(images.any { it.id == pinnedClip.id })
            assertEquals(images.size, imagesDir.listFiles()?.size)

            h.clearUnpinned()
            h.flush()
            assertEquals(listOf(pinnedClip.image?.file), imagesDir.listFiles()?.map { it.name })

            h.delete(pinnedClip.id)
            h.flush()
            assertFalse(imagesDir.exists())
            assertFalse(file.exists())
        }
    }

    @Test
    fun invalidAndHistoryOffImagesWriteNoFiles() { // CLIP-31 (negative)
        withHistory { h ->
            assertNull(h.addImage(fakePng(1), "image/svg+xml", 16, 16, now))
            assertNull(h.addImage(ByteArray(0), "image/png", 16, 16, now))
            assertNull(h.addImage(fakePng(1), "image/png", 0, 16, now))
            assertNull(h.addImage(fakePng(1), "image/png", ClipboardHistory.MAX_IMAGE_EDGE + 1, 16, now))
            h.flush()
            assertTrue(h.items.value.isEmpty())
            assertFalse(imagesDir.exists())
        }
        // Traversal filename in a forged/damaged record is rejected cleanly when loading.
        val forged = listOf(
            mapOf("format_version" to "2"),
            mapOf("id" to "1", "text" to "", "time_utc_ms" to now.toString(), "pinned" to "0", "phrase" to "0", "image" to "../evil.kci", "mime" to "image/png", "width" to "10", "height" to "10"),
        )
        file.parentFile!!.mkdirs()
        file.writeBytes(ClipFile.seal(forged, key))
        withHistory { h -> assertTrue(h.items.value.isEmpty()) }

        // History off writes nothing to disk, while still offering the in-memory image clip.
        settings = ClipboardSettings(history = false)
        withHistory { h ->
            val copied = h.addImage(fakePng(7), "image/jpg", 50, 40, now + 10)
            h.flush()
            assertEquals("image/jpeg", copied?.image?.mime)
            assertNull(copied?.image?.file)
            assertTrue(h.items.value.isEmpty())
            assertFalse(imagesDir.exists())
        }
    }

    @Test
    fun mimeMatchingAndFallbackSelection() { // CLIP-32
        assertTrue(ClipboardHistory.mimeMatches("image/png", "IMAGE/*"))
        assertTrue(ClipboardHistory.mimeMatches("image/webp; charset=binary", "image/webp"))
        assertTrue(ClipboardHistory.mimeMatches("image/gif", "*/*"))
        assertFalse(ClipboardHistory.mimeMatches("image/png", "text/plain"))
        assertEquals("image/webp", ClipboardHistory.chooseCommitMime("image/webp", listOf("image/png", "image/webp")))
        assertEquals("image/png", ClipboardHistory.chooseCommitMime("image/webp", listOf("image/png")))
        assertEquals("image/jpeg", ClipboardHistory.chooseCommitMime("image/png", listOf("image/jpeg")))
        assertNull(ClipboardHistory.chooseCommitMime("image/gif", listOf("image/png", "image/jpeg")))
        assertEquals("image/gif", ClipboardHistory.chooseCommitMime("image/gif", listOf("image/*")))
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
