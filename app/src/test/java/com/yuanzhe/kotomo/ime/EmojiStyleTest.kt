package com.yuanzhe.kotomo.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the emoji style setting and the font check of an import (T14-emoji-pack, PLAN §11.5). */
class EmojiStyleTest {
    @Test
    fun configRoundTripsAndKeepsUnknownMembers() { // T14-emoji-pack
        val c = EmojiStyleConfig(EmojiStyle.CUSTOM, "My font", mapOf("future" to 1L))
        val back = EmojiStyleFormat.read(EmojiStyleFormat.write(c, 5L))!!
        assertEquals(EmojiStyle.CUSTOM, back.style)
        assertEquals("My font", back.customName)
        assertEquals(1L, back.extra["future"])
        assertEquals(EmojiStyle.SYSTEM, EmojiStyleFormat.read("""{"style":"apple"}""")!!.style)
    }

    @Test
    fun onlyFontFilesAreImported() { // T14-emoji-pack (negative)
        assertTrue(EmojiStyleFormat.looksLikeFont(byteArrayOf(0, 1, 0, 0, 9)))
        assertTrue(EmojiStyleFormat.looksLikeFont("OTTO".toByteArray()))
        assertFalse(EmojiStyleFormat.looksLikeFont("\u0089PNG".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(EmojiStyleFormat.looksLikeFont(byteArrayOf(0, 1)))
    }

    @Test
    fun theNotoPackIsPinned() { // T14-emoji-pack: the download is checked against a fixed size and hash
        assertTrue(EmojiStyles.NOTO_URL.startsWith("https://" + EmojiStyles.NOTO_HOST + "/"))
        assertEquals(64, EmojiStyles.NOTO_SHA256.length)
        assertTrue(EmojiStyles.NOTO_SIZE > 0)
    }
}
