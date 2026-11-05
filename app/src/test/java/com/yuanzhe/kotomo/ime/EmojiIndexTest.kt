package com.yuanzhe.kotomo.ime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Covers the emoji index of P1 (PLAN.md §8.6, §10.6): groups, tones and three-language keys. */
class EmojiIndexTest {
    private val fixture = """
        #kotomo-emoji	1	emoji 18.0	cldr 49.0.0
        g	smileys
        e	😀
        e	😄
        g	people
        e	👋	👋🏻 👋🏼 👋🏽 👋🏾 👋🏿
        g	component
        g	animals
        e	🐱
        k	cat	3
        k	hello	2
        k	neko	3
        k	smile	1,0
        k	smiling	0
        k	warau	1,0
        k	xiao	1
        k	ねこ	3
        k	笑	1
    """.trimIndent().lineSequence()

    @Test
    fun groupsKeepOrderAndDropEmptyOnes() {
        val index = EmojiIndex.parse(fixture)
        assertEquals(listOf("smileys", "people", "animals"), index.groups.map { it.id })
        assertEquals(listOf("😀", "😄"), index.groups[0].emoji)
    }

    @Test
    fun tonesBelongToTheirBase() {
        val index = EmojiIndex.parse(fixture)
        assertEquals(5, index.variants("👋").size)
        assertTrue(index.variants("😄").isEmpty())
    }

    @Test
    fun findsEmojiInEachLanguage() {
        val index = EmojiIndex.parse(fixture)
        assertEquals("😄", index.lookup("smile").first())
        assertEquals("😄", index.lookup("xiao").first())
        assertEquals("😄", index.lookup("warau").first())
        assertEquals("😄", index.lookup("笑").first())
        assertEquals(listOf("🐱"), index.lookup("neko"))
        assertEquals(listOf("🐱"), index.lookup("ねこ"))
        assertEquals(listOf("🐱"), index.lookup("CAT"))
    }

    @Test
    fun prefixFindsLongerKeysAfterExactOnes() {
        val index = EmojiIndex.parse(fixture)
        assertEquals(listOf("😄", "😀"), index.lookup("smil"))
        assertEquals(listOf("😄"), index.lookup("smile", limit = 1))
    }

    @Test
    fun unknownOrEmptyKeysFindNothing() {
        val index = EmojiIndex.parse(fixture)
        assertTrue(index.lookup("zzz").isEmpty())
        assertTrue(index.lookup("").isEmpty())
        assertTrue(index.lookup("  ").isEmpty())
        assertTrue(index.lookup("smilex").isEmpty())
        assertTrue(index.lookup("smile", limit = 0).isEmpty())
    }

    @Test
    fun unrenderableEmojiAreDropped() {
        val index = EmojiIndex.parse(fixture) { it != "😄" && it != "👋🏿" }
        assertEquals(listOf("😀"), index.groups[0].emoji)
        assertEquals(listOf("😀"), index.lookup("smile"))
        assertFalse("👋🏿" in index.variants("👋"))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsAnUnknownFormat() {
        EmojiIndex.parse(sequenceOf("#kotomo-emoji\t2\temoji 99", "g\tsmileys"))
    }

    /** Runs on the asset that `./gradlew :app:emojiData` builds (it is build output). */
    @Test
    fun builtAssetAnswersPlanExamples() {
        val asset = File("build/generated/emoji/emoji.tsv")
        assumeTrue("run :app:emojiData first", asset.isFile)
        val index = asset.bufferedReader().useLines { EmojiIndex.parse(it.toList().asSequence()) }
        val expected = mapOf(
            // CLDR's ja keywords for 😄 are スマイル, ハッピー, わーい, 笑顔, 顔; 笑う belongs to 😀.
            "smile" to "😄", "xiao" to "😄", "egao" to "😄", "えがお" to "😄", "笑顔" to "😄", "warau" to "😀",
            "apple" to "🍎", "pingguo" to "🍎", "ringo" to "🍎",
            "cat" to "🐱", "mao" to "🐱", "neko" to "🐱",
            "sakura" to "🌸", "yinghua" to "🌸",
            "birthday" to "🎂", "shengri" to "🎂",
            "coffee" to "☕", "kafei" to "☕",
            "computer" to "💻", "diannao" to "💻",
        )
        for ((key, emoji) in expected) {
            assertTrue("$key → $emoji", emoji in index.lookup(key, limit = 24))
        }
        assertTrue(index.lookup("qwxz").isEmpty())
        assertEquals(9, index.groups.size)
        assertTrue(index.variants("👍").size == 5)
    }
}
