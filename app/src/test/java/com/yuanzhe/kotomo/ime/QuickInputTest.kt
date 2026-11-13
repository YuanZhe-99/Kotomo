package com.yuanzhe.kotomo.ime

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T13-quick (PLAN §10.4, §10.8): the quick-input conversions, each with a negative case. */
class QuickInputTest {
    private val now = LocalDateTime.of(2026, 11, 11, 22, 5)

    @Test
    fun chineseNumerals() {
        assertEquals("十", QuickInput.lower("10"))
        assertEquals("十五", QuickInput.lower("15"))
        assertEquals("一百一十", QuickInput.lower("110"))
        assertEquals("一千零一", QuickInput.lower("1001"))
        assertEquals("一万零一", QuickInput.lower("10001"))
        assertEquals("一亿", QuickInput.lower("100000000"))
        assertEquals("一亿零一十万", QuickInput.lower("100100000"))
        assertEquals("零", QuickInput.lower("0"))
        assertEquals(listOf("一千二百三十四点五", "壹仟贰佰叁拾肆元伍角", "一二三四点五", "1,234.5"), QuickInput.candidates("1234.5", now))
    }

    @Test
    fun capitalMoney() {
        assertEquals("壹佰元整", QuickInput.money("100", ""))
        assertEquals("壹仟零伍元零陆分", QuickInput.money("1005", "06"))
        assertEquals("伍角", QuickInput.money("0", "5"))
        assertEquals("壹拾元整", QuickInput.money("10", ""))
        // Negative: more than two decimals has no money form.
        assertNull(QuickInput.money("1", "234"))
    }

    @Test
    fun datesTimesUnicodeAndArithmetic() {
        val date = QuickInput.candidates("rq", now)
        assertEquals("2026年11月11日", date[0])
        assertTrue("2026-11-11" in date && "二〇二六年十一月十一日" in date)
        assertEquals(listOf("22:05", "22时5分", "2026-11-11 22:05"), QuickInput.candidates("sj", now))
        assertEquals("星期三", QuickInput.candidates("xq", now)[0])
        assertEquals(listOf("中", "U+4E2D"), QuickInput.candidates("u4e2d", now))
        assertEquals(listOf("7", "1+2*3=7"), QuickInput.candidates("1+2*3", now))
        assertEquals("2.5", QuickInput.calculate("5/2"))
        assertEquals("512", QuickInput.calculate("2^3^2"))
        // Negative: nonsense, a control character, division by zero.
        assertEquals(emptyList<String>(), QuickInput.candidates("zzz", now))
        assertEquals(emptyList<String>(), QuickInput.candidates("u1", now))
        assertNull(QuickInput.calculate("1/0"))
        assertNull(QuickInput.calculate("1+"))
    }

    @Test
    fun customReplacements() {
        val rules = QuickInput.parseCustom("dz=上海市某路 1 号\nBad Key=x\nyx=\nem=me@example.com")
        assertEquals(mapOf("dz" to "上海市某路 1 号", "em" to "me@example.com"), rules)
        assertEquals(rules, QuickInput.parseCustom(QuickInput.formatCustom(rules)))
        assertEquals(listOf("me@example.com"), QuickInput.candidates("em", now, rules))
    }
}
