package com.yuanzhe.kotomo.ime

/** Names what a key does; the keyboard view hands it to the service unchanged. */
sealed interface KeyAction {
    /** Types [text] (a letter, digit or punctuation mark). */
    data class Text(val text: String) : KeyAction
    data object Shift : KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    /** Shows symbol page [page] (0: digits and common marks, 1: more symbols). */
    data class Symbols(val page: Int) : KeyAction
    data object Letters : KeyAction
    /** Opens the emoji panel; a long press opens the language choice instead. */
    data object Emoji : KeyAction
    /** Switches to [mode] (chosen from the Emoji key's long-press popup). */
    data class SetMode(val mode: Mode) : KeyAction
    /** Moves the cursor [steps] characters (negative: left), from dragging along the space bar. */
    data class MoveCursor(val steps: Int) : KeyAction
    /** Deletes [count] words before the cursor, from dragging left from backspace. */
    data class DeleteWords(val count: Int) : KeyAction
    /** Moves the one-handed keyboard to [side], or back to full width for OFF. */
    data class SetOneHanded(val side: com.yuanzhe.kotomo.settings.OneHanded) : KeyAction
    /** Sets the one-handed keyboard's width to [percent] of the full width (dragging its inner edge). */
    data class SetOneHandedWidth(val percent: Int) : KeyAction
}

/** Input modes the keyboard offers; Mixed accepts pinyin, romaji and English in one string. */
enum class Mode { MIXED, CHINESE, JAPANESE, ENGLISH }

/**
 * Describes one key: what it shows, what it does and how much of its row it takes.
 *
 * Notes: [hint] is drawn in the key's corner; [more] lists what a long press offers in its popup
 * (the first is preselected); [width] is relative to the other keys of the row; [function] keys
 * use the darker key color.
 */
data class Key(
    val label: String,
    val action: KeyAction,
    val width: Float = 1f,
    val hint: String? = null,
    val more: List<String> = emptyList(),
    val function: Boolean = false,
)

/**
 * One keyboard row. [lead] and [trail] are empty widths (in key units) that centre a shorter row.
 * In a split layout, [splitAt] is the index of the first key of the right half; a row without
 * one spans the whole width, its [Key.width]s counted in units of a half's letter key, and its
 * space bar takes what is left.
 */
data class Row(val keys: List<Key>, val lead: Float = 0f, val trail: Float = 0f, val splitAt: Int = -1)

