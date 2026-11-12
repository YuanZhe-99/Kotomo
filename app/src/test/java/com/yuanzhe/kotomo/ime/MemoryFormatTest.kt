package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.dictionary.Json
import com.yuanzhe.kotomo.settings.ChineseScript
import com.yuanzhe.kotomo.settings.OneHanded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        var emoji = EmojiMemory()
        repeat(Memory.MAX_RECENT + 5) { emoji = emoji.sent("e$it") }
        emoji = emoji.sent("e10")
        assertEquals(Memory.MAX_RECENT, emoji.recent.size)
        assertEquals("e10", emoji.recent.first())
        assertEquals(1, emoji.recent.count { it == "e10" })
    }

    /** PLAN §8.6 "Frequently used": emoji sent twice or more, most used first, counts kept and capped. */
    @Test
    fun frequentlyUsedEmoji() {
        var emoji = EmojiMemory()
        listOf("🐱", "👍", "🐱", "😀", "👍", "🐱").forEach { emoji = emoji.sent(it) }
        assertEquals(listOf("🐱", "👍"), emoji.frequent())
        // Negative: an emoji sent once is recent but not frequent.
        assertTrue("😀" in emoji.recent && "😀" !in emoji.frequent())
        val read = MemoryFormat.read(MemoryFormat.write(Memory(emoji = emoji), 1L))!!
        assertEquals(mapOf("🐱" to 3, "👍" to 2, "😀" to 1), read.emoji.uses)
        repeat(Memory.MAX_USES + 10) { emoji = emoji.sent("x$it") }
        assertEquals(Memory.MAX_USES, emoji.uses.size)
        assertEquals(3, emoji.uses["🐱"])
    }

    /** PLAN §8.6 search: English, pinyin and romaji keys, spaces ignored; nothing for an empty query. */
    @Test
    fun emojiSearch() {
        val index = EmojiIndex.parse(
            sequenceOf("#kotomo-emoji\t1\temoji 18.0", "g\tanimals", "e\t🐱", "e\t🐶", "k\tcat\t0", "k\tcatface\t0", "k\tdog\t1", "k\tmao\t0", "k\tneko\t0"),
        )
        assertEquals(listOf("🐱"), Candidates.emojiSearch(index, "cat"))
        assertEquals(listOf("🐱"), Candidates.emojiSearch(index, "cat face"))
        assertEquals(listOf("🐱"), Candidates.emojiSearch(index, "neko"))
        assertEquals(listOf("🐶"), Candidates.emojiSearch(index, "DO"))
        assertEquals(emptyList<String>(), Candidates.emojiSearch(index, " "))
        assertEquals(emptyList<String>(), Candidates.emojiSearch(index, "zebra"))
    }

    /** PLAN §8.10: one-handed mode is kept per screen and orientation, with the old global side as fallback. */
    @Test
    fun oneHandedPerScreenAndOrientation() {
        val portrait = Posture.screenKey(411, landscape = false)
        val landscape = Posture.screenKey(411, landscape = true)
        val inner = Posture.screenKey(673, landscape = false)
        assertEquals("sw411-portrait", portrait)
        var memory = Memory().withOneHanded(portrait, OneHanded.OFF) { it.copy(side = OneHanded.RIGHT, widthPercent = 70) }
        memory = memory.withOneHanded(inner, OneHanded.OFF) { it.copy(widthPercent = 90) }
        val read = MemoryFormat.read(MemoryFormat.write(memory, 1L))!!
        assertEquals(OneHandedMemory(OneHanded.RIGHT, 70), read.oneHandedOn(portrait, OneHanded.OFF))
        assertEquals(OneHandedMemory(OneHanded.OFF, 90), read.oneHandedOn(inner, OneHanded.LEFT))
        // Negative: another orientation is not changed, and follows the settings' old side.
        assertEquals(OneHandedMemory(OneHanded.LEFT), read.oneHandedOn(landscape, OneHanded.LEFT))
        // An out-of-range width reads as the default.
        val bad = MemoryFormat.read("""{"one_handed": {"sw411-portrait": {"side": "left", "width_percent": 10}}}""")!!
        assertEquals(OneHandedMemory(OneHanded.LEFT, OneHandedMemory.DEFAULT_WIDTH, mapOf("width_percent" to 10L)), bad.oneHandedOn(portrait, OneHanded.OFF))
    }

    /**
     * T12-app (PLAN §10.10): an app's own settings (no learning, code) survive a rewrite, are
     * written only when on, and a value of the wrong type reads as off and is kept.
     */
    @Test
    fun appSettingsRoundTrip() {
        val memory = Memory(apps = mapOf("com.term" to AppMemory(incognito = true, code = true), "com.chat" to AppMemory(mode = Mode.CHINESE)))
        val written = parse(MemoryFormat.write(memory, 1L))
        @Suppress("UNCHECKED_CAST")
        val apps = written["apps"] as Map<String, Map<String, Any?>>
        assertEquals(mapOf("incognito" to true, "code" to true), apps["com.term"])
        assertEquals(mapOf("mode" to "chinese"), apps["com.chat"])
        assertEquals(memory.apps, MemoryFormat.read(MemoryFormat.write(memory, 1L))!!.apps)
        assertTrue(memory.app("com.term").custom)
        assertFalse(memory.app("com.chat").custom)
        val odd = MemoryFormat.read("""{"apps": {"a.b": {"incognito": "yes"}}}""")!!.app("a.b")
        assertFalse(odd.incognito)
        assertEquals(mapOf("incognito" to "yes"), odd.extra)
        // Turning both off empties the entry, so it goes.
        assertTrue(memory.withApp("com.term") { it.copy(incognito = false, code = false) }.apps.keys == setOf("com.chat"))
    }
}
