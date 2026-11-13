package com.yuanzhe.kotomo.ime

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns a quick-input query into candidates (PLAN §10.4 U/V-mode style, §10.8 dates and times): in
 * Chinese mode a composition that starts with `v` is not pinyin but one of these.
 *
 * - digits (one optional decimal point): Chinese numerals, capital money (大写金额), each digit, and
 *   the number grouped by thousands (`v1234.5` → 一千二百三十四点五, 壹仟贰佰叁拾肆元伍角, …);
 * - `rq` today's date, `sj` the time, `xq` the weekday, in several formats;
 * - `u` and 1–6 hex digits: that Unicode character and its `U+` name;
 * - an arithmetic expression (digits, `+ - * / ^ ( ) .`): its value, and the equation;
 * - an abbreviation of the person's own replacements (`quick.custom`): its text.
 *
 * Notes: pure Kotlin; no I/O (the caller passes the time and the rules). Unit-tested (QuickInputTest).
 */
object QuickInput {
    /** The letter that starts a quick-input composition in Chinese mode. */
    const val PREFIX = 'v'
    /** The longest number converted (12 integer digits: up to 千亿). */
    private const val MAX_DIGITS = 12

    /**
     * Returns the candidates for [query] (what follows `v`), best first; empty when it means
     * nothing. [now] is the local date and time; [custom] the person's abbreviations.
     */
    fun candidates(query: String, now: LocalDateTime, custom: Map<String, String> = emptyMap()): List<String> {
        val out = mutableListOf<String>()
        custom[query]?.let { out += it }
        when {
            query == "rq" -> out += date(now)
            query == "sj" -> out += time(now)
            query == "xq" -> out += listOf("星期" + WEEK[now.dayOfWeek.value % 7], "周" + WEEK[now.dayOfWeek.value % 7])
            query.startsWith("u") && query.length in 2..7 && query.drop(1).all { it in '0'..'9' || it in 'a'..'f' } -> unicode(query.drop(1))?.let { out += it }
            NUMBER.matches(query) -> out += numbers(query)
            EXPRESSION.matches(query) -> calculate(query)?.let { v -> out += listOf(v, "$query=$v") }
        }
        return out.distinct()
    }

    /**
     * Reads the person's replacements: one `abbreviation=text` per line, the abbreviation of
     * lowercase letters and digits (at most 16), the text not empty; other lines are skipped.
     */
    fun parseCustom(text: String): Map<String, String> = text.split('\n').mapNotNull { line ->
        val at = line.indexOf('=')
        if (at <= 0) return@mapNotNull null
        val key = line.substring(0, at).trim()
        val value = line.substring(at + 1).trim()
        if (!CUSTOM_KEY.matches(key) || value.isEmpty()) null else key to value
    }.toMap()

    /** Writes replacements as [parseCustom] reads them, one per line in order. */
    fun formatCustom(map: Map<String, String>): String = map.toSortedMap().entries.joinToString("\n") { (k, v) -> "$k=$v" }

