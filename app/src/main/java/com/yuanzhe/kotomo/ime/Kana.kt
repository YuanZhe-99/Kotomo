package com.yuanzhe.kotomo.ime

/**
 * Maps hiragana for the 12-key flick layout (PLAN §10.9): which characters compose, and the ゛゜小
 * key's cycle through a kana's plain, voiced, half-voiced and small forms.
 * Notes: pure Kotlin; unit-tested (FlickTest). The cycles are the kana's own Unicode forms.
 */
object Kana {
    /** Each cycle the ゛゜小 key steps through; a kana in no cycle stays as it is. */
    private val cycles = listOf(
        "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ", "やゃ", "ゆゅ", "よょ", "わゎ", "つっづ",
        "かが", "きぎ", "くぐ", "けげ", "こご", "さざ", "しじ", "すず", "せぜ", "そぞ",
        "ただ", "ちぢ", "てで", "とど", "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ",
    )
    private val next: Map<Char, Char> = buildMap {
        for (c in cycles) c.forEachIndexed { i, ch -> put(ch, c[(i + 1) % c.length]) }
    }

    /** Whether [c] composes in Japanese mode: hiragana and the long-vowel mark ー. */
    fun composes(c: Char) = c in 'ぁ'..'ゖ' || c == 'ー'

    /** Returns the next form of [c] in its cycle (か→が→か, は→ば→ぱ→は, つ→っ→づ→つ), or [c]. */
    fun modify(c: Char): Char = next[c] ?: c
}
