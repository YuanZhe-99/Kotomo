package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.settings.ChineseScript
import org.junit.Assert.assertEquals
import org.junit.Test

/** T23 (PLAN §12): the language voice typing starts in, and reading the recognizer's model lists. */
class VoiceLangTest {
    @Test
    fun startsInTheModesLanguage() {
        assertEquals(VoiceLang.JAPANESE, VoiceLang.forMode(Mode.JAPANESE, ChineseScript.SIMPLIFIED))
        assertEquals(VoiceLang.ENGLISH, VoiceLang.forMode(Mode.ENGLISH, ChineseScript.SIMPLIFIED))
        assertEquals(VoiceLang.ENGLISH, VoiceLang.forMode(Mode.PC, ChineseScript.SIMPLIFIED))
        assertEquals(VoiceLang.CHINESE_CN, VoiceLang.forMode(Mode.MIXED, ChineseScript.SIMPLIFIED))
        assertEquals(VoiceLang.CHINESE_TW, VoiceLang.forMode(Mode.CHINESE, ChineseScript.HONG_KONG))
    }

    @Test
    fun modelListsAreRead() {
        assertEquals(VoiceModel.INSTALLED, Voice.modelOf(VoiceLang.JAPANESE, listOf("en-US", "ja-JP"), emptyList(), emptyList()))
        assertEquals(VoiceModel.INSTALLED, Voice.modelOf(VoiceLang.CHINESE_CN, listOf("zh_CN"), emptyList(), emptyList()))
        assertEquals(VoiceModel.DOWNLOADING, Voice.modelOf(VoiceLang.ENGLISH, emptyList(), listOf("en-US"), listOf("en-US")))
        assertEquals(VoiceModel.DOWNLOADABLE, Voice.modelOf(VoiceLang.ENGLISH, listOf("ja-JP"), emptyList(), listOf("en-US")))
        // Negative: a recognizer that names other languages only does not offer this one.
        assertEquals(VoiceModel.UNSUPPORTED, Voice.modelOf(VoiceLang.CHINESE_TW, listOf("en-US"), emptyList(), listOf("ja-JP")))
        assertEquals(VoiceModel.UNKNOWN, Voice.modelOf(VoiceLang.ENGLISH, emptyList(), emptyList(), emptyList()))
    }
}