/** Places a key in view coordinates (pixels); the box excludes the gap around the key. */
data class PlacedKey(val key: Key, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/** Holds the rows of one keyboard page and maps it to view coordinates. */
data class Layout(val rows: List<Row>) {

    /**
     * Lays the rows out from the top of a [width]-wide area.
     *
     * Inputs: [keyHeight] and [rowGap] per row; [gap] between keys; [half] > 0 lays split rows
     * out as two halves of exactly that width at both edges, the middle left empty (the thumb
     * zones stay the same size on any screen).
     * Returns: every key with its box, rows top to bottom and keys left to right.
     */
    fun place(width: Float, keyHeight: Float, rowGap: Float, gap: Float, half: Float = 0f): List<PlacedKey> {
        val out = mutableListOf<PlacedKey>()
        rows.forEachIndexed { index, row ->
            val top = index * (keyHeight + rowGap)
            fun segment(keys: List<Key>, from: Float, to: Float, lead: Float, trail: Float) {
                val units = lead + trail + keys.sumOf { it.width.toDouble() }.toFloat()
                val unit = (to - from + gap) / units
                var x = from + lead * unit
                for (key in keys) {
                    val w = key.width * unit
                    out += PlacedKey(key, x, top, x + w - gap, top + keyHeight)
                    x += w
                }
            }
            when {
                half <= 0f -> segment(row.keys, 0f, width, row.lead, row.trail)
                row.splitAt >= 0 -> {
                    segment(row.keys.subList(0, row.splitAt), 0f, half, row.lead, 0f)
                    segment(row.keys.subList(row.splitAt, row.keys.size), width - half, width, 0f, row.trail)
                }
                else -> {
                    // A whole-width row: keys keep a half's key unit and the space bar stretches.
                    val unit = (half + gap) / 5.5f
                    val fixed = row.keys.filter { it.action != KeyAction.Space }.sumOf { it.width.toDouble() }.toFloat() * unit
                    val space = width + gap - fixed
                    var x = 0f
                    for (key in row.keys) {
                        val w = if (key.action == KeyAction.Space) space else key.width * unit
                        out += PlacedKey(key, x, top, x + w - gap, top + keyHeight)
                        x += w
                    }
                }
            }
        }
        return out
    }

    companion object {
        /**
         * Finds the key a touch at ([x], [y]) means.
         *
         * Returns: the key whose box contains the point, otherwise the nearest key in the touched
         * row (touches in gaps, margins and the split's middle still type), or null for none.
         */
        fun hit(keys: List<PlacedKey>, x: Float, y: Float): PlacedKey? {
            keys.firstOrNull { x >= it.left && x < it.right && y >= it.top && y < it.bottom }?.let { return it }
            val row = keys.minByOrNull { kotlin.math.abs(it.centerY - y) }?.centerY ?: return null
            return keys.filter { it.centerY == row }.minByOrNull { kotlin.math.abs(it.centerX - x) }
        }

        /** The corner hints of the letter keys (design: digits on the top row, symbols below). */
        private val hints = mapOf(
            'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5", 'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
            'a' to "@", 's' to "#", 'd' to "$", 'f' to "%", 'g' to "&", 'h' to "-", 'j' to "+", 'k' to "(", 'l' to ")",
            'z' to "*", 'x' to "\"", 'c' to "'", 'v' to ":", 'b' to ";", 'n' to "!", 'm' to "?",
        )

        /** Accented letters a long press offers after the hint. */
        private val accents = mapOf(
            'a' to "àáâäæãåā", 'c' to "çćč", 'e' to "èéêëē", 'i' to "ìíîïī", 'n' to "ñń",
            'o' to "òóôöõøōœ", 's' to "ßśš", 'u' to "ùúûüū", 'y' to "ýÿ", 'z' to "žźż",
        )

        private fun letter(c: Char, shifted: Boolean): Key {
            val s = if (shifted) c.uppercase() else c.toString()
            val hint = hints.getValue(c)
            val accented = accents[c].orEmpty().map { if (shifted) it.uppercase() else it.toString() }
            return Key(s, KeyAction.Text(s), hint = hint, more = listOf(hint) + accented)
        }

        private fun letters(chars: String, shifted: Boolean) = chars.map { letter(it, shifted) }

        private fun shift() = Key("⇧", KeyAction.Shift, 1.5f, function = true)
        private fun backspace() = Key("⌫", KeyAction.Backspace, 1.5f, function = true)

        /**
         * Builds the letter page for [mode]; [shifted] shows capitals; [split] marks the rows for
         * the split layout (g and v appear on both halves, as on the design); [field] adapts the
         * bottom row (PLAN §8.7): e-mail fields put @ in the comma's place, web addresses / and a
         * .com key in place of Emoji; both, and code-like fields, use ASCII punctuation. [hinge]
         * (split only) gives the bottom row a space bar on each side of a vertical hinge.
         */
        fun letters(mode: Mode, shifted: Boolean, split: Boolean = false, field: Field = Field.TEXT, hinge: Boolean = false): Layout {
            val rows = if (split) {
                listOf(
                    Row(letters("qwertyuiop", shifted), splitAt = 5),
                    Row(letters("asdfgghjkl", shifted), lead = 0.35f, trail = 0.35f, splitAt = 5),
                    Row(listOf(shift()) + letters("zxcvvbnm", shifted) + backspace(), splitAt = 5),
                )
            } else {
                listOf(
                    Row(letters("qwertyuiop", shifted)),
                    Row(letters("asdfghjkl", shifted), lead = 0.5f, trail = 0.5f),
                    Row(listOf(shift()) + letters("zxcvbnm", shifted) + backspace()),
                )
            }
            return Layout(rows + bottomRow(mode, split, KeyAction.Symbols(0), "?123", field).aroundHinge(split && hinge))
        }

        /**
         * Builds the keypad of a phone (digits, * # +, separators) or number field (digits, sign,
         * comma and decimal point; a date or time field gets / and : instead).
         * Notes: one layout for every width; the last column holds the function keys.
         */
        fun pad(field: Field): Layout {
            fun k(s: String, vararg more: String) = Key(s, KeyAction.Text(s), more = if (more.isEmpty()) emptyList() else listOf(s, *more))
            val (bottomLeft, bottomRight) = when (field) {
                Field.PHONE -> k("*", "#", ";", ",") to k("#")
                Field.DATETIME -> k("/", "-", ".") to k(":")
                else -> k(",") to k(".")
            }
            val dash = if (field == Field.PHONE) k("-", "(", ")", "/", ".", ",", "N") else k("-", "+")
            return Layout(
                listOf(
                    Row(listOf(k("1"), k("2"), k("3"), dash.copy(function = true))),
                    Row(listOf(k("4"), k("5"), k("6"), Key("", KeyAction.Space, function = true))),
                    Row(listOf(k("7"), k("8"), k("9"), backspace().copy(width = 1f))),
                    Row(listOf(bottomLeft, if (field == Field.PHONE) k("0", "+") else k("0"), bottomRight, Key("⏎", KeyAction.Enter, function = true))),
                ),
            )
        }

        /** Long-press variants of symbols, the symbol itself first. */
        private val symbolMore = mapOf(
            "$" to "$€£¥¢₩", "¥" to "¥￥$€£", "￥" to "￥¥$€£", "-" to "-–—_", "_" to "_-—", "\"" to "\"“”„«»",
            "'" to "'‘’‚‹›", "?" to "?¿", "!" to "!¡", "%" to "%‰", "(" to "([{<", ")" to ")]}>", "*" to "*★†‡",
            "=" to "=≠≈∞", "/" to "/÷", "+" to "+±", "<" to "<≤«", ">" to ">≥»", "." to ".…", "…" to "…⋯",
            "？" to "？?¿", "！" to "！!¡", "“" to "“「『\"", "”" to "”」』\"", "（" to "（【〔(", "）" to "）】〕)",
            "、" to "、，", "・" to "・·•", "—" to "—–-",
        )

        private fun symbolKeys(s: String) = s.codePoints().toArray().map { cp ->
            val c = String(Character.toChars(cp))
            Key(c, KeyAction.Text(c), more = symbolMore[c]?.codePoints()?.toArray()?.map { String(Character.toChars(it)) }.orEmpty())
        }

        /**
         * Builds symbol page [page] for [mode]: page 0 has the digits and the marks used most,
         * page 1 more symbols; Chinese and Mixed use full-width Chinese punctuation, Japanese its
         * own marks, English ASCII. The third row's first key switches page; the bottom row returns
         * to letters. Many symbols offer variants on long press.
         */
        fun symbols(mode: Mode, split: Boolean = false, page: Int = 0, hinge: Boolean = false): Layout {
            val (row2, row3) = when (mode) {
                Mode.ENGLISH -> if (page == 0) "@#\$_&-+()/" to "*\"':;!?" else "~`|•√π÷×¶∆" to "%©®™✓[]<>"
                Mode.JAPANESE -> if (page == 0) "@#￥&-+（）「」" to "、；：・？！…" else "『』【】〈〉《》～｜" to "%^=\\{}<>"
                Mode.MIXED, Mode.CHINESE -> if (page == 0) "@#￥&-+（）“”" to "、；：‘’？！" else "【】《》「」…—·～" to "%^=\\{}<>"
            }
            val row1 = if (page == 0) "1234567890" else when (mode) {
                Mode.ENGLISH -> "£€¥¢^°={}\\"
                else -> "£€$¢^°=_|\\"
            }
            val switch = Key(if (page == 0) "=\\<" else "?123", KeyAction.Symbols(1 - page), 1.5f, function = true)
            val third = listOf(switch) + symbolKeys(row3) + backspace()
            val rows = if (split) {
                listOf(
                    Row(symbolKeys(row1), splitAt = 5),
                    Row(symbolKeys(row2), splitAt = 5),
                    Row(third, splitAt = 1 + (third.size - 2) / 2),
                )
            } else {
                listOf(Row(symbolKeys(row1)), Row(symbolKeys(row2)), Row(third))
            }
            return Layout(rows + bottomRow(mode, split, KeyAction.Letters, "ABC").aroundHinge(split && hinge))
        }

        /**
         * The design's bottom row: page switch, comma, Emoji, space, full stop, enter.
         * Notes: on the phone the comma and full stop are light keys and Emoji a dark one; split,
         * the other way round.
         */
        private fun bottomRow(mode: Mode, split: Boolean, page: KeyAction, pageLabel: String, field: Field = Field.TEXT): Row {
            val emoji = Key("☺", KeyAction.Emoji, function = !split)
            when (field) {
                Field.EMAIL, Field.URL -> {
                    val email = field == Field.EMAIL
                    val first = if (email) Key("@", KeyAction.Text("@"), more = listOf("@", "-", "_", "+"), function = split)
                    else Key("/", KeyAction.Text("/"), more = listOf("/", ":", "-", "_", "?", "=", "&", "#"), function = split)
                    val domains = listOf(".com", ".net", ".org", ".cn", ".jp", ".io")
                    return Row(
                        listOf(
                            Key(pageLabel, page, if (split) 1.5f else 1.4f, function = true),
                            first,
                            if (email) emoji else Key(".com", KeyAction.Text(".com"), more = domains, function = !split),
                            Key("", KeyAction.Space, 4.6f),
                            Key(".", KeyAction.Text("."), more = if (email) domains else listOf(".", "-", "_"), function = split),
                            Key("⏎", KeyAction.Enter, 1.5f, function = true),
                        ),
                    )
                }
                Field.CODE -> return bottomRow(Mode.ENGLISH, split, page, pageLabel)
                else -> Unit
            }
            val (comma, stop) = punctuation(mode)
            val (commaMore, stopMore) = when (mode) {
                Mode.ENGLISH -> listOf(",", ";", ":", "'", "\"") to listOf(".", "…", "?", "!")
                Mode.JAPANESE -> listOf("、", "，", "；", "：", "・") to listOf("。", "…", "？", "！")
                else -> listOf("，", "、", "；", "：", "“”") to listOf("。", "…", "？", "！")
            }
            return Row(
                listOf(
                    Key(pageLabel, page, if (split) 1.5f else 1.4f, function = true),
                    Key(comma, KeyAction.Text(comma), more = commaMore, function = split),
                    emoji,
                    Key("", KeyAction.Space, 4.6f),
                    Key(stop, KeyAction.Text(stop), more = stopMore, function = split),
                    Key("⏎", KeyAction.Enter, 1.5f, function = true),
                ),
            )
        }

        /**
         * Splits a whole-width bottom row around a hinge when [apply] is set: the space bar becomes
         * one bar on each side, so no key spans the hinge (PLAN §8.10).
         */
        private fun Row.aroundHinge(apply: Boolean): Row {
            val i = keys.indexOfFirst { it.action == KeyAction.Space }
            if (!apply || i < 0) return this
            val bar = keys[i].copy(width = HINGE_SPACE)
            return Row(keys.subList(0, i) + bar + bar + keys.subList(i + 1, keys.size), splitAt = i + 1)
        }

        /** The width of each space bar beside a hinge, in key units. */
        private const val HINGE_SPACE = 2.3f

        /**
         * Marks which popup choices are full-width (true) or half-width (false), for a popup that
         * offers both kinds; null where it says nothing (letters, or a popup with one kind only).
         * Notes: full-width covers the fullwidth forms block, CJK symbols and punctuation, and the
         * curly quotes, ellipsis and dash used in Chinese text; half-width is printable ASCII.
         */
        fun widthMarks(choices: List<String>): List<Boolean?> {
            fun kind(s: String): Boolean? {
                val cp = s.codePointAt(0)
                if (s.codePointCount(0, s.length) != 1) return null
                return when {
                    cp in 0xFF01..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x3000..0x303F ||
                        cp in listOf(0x2018, 0x2019, 0x201C, 0x201D, 0x2026, 0x2014, 0x30FB) -> true
                    cp in 0x21..0x7E && !Character.isLetterOrDigit(cp) -> false
                    else -> null
                }
            }
            val kinds = choices.map(::kind)
            return if (true in kinds && false in kinds) kinds else choices.map { null }
        }

        /** Returns the comma and full stop for [mode]: Chinese and Japanese forms or ASCII. */
        fun punctuation(mode: Mode) = when (mode) {
            Mode.MIXED, Mode.CHINESE -> "，" to "。"
            Mode.JAPANESE -> "、" to "。"
            Mode.ENGLISH -> "," to "."
        }
    }
}
