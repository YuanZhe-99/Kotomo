package com.yuanzhe.kotomo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks how long text is split into parts the GenAI APIs take (PLAN §11.1, their 256-token limit). */
class GenAiTest {
    @Test
    fun shortTextStaysWhole() {
        assertEquals(listOf("Hello there." to ""), GenAi.split("Hello there."))
    }

    @Test
    fun longTextSplitsAtSentenceEndsAndJoinsBackUnchanged() {
        val sentence = "This sentence is about forty characters. "
        val text = sentence.repeat(30) + "これは日本語の文です。" + "最後の行\n"
        val parts = GenAi.split(text)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.first.length <= 400 })
        assertEquals(text, parts.joinToString("") { it.first + it.second })
        assertTrue(parts.dropLast(1).all { it.first.endsWith(". ") || it.first.endsWith("。") || it.first.endsWith(".") })
    }
}
