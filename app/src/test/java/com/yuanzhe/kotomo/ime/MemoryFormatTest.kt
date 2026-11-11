package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.dictionary.Json
import com.yuanzhe.kotomo.settings.ChineseScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the keyboard's memory file (PLAN §2.3, the behavior contract's versioned formats): round
 * trip, unknown members and values kept, a newer version kept, damaged files, the move from the
 * SharedPreferences of older builds, and the emoji history's cap.
 */
class MemoryFormatTest {
    @get:Rule val folder = TemporaryFolder()

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String) = Json.parse(text) as Map<String, Any?>

    @Test
    fun roundTrip() {
        val memory = Memory(
            lastMode = Mode.JAPANESE,
            apps = mapOf("com.example.chat" to AppMemory(mode = Mode.CHINESE, pinned = Mode.ENGLISH, script = ChineseScript.TAIWAN)),
            emoji = EmojiMemory(listOf("😀", "🐱")),
        )
        // The version read back is kept in extra, so a rewrite never lowers it.
        assertEquals(memory, MemoryFormat.read(MemoryFormat.write(memory, 1000L))!!.copy(extra = emptyMap()))
        val written = parse(MemoryFormat.write(memory, 1000L))
        assertEquals(1L, written["format_version"])
        assertEquals(1000L, written["updated_utc_ms"])
        assertEquals("japanese", written["last_mode"])
    }

    @Test
    fun unknownMembersValuesAndNewerVersionsStay() {
        val text = """{"format_version": 4, "last_mode": "korean", "sync": {"on": true},
            "apps": {"a.b": {"mode": "korean", "pinned_mode": "english", "layout": "flick"}},
            "emoji": {"recent": ["👍"], "style": "noto"}}"""
        val memory = MemoryFormat.read(text)!!
        assertNull(memory.lastMode)
        assertNull(memory.app("a.b").mode)
        assertEquals(Mode.ENGLISH, memory.app("a.b").pinned)
        val changed = memory.withApp("a.b") { it.copy(script = ChineseScript.HONG_KONG) }
        val written = parse(MemoryFormat.write(changed, 5L))
        assertEquals(4L, written["format_version"])
        assertEquals("korean", written["last_mode"])
        assertEquals(mapOf("on" to true), written["sync"])
        @Suppress("UNCHECKED_CAST")
        val app = (written["apps"] as Map<String, Any?>)["a.b"] as Map<String, Any?>
        assertEquals("korean", app["mode"])
        assertEquals("flick", app["layout"])
        assertEquals("hong_kong", app["script"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("noto", (written["emoji"] as Map<String, Any?>)["style"])
        // A new value replaces the one this version could not read.
        val replaced = parse(MemoryFormat.write(changed.withApp("a.b") { it.copy(mode = Mode.CHINESE) }, 6L))
        @Suppress("UNCHECKED_CAST")
        assertEquals("chinese", ((replaced["apps"] as Map<String, Any?>)["a.b"] as Map<String, Any?>)["mode"])
    }

    @Test
    fun damagedFileIsMovedAsideAndReadsAsAbsent() {
        val file = File(folder.root, "keyboard/memory.json").apply { parentFile!!.mkdirs(); writeText("not json") }
        val memory = KeyboardMemory(file) { null }
        assertEquals(Memory(), memory.current)
        assertTrue(File(file.path + ".unreadable").isFile)
    }

    @Test
    fun olderPreferencesMoveOverAndStayUntouched() {
        val ime = mapOf("mode" to "CHINESE", "mode.com.a" to "JAPANESE", "script.com.a" to "TAIWAN", "mode.com.b" to "KLINGON", "other" to 3)
        val emoji = mapOf("format" to 1, "items" to "😀\n🐱\n😀\n")
        val memory = MemoryFormat.fromLegacy(ime, emoji)
        assertEquals(Mode.CHINESE, memory.lastMode)
        assertEquals(AppMemory(mode = Mode.JAPANESE, script = ChineseScript.TAIWAN), memory.app("com.a"))
        assertTrue(memory.app("com.b").empty)
        assertEquals(listOf("😀", "🐱"), memory.emoji.recent)
    }

    @Test
    fun storeWritesChangesAndReadsThemBack() {
        val file = File(folder.root, "memory.json")
        val memory = KeyboardMemory(file) { Memory(lastMode = Mode.ENGLISH) }
        assertEquals(Mode.ENGLISH, memory.current.lastMode)
        memory.update { m -> m.withApp("com.a") { it.copy(pinned = Mode.JAPANESE) } }
        // The write runs on the memory thread; a second store reads the file once it is there.
        repeat(50) { if (!file.isFile) Thread.sleep(20) }
        assertEquals(Mode.JAPANESE, KeyboardMemory(file) { null }.current.app("com.a").pinned)
        memory.update { m -> m.withApp("com.a") { it.copy(pinned = null) } }
        assertTrue(memory.current.apps.isEmpty())
    }

    @Test
    fun recentEmojiMoveToTheFrontAndAreCapped() {
        var memory = Memory()
        repeat(Memory.MAX_RECENT + 5) { memory = withRecent(memory, "e$it") }
        memory = withRecent(memory, "e10")
        assertEquals(Memory.MAX_RECENT, memory.emoji.recent.size)
        assertEquals("e10", memory.emoji.recent.first())
        assertEquals(1, memory.emoji.recent.count { it == "e10" })
    }
}
