package com.yuanzhe.kotomo.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T14-emoji-panel (PLAN §10.6): favorites, combinations and mappings as stored and read back. */
class EmojiExtrasTest {
    @Test
    fun favoritesToggleAndStayBounded() {
        val one = EmojiExtras.parseFavorites(EmojiExtras.toggleFavorite(emptyList(), "🍎"))
        assertEquals(listOf("🍎"), one)
        val two = EmojiExtras.parseFavorites(EmojiExtras.toggleFavorite(one, "🐱"))
        assertEquals(listOf("🍎", "🐱"), two)
        // Toggling again removes it.
        assertEquals(listOf("🐱"), EmojiExtras.parseFavorites(EmojiExtras.toggleFavorite(two, "🍎")))
        // Negative: blank, repeated and overlong lines are dropped; the cap keeps the newest.
        assertEquals(listOf("🍎"), EmojiExtras.parseFavorites("\n🍎\n 🍎 \n" + "x".repeat(40)))
        val many = (0 until 70).map { "e$it" }
        val capped = EmojiExtras.parseFavorites(EmojiExtras.toggleFavorite(many.take(64), "new"))
        assertEquals(EmojiExtras.MAX_FAVORITES, capped.size)
        assertEquals("new", capped.last())
    }

    @Test
    fun combosKeepTheirOrder() {
        assertEquals(listOf("🎉🎂", "👍👍"), EmojiExtras.parseCombos("🎉🎂\n\n👍👍\n🎉🎂"))
        assertTrue(EmojiExtras.COMBOS.isNotEmpty() && EmojiExtras.KAOMOJI.isNotEmpty())
    }

    @Test
    fun mappingsReadWordsAndEmoji() {
        val m = EmojiExtras.parseMappings("Neko=🐈\nneko=😺\ncafe=☕\n=🍎\nno space=🍎\n中文=🍎\nempty=\nneko=🐈")
        assertEquals(mapOf("neko" to listOf("🐈", "😺"), "cafe" to listOf("☕")), m)
        assertEquals("cafe=☕\nneko=🐈\nneko=😺", EmojiExtras.formatMappings(m))
        assertEquals(m, EmojiExtras.parseMappings(EmojiExtras.formatMappings(m)))
    }

    @Test
    fun mappedEmojiComeFirstAmongSuggestions() {
        val mine = mapOf("zz" to listOf("🦄"), "cat" to listOf("🐈‍⬛"))
        assertEquals(listOf("🦄"), Candidates.emoji(null, "zz", mine))
        assertEquals("🐈‍⬛", Candidates.emoji(null, "Cat", mine).first())
        // Negative: one letter suggests nothing, and other letters do not take a mapping.
        assertEquals(emptyList<String>(), Candidates.emoji(null, "z", mapOf("z" to listOf("🦄"))))
        assertEquals(emptyList<String>(), Candidates.emoji(null, "zy", mine))
    }
}