    private fun date(now: LocalDateTime): List<String> {
        val y = now.year
        val m = now.monthValue
        val d = now.dayOfMonth
        return listOf(
            "${y}年${m}月${d}日",
            now.format(DateTimeFormatter.ISO_LOCAL_DATE),
            now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT)),
            y.toString().map { DIGITS[it - '0'] }.joinToString("").replace('零', '〇') + "年" + small(m) + "月" + small(d) + "日",
            "${m}月${d}日 星期" + WEEK[now.dayOfWeek.value % 7],
        )
    }

    private fun time(now: LocalDateTime): List<String> = listOf(
        now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)),
        "${now.hour}时${now.minute}分",
        now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)),
    )

    private fun unicode(hex: String): List<String>? {
        val cp = hex.toIntOrNull(16) ?: return null
        if (!Character.isValidCodePoint(cp) || Character.getType(cp).let { it == Character.SURROGATE.toInt() || it == Character.CONTROL.toInt() || it == Character.UNASSIGNED.toInt() }) return null
        return listOf(String(Character.toChars(cp)), "U+%04X".format(cp))
    }

    /** The conversions of a number: Chinese numerals, capital money, each digit, grouped digits. */
    private fun numbers(q: String): List<String> {
        val (whole, frac) = q.split('.').let { it[0].ifEmpty { "0" } to it.getOrElse(1) { "" } }
        if (whole.trimStart('0').length > MAX_DIGITS) return emptyList()
        val n = whole.trimStart('0').ifEmpty { "0" }
        val chinese = lower(n) + if (frac.isEmpty()) "" else "点" + frac.map { DIGITS[it - '0'] }.joinToString("")
        val grouped = n.reversed().chunked(3).joinToString(",").reversed() + if (frac.isEmpty()) "" else ".$frac"
        val each = q.map { if (it == '.') '点' else DIGITS[it - '0'] }.joinToString("")
        return listOfNotNull(chinese, money(n, frac), each, grouped.takeIf { it != q })
    }

    /** Writes a whole number in Chinese numerals: 10 → 十, 1001 → 一千零一, 100000000 → 一亿. */
    fun lower(n: String): String = spell(n, DIGITS, LOWER_UNITS, tenAlone = true)

    /** Writes an amount in capital money: 1234.56 → 壹仟贰佰叁拾肆元伍角陆分, 100 → 壹佰元整; more than two decimals → null. */
    fun money(whole: String, frac: String): String? {
        if (frac.length > 2) return null
        val w = whole.trimStart('0')
        val jiao = frac.getOrNull(0)?.minus('0') ?: 0
        val fen = frac.getOrNull(1)?.minus('0') ?: 0
        // Less than one yuan is written from 角 or 分 (伍角), not 零元伍角.
        if (w.isEmpty() && (jiao != 0 || fen != 0)) return (if (jiao != 0) "${CAPITAL[jiao]}角" else "") + (if (fen != 0) "${CAPITAL[fen]}分" else "")
        val yuan = spell(w.ifEmpty { "0" }, CAPITAL, CAPITAL_UNITS, tenAlone = false) + "元"
        return when {
            jiao == 0 && fen == 0 -> yuan + "整"
            fen == 0 -> yuan + CAPITAL[jiao] + "角"
            jiao == 0 -> yuan + "零" + CAPITAL[fen] + "分"
            else -> yuan + CAPITAL[jiao] + "角" + CAPITAL[fen] + "分"
        }
    }

    /**
     * Spells the digits [n] with [digits] and the units 十百千 ([units]) in groups of four under
     * 万 and 亿; zeros inside collapse to one 零, trailing zeros of a group are silent, and with
     * [tenAlone] a leading 一十 reads 十.
     */
    private fun spell(n: String, digits: CharArray, units: CharArray, tenAlone: Boolean): String {
        if (n.all { it == '0' }) return digits[0].toString()
        val groups = n.reversed().chunked(4).map { it.reversed() }
        val big = arrayOf("", "万", "亿")
        val sb = StringBuilder()
        var zero = false
        for (g in groups.indices.reversed()) {
            val group = groups[g].padStart(4, '0')
            if (group == "0000") {
                zero = sb.isNotEmpty()
                continue
            }
            for (i in 0 until 4) {
                val d = group[i] - '0'
                if (d == 0) {
                    if (sb.isNotEmpty()) zero = true
                    continue
                }
                if (zero) sb.append(digits[0])
                zero = false
                sb.append(digits[d])
                if (i < 3) sb.append(units[2 - i])
            }
            sb.append(big[g])
        }
        val s = sb.toString()
        return if (tenAlone && s.startsWith("${digits[1]}${units[0]}")) s.substring(1) else s
    }

    /** Writes a month or day (1–31) in Chinese numerals, for the date in numerals. */
    private fun small(n: Int) = lower(n.toString())

    /** Evaluates an arithmetic expression; null when it does not parse or divides by zero. Whole results drop the decimals. */
    fun calculate(expr: String): String? {
        val value = runCatching { Parser(expr).parse() }.getOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        return if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString()
        else java.math.BigDecimal(value).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }

    /** A small recursive-descent parser: + − × ÷ with precedence, ^ (right-associative), parentheses, unary minus. */
    private class Parser(private val s: String) {
        private var i = 0
        fun parse(): Double = sum().also { require(i == s.length) }
        private fun sum(): Double {
            var v = product()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) v = if (s[i++] == '+') v + product() else v - product()
            return v
        }
        private fun product(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = power()
                v = if (op == '*') v * r else { require(r != 0.0); v / r }
            }
            return v
        }
        private fun power(): Double {
            val base = unary()
            return if (i < s.length && s[i] == '^') { i++; Math.pow(base, power()) } else base
        }
        private fun unary(): Double = if (i < s.length && s[i] == '-') { i++; -unary() } else atom()
        private fun atom(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = sum()
                require(i < s.length && s[i] == ')')
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()
        }
    }

    private val DIGITS = "零一二三四五六七八九".toCharArray()
    private val LOWER_UNITS = "十百千".toCharArray()
    private val CAPITAL = "零壹贰叁肆伍陆柒捌玖".toCharArray()
    private val CAPITAL_UNITS = "拾佰仟".toCharArray()
    private val WEEK = "日一二三四五六".toCharArray()
    private val NUMBER = Regex("[0-9]+(\\.[0-9]+)?")
    private val EXPRESSION = Regex("[0-9.+\\-*/^()]+")
    private val CUSTOM_KEY = Regex("[a-z0-9]{1,16}")
}
