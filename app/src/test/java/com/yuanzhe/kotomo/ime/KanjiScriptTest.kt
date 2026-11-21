package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.settings.ChineseScript
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Checks CJK kanji script conversion and shared `中日` detection (`KanjiScript`, Step 4). */
class KanjiScriptTest {
    @Test
    fun convertsChineseToJapaneseShinjitai() {
        assertEquals("天気", script.toJapanese("天气"))
        assertEquals("東京", script.toJapanese("东京"))
        assertEquals("学習", script.toJapanese("学习"))
        assertEquals("図書館", script.toJapanese("图书馆"))
        assertEquals("電話", script.toJapanese("电话"))
        assertEquals("頭髪", script.toJapanese("头发"))
        assertEquals("氷", script.toJapanese("冰"))
        assertEquals("天気", script.toJapanese("天氣"))
    }

    @Test
    fun preservesRoundTripSharedAndIdenticalWords() {
        assertNull(script.toJapanese("内容"))
        assertNull(script.toJapanese("猫"))
        assertNull(script.toJapanese("日本"))
        assertNull(script.toJapanese("学生"))
        assertNull(script.toJapanese("世界"))
        assertNull(script.toJapanese("天気"))
        assertNull(script.toJapanese("coffee"))
        assertNull(script.toJapanese(""))
    }

    @Test
    fun convertsJapaneseToChineseByScript() {
        assertEquals("天气", script.toChinese("天気", ChineseScript.SIMPLIFIED))
        assertEquals("天氣", script.toChinese("天気", ChineseScript.TRADITIONAL))
        assertEquals("勉强", script.toChinese("勉強", ChineseScript.SIMPLIFIED))
        assertNull(script.toChinese("勉強", ChineseScript.TRADITIONAL))
        assertEquals("读卖", script.toChinese("読売", ChineseScript.SIMPLIFIED))
        assertEquals("讀賣", script.toChinese("読売", ChineseScript.TRADITIONAL))
        assertEquals("东京", script.toChinese("東京", ChineseScript.SIMPLIFIED))
        assertNull(script.toChinese("東京", ChineseScript.TRADITIONAL))
        assertEquals("冰", script.toChinese("氷", ChineseScript.SIMPLIFIED))
        assertEquals("邻", script.toChinese("隣", ChineseScript.SIMPLIFIED))
        assertNull(script.toChinese("天才", ChineseScript.TRADITIONAL))
        assertNull(script.toChinese("食べる", ChineseScript.SIMPLIFIED))
    }

    @Test
    fun detectsSharedKanjiExcludingDifferencesAndKokuji() {
        assertTrue(script.isSharedJapaneseKanji("日本", ChineseScript.SIMPLIFIED))
        assertTrue(script.isSharedJapaneseKanji("学生", ChineseScript.SIMPLIFIED))
        assertTrue(script.isSharedJapaneseKanji("世界", ChineseScript.SIMPLIFIED))
        assertTrue(script.isSharedJapaneseKanji("日本", ChineseScript.TRADITIONAL))

        assertFalse(script.isSharedJapaneseKanji("学生", ChineseScript.TRADITIONAL))
        assertFalse(script.isSharedJapaneseKanji("天気", ChineseScript.SIMPLIFIED))
        assertFalse(script.isSharedJapaneseKanji("氷", ChineseScript.SIMPLIFIED))
        assertFalse(script.isSharedJapaneseKanji("働く", ChineseScript.SIMPLIFIED))
        assertFalse(script.isSharedJapaneseKanji("畑", ChineseScript.SIMPLIFIED))
        assertFalse(script.isSharedJapaneseKanji("込", ChineseScript.SIMPLIFIED))
        assertFalse(script.isSharedJapaneseKanji("coffee", ChineseScript.SIMPLIFIED))
    }

    companion object {
        private lateinit var script: KanjiScript

        @BeforeClass
        @JvmStatic
        fun loadTables() {
            val dir = listOf(
                File("third_party/opencc/data/dictionary"),
                File("../third_party/opencc/data/dictionary"),
            ).first { it.isDirectory }
            val loaded = KanjiScript.load(dir)
            assertNotNull("OpenCC dictionary directory not loaded from $dir", loaded)
            script = loaded!!
        }
    }
}
