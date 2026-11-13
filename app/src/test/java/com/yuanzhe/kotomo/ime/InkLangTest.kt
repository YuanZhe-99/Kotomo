package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.settings.ChineseScript
import org.junit.Assert.assertEquals
import org.junit.Test

/** T22 (PLAN §12): which handwriting model the panel opens with. */
class InkLangTest {
    @Test
    fun japaneseModeWritesJapanese() {
        assertEquals(InkLang.JAPANESE, InkLang.forMode(Mode.JAPANESE, ChineseScript.SIMPLIFIED))
    }

    @Test
    fun otherModesWriteChineseInThePersonsScript() {
        assertEquals(InkLang.CHINESE_CN, InkLang.forMode(Mode.MIXED, ChineseScript.SIMPLIFIED))
        assertEquals(InkLang.CHINESE_TW, InkLang.forMode(Mode.CHINESE, ChineseScript.TRADITIONAL))
        assertEquals(InkLang.CHINESE_TW, InkLang.forMode(Mode.ENGLISH, ChineseScript.TAIWAN))
        assertEquals(InkLang.CHINESE_HK, InkLang.forMode(Mode.CHINESE, ChineseScript.HONG_KONG))
    }
}
