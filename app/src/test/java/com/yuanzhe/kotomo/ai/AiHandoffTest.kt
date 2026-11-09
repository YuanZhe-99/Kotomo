package com.yuanzhe.kotomo.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Checks that the AI hand-off keeps the selected text only as long as it is needed (PLAN §14). */
class AiHandoffTest {
    private val request = AiRequest("app", 1, "Teh text", 0, 8, AiLanguage.ENGLISH)

    @After
    fun reset() = AiHandoff.clear()

    @Test
    fun closingTheScreenWithoutReplaceDropsTheText() { // AI-01
        AiHandoff.begin(request)
        AiHandoff.dropUnconfirmed()
        assertNull(AiHandoff.request)
        assertNull(AiHandoff.take())
    }

    @Test
    fun aConfirmedResultStillReachesTheKeyboard() { // AI-02 (negative)
        AiHandoff.begin(request)
        AiHandoff.confirm("The text")
        AiHandoff.dropUnconfirmed()
        assertEquals(request to "The text", AiHandoff.take())
        assertNull(AiHandoff.request)
    }
}
