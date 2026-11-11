package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.engine.English
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the candidate bar's lists (PLAN §8.2) and English tap positions (PLAN §9.7). */
class CandidatesTest {
    @Test
    fun chineseOffersTheTypedLettersAfterTheTwoBestWords() {
        val list = Candidates.chinese(listOf("中国", "种过", "种果"), "zhongguo", listOf("🐉"))
        assertEquals(listOf("中国" to 0, "种过" to 1, "zhongguo" to Candidates.TYPED, "🐉" to Candidates.EMOJI, "种果" to 2), list)
    }

    @Test
    fun chineseLeavesOutTypedLettersThatAreAlreadyACandidate() { // negative
        val list = Candidates.chinese(listOf("ok", "哦"), "ok", emptyList())
        assertEquals(listOf("ok" to 0, "哦" to 1), list)
        assertTrue(Candidates.chinese(emptyList(), "", emptyList()).isEmpty())
    }

    @Test
    fun englishKeepsTheTypedWordFirstAndItsCapital() {
        assertEquals(listOf("Helo", "Hello", "Help"), Candidates.english("Helo", listOf("hello", "help")))
        assertEquals(listOf("first", "most"), Candidates.english("", listOf("first", "most", "first")))
    }

    @Test
    fun tapsMapIntoTheEnglishEnginesLayout() {
        // The centre of r is (350, 75); a tap at its left edge lands next to e.
        assertEquals(350 to 75, English.touchPoint('r', 0f, 0f))
        assertEquals(300 to 75, English.touchPoint('r', -0.5f, 0f))
        // Second and third rows start half a key and one and a half keys in.
        assertEquals(200 to 225, English.touchPoint('s', 0f, 0f))
        assertEquals(200 to 375, English.touchPoint('Z', 0f, 0f))
        // Taps far outside stay inside the layout; characters without a key map to nothing.
        assertEquals(0 to 0, English.touchPoint('q', -5f, -5f))
        assertNull(English.touchPoint('1', 0f, 0f))
    }
}
