package com.yuanzhe.kotomo.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks the translate panel's language guess and default target (T18, PLAN §11.1). */
class TranslationTest {
    @Test
    fun guessesTheLanguageTheTextIsWrittenIn() { // T18-translate-guess
        val cases = listOf(
            "今天天气很好" to TranslateLang.CHINESE,
            "今日はいい天気です" to TranslateLang.JAPANESE,
            "東京へ行きます" to TranslateLang.JAPANESE, // kana decides over kanji
            "The weather is nice" to TranslateLang.ENGLISH,
            "我用 Android 手机" to TranslateLang.CHINESE, // more characters than Latin words
            "Pay with 微信 at the shop" to TranslateLang.ENGLISH,
            "12345" to TranslateLang.ENGLISH,
        )
        cases.forEach { (text, lang) -> assertEquals(text, lang, TranslateLang.guess(text)) }
    }

    @Test
    fun translatesIntoEnglishOrFromEnglishIntoChinese() { // T18-translate-target
        assertEquals(TranslateLang.CHINESE, TranslateLang.defaultTarget(TranslateLang.ENGLISH))
        assertEquals(TranslateLang.ENGLISH, TranslateLang.defaultTarget(TranslateLang.CHINESE))
        assertEquals(TranslateLang.ENGLISH, TranslateLang.defaultTarget(TranslateLang.JAPANESE))
    }

    @Test
    fun everyPairNeedsEnglishAsThePivot() { // T18-translate-pivot
        assertEquals(setOf(TranslateLang.CHINESE, TranslateLang.JAPANESE, TranslateLang.ENGLISH), Translations.needs(TranslateLang.CHINESE, TranslateLang.JAPANESE))
        assertEquals(setOf(TranslateLang.ENGLISH, TranslateLang.JAPANESE), Translations.needs(TranslateLang.ENGLISH, TranslateLang.JAPANESE))
    }
}
