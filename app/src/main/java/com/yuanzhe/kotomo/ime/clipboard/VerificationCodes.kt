package com.yuanzhe.kotomo.ime.clipboard

/**
 * Holds one plausible verification code found in a text.
 *
 * Notes: [value] is what gets pasted (digits or letters only, separators removed); [score] is in
 * points (higher is likelier, [VerificationCodes.MIN_SCORE] and up); [context] is a short snippet
 * of the text around the code, cut with "…", for telling several codes apart (PLAN §10.7).
 */
data class VerificationCode(val value: String, val score: Int, val context: String)

/**
 * Finds verification codes in copied or received text with simple local rules, in Chinese,
 * English and Japanese messages.
 *
 * Notes: pure Kotlin, no I/O; nothing leaves the device. A text with no code keyword yields
 * nothing unless the whole text is a code, so ordinary numbers are never offered. Dates, times,
 * prices, amounts, phone numbers and order numbers are rejected or ranked down. When several codes
 * are plausible, all are returned, likeliest first, each with its context.
 */
object VerificationCodes {
    /** The lowest score a returned code has. */
    const val MIN_SCORE = 30

    /** Texts longer than this are not scanned: a code message is short. */
    private const val MAX_SCAN = 4000

    /** Characters of context kept on each side of a code. */
    private const val CONTEXT = 12

    private val keywordEn = Regex(
        // ASCII-letter boundaries, not \b: Android's ICU counts CJK as word characters, which
        // would hide "OTP" in "您的OTP为…".
        "(?<![A-Za-z])(codes?|verification|verify|verifying|otp|passcode|pin|one[- ]time|2fa|authentication)(?![A-Za-z])",
        RegexOption.IGNORE_CASE,
    )
    private val keywordCjk = Regex(
        "验证码|驗證碼|校验码|校驗碼|动态码|動態碼|动态密码|動態密碼|确认码|確認碼|激活码|啟用碼|安全码|安全碼|" +
            "认证码|認證碼|授权码|授權碼|口令|認証コード|確認コード|認証番号|確認番号|暗証番号|ワンタイム|パスコード|コード",
    )
    private val tailWords = Regex("(尾号|尾號|末尾|下[4四]桁|ending in|ending with|ends in|account)\\W{0,3}$", RegexOption.IGNORE_CASE)
    private val orderWords = Regex("(订单|訂單|单号|單號|注文|伝票|order|tracking|invoice|no\\.|#|＃)[^\\n]{0,6}$", RegexOption.IGNORE_CASE)
    private val phoneWords = Regex("(电话|電話|致电|致電|热线|熱線|tel|call|phone|fax)\\W{0,3}$", RegexOption.IGNORE_CASE)
    private val currencyBefore = Regex("([$¥￥€£₩]|usd|rmb|cny|jpy|eur|hk\\$)\\s?$", RegexOption.IGNORE_CASE)
    private val unitAfter = Regex("^(\\s?[年月日号號時时分秒元円块塊人个個件%万萬億亿点點度％]|\\s(yen|dollars?|usd|rmb|jpy|eur|kg|km|pts|points)\\b)", RegexOption.IGNORE_CASE)

    /**
     * Finds the plausible codes in [text].
     * Returns: the codes with score ≥ [MIN_SCORE], likeliest first (ties in text order), one per
     * value; empty when there is none.
     */
    fun find(text: String): List<VerificationCode> {
        if (text.length > MAX_SCAN || text.isBlank()) return emptyList()
        val norm = normalize(text)
        bare(norm.trim())?.let { return listOf(VerificationCode(it, 100, "")) }
        val keywords = (keywordEn.findAll(norm) + keywordCjk.findAll(norm)).map { it.range }.toList()
        if (keywords.isEmpty()) return emptyList()
        return candidates(norm)
            .mapNotNull { c -> score(norm, c, keywords)?.let { c.range.first to VerificationCode(c.value, it, context(text, c.range)) } }
            .filter { it.second.score >= MIN_SCORE }
            .sortedWith(compareByDescending<Pair<Int, VerificationCode>> { it.second.score }.thenBy { it.first })
            .map { it.second }
            .distinctBy { it.value }
    }

    /**
     * Decides whether the whole text is a code, as when only the code was copied.
     * Returns: the code to paste, or null.
     */
    fun bare(text: String): String? {
        val t = normalize(text.trim())
        return when {
            t.length in 4..8 && t.all { it in '0'..'9' } -> t
            t.length == 7 && (t[3] == ' ' || t[3] == '-') && t.removeRange(3, 4).all { it in '0'..'9' } -> t.removeRange(3, 4)
            isAlnumCode(t) -> t
            else -> null
        }
    }

    /** A code-shaped token: where it is in the text and what it pastes as. */
    private class Candidate(val range: IntRange, val value: String, val alnum: Boolean)

