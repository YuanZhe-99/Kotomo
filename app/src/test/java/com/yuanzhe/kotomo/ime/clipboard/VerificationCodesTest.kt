package com.yuanzhe.kotomo.ime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the verification-code extractor (PLAN §10.7, tests CODE-01 to CODE-14): positives in
 * Chinese, English and Japanese, ambiguous messages ranked with context, and negatives (no keyword,
 * dates, prices, phone numbers, order numbers).
 */
class VerificationCodesTest {
    private fun values(text: String) = VerificationCodes.find(text).map { it.value }

    @Test
    fun chineseMessageRanksTheCodeBeforeTheAccountTail() { // CODE-01
        val codes = VerificationCodes.find("【某银行】您的验证码为482913，5分钟内有效。尾号7731的账户正在登录，请勿泄露。")
        assertEquals(listOf("482913", "7731"), codes.map { it.value })
        assertTrue(codes[0].score > codes[1].score)
        assertTrue(codes[0].context.contains("验证码为482913"))
        assertTrue(codes[1].context.contains("尾号7731"))
    }

    @Test
    fun englishMessageWithPrefix() { // CODE-02
        assertEquals(listOf("482913"), values("G-482913 is your Google verification code."))
        assertEquals(listOf("482913"), values("Your verification code is G-482913"))
    }

    @Test
    fun japaneseFullWidthDigits() { // CODE-03
        assertEquals(listOf("482913"), values("【Kotomo】認証コード：４８２９１３　有効期限は10分です。"))
    }

    @Test
    fun alphanumericCode() { // CODE-04
        assertEquals(listOf("A7K29Q"), values("確認コードは A7K29Q です。"))
        assertEquals(listOf("K7Q2"), values("Your one-time passcode: K7Q2"))
    }

    @Test
    fun groupedCodeIsJoined() { // CODE-05
        assertEquals(listOf("123456"), values("Your code: 123-456"))
        assertEquals(listOf("123456"), values("Your code: 123 456"))
    }

    @Test
    fun englishKeywordInsideChineseText() { // CODE-06
        assertEquals(listOf("392017"), values("您的OTP为392017，请勿告诉他人"))
    }

    @Test
    fun bareCodeNeedsNoKeyword() { // CODE-07
        assertEquals(listOf("482913"), values(" 482913 "))
        assertEquals(listOf("482913"), values("482-913"))
    }

    @Test
    fun noKeywordNoCode() { // CODE-08 (negative)
        assertEquals(emptyList<String>(), values("Meeting in room 4821 at 10:30"))
        assertEquals(emptyList<String>(), values("会议定在2026年11月4日下午3点，地点在4楼4021室"))
        assertEquals(emptyList<String>(), values("Call 138 0000 0000"))
    }

    @Test
    fun datesAreNotCodes() { // CODE-09 (negative)
        assertEquals(listOf("5531"), values("验证码已于2026年11月4日发送：5531"))
        assertEquals(listOf("7731"), values("Use 7731 to verify. Sent 2026-11-04 and 2026/11/04."))
    }

    @Test
    fun pricesAreNotCodes() { // CODE-10 (negative)
        assertEquals(listOf("4821"), values("Your code 4821 — total ¥3500"))
        assertEquals(listOf("482913"), values("Your verification code is 482913. Order total 1,299.00 USD, or $1299."))
        assertEquals(listOf("6624"), values("認証コード 6624（ご請求額 3500円）"))
    }

    @Test
    fun phoneNumbersAreNotCodes() { // CODE-11 (negative)
        assertEquals(listOf("663920"), values("验证码 663920，如有疑问请致电 400-820-8820"))
        assertEquals(listOf("824613"), values("验证码已发送至 138 0000 0000，请查收：824613"))
        assertEquals(listOf("5120"), values("Your code is 5120. Questions? +1 415 555 0100"))
    }

    @Test
    fun orderNumbersAreNotCodes() { // CODE-12 (negative)
        assertEquals(listOf("7731"), values("Use 7731 to verify your account. Order #12345678 ships tomorrow."))
        assertEquals(listOf("208841"), values("您的取货验证码 208841。订单号 55203317 已发货"))
    }

    @Test
    fun longTextIsNotScanned() { // CODE-13 (negative)
        assertEquals(emptyList<String>(), values("code 482913 " + "x".repeat(5000)))
    }

    @Test
    fun contextIsCutWithEllipses() { // CODE-14
        val code = VerificationCodes.find("Hello there, this is a long message. Your code is 482913. It expires in ten minutes. Thanks!")[0]
        assertTrue(code.context.startsWith("…"))
        assertTrue(code.context.endsWith("…"))
        assertTrue(code.context.contains("482913"))
    }
}