    /**
     * Maps full-width digits and letters and the ideographic space to ASCII; the length never
     * changes.
     * Notes: full-width punctuation stays as it is, so "482913，5分钟" is not read as the number
     * "482913,5".
     */
    private fun normalize(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                in '０'..'９', in 'Ａ'..'Ｚ', in 'ａ'..'ｚ' -> c - 0xFEE0
                '　' -> ' '
                else -> c
            },
        )
    }

    /** Whether a token looks like an alphanumeric code: 4–8 capitals and digits, at least two digits. */
    private fun isAlnumCode(t: String) =
        t.length in 4..8 && t.all { it in '0'..'9' || it in 'A'..'Z' } && t.count { it in '0'..'9' } >= 2 && t.any { it in 'A'..'Z' }

    private fun Char.ascii() = this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'

    /**
     * Lists the code-shaped tokens: single digit groups of 4–8, two equal groups of 3 or 4 joined
     * by a space or hyphen, and alphanumeric codes.
     * Notes: chains of digit groups joined by `.`, `,` or `:` (amounts, times, dates), three or
     * more groups, nine or more digits (phone numbers, dates, IDs), `/` (dates) and a leading `+`
     * are not codes.
     */
    private fun candidates(s: String): List<Candidate> {
        val tokens = mutableListOf<IntRange>()
        var i = 0
        while (i < s.length) {
            if (!s[i].ascii()) { i++; continue }
            val start = i
            while (i < s.length && s[i].ascii()) i++
            tokens += start until i
        }
        val out = mutableListOf<Candidate>()
        var t = 0
        while (t < tokens.size) {
            val tok = tokens[t]
            val text = s.substring(tok)
            if (!text.all { it in '0'..'9' }) {
                if (isAlnumCode(text)) out += Candidate(tok, text, alnum = true)
                t++
                continue
            }
            // Gather the chain of digit groups joined by one separator.
            val chain = mutableListOf(tok)
            val seps = StringBuilder()
            while (t + 1 < tokens.size) {
                val next = tokens[t + 1]
                val gap = next.first - chain.last().last - 1
                if (gap != 1 || !s.substring(next).all { it in '0'..'9' }) break
                val sep = s[chain.last().last + 1]
                val spaceOk = sep == ' ' && chain.last().count() <= 4 && next.count() <= 4
                if (sep !in "-./:," && !spaceOk) break
                seps.append(sep)
                chain += next
                t++
            }
            t++
            val digits = chain.sumOf { it.count() }
            val plus = chain.first().first > 0 && s[chain.first().first - 1] == '+'
            if (plus || seps.any { it in ".,:/" } || chain.size >= 3 || digits >= 9) continue
            when (chain.size) {
                1 -> if (digits in 4..8) out += Candidate(tok, text, alnum = false)
                2 -> if (chain[0].count() == chain[1].count() && chain[0].count() in 3..4) {
                    out += Candidate(chain[0].first..chain[1].last, s.substring(chain[0]) + s.substring(chain[1]), alnum = false)
                }
            }
        }
        return out
    }

    /**
     * Scores one candidate: its shape, its distance to the nearest code keyword, and penalties for
     * account tails, order and phone context and years.
     * Returns: the score in points, or null when the context rules it out (a price, a unit).
     */
    private fun score(s: String, c: Candidate, keywords: List<IntRange>): Int? {
        val before = s.substring(maxOf(0, c.range.first - 10), c.range.first)
        val after = s.substring(c.range.last + 1, minOf(s.length, c.range.last + 12))
        if (currencyBefore.containsMatchIn(before) || unitAfter.containsMatchIn(after)) return null
        var score = when {
            c.alnum -> 30
            c.value.length == 6 -> 50
            c.value.length == 4 -> 40
            else -> 35
        }
        val distance = keywords.minOf { k ->
            when {
                k.last < c.range.first -> c.range.first - k.last - 1
                k.first > c.range.last -> k.first - c.range.last - 1
                else -> Int.MAX_VALUE // a keyword overlapping the token (e.g. "PIN" inside a code) is no evidence
            }
        }
        score += when {
            distance <= 12 -> 40
            distance <= 40 -> 20
            else -> 0
        }
        if (tailWords.containsMatchIn(before)) score -= 30
        if (orderWords.containsMatchIn(before)) score -= 50
        if (phoneWords.containsMatchIn(before)) score -= 40
        if (!c.alnum && c.value.length == 4 && c.value.toInt() in 1900..2099) score -= 15
        return score
    }

    /** Cuts the snippet around [range] from the original text, whitespace collapsed, "…" where cut. */
    private fun context(text: String, range: IntRange): String {
        val from = maxOf(0, range.first - CONTEXT)
        val to = minOf(text.length, range.last + 1 + CONTEXT)
        val core = text.substring(from, to).replace(Regex("\\s+"), " ").trim()
        return (if (from > 0) "…" else "") + core + (if (to < text.length) "…" else "")
    }
}
