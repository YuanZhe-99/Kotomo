package com.yuanzhe.kotomo.ime

/** Names what a key does; the keyboard view hands it to the service unchanged. */
sealed interface KeyAction {
    /** Types [text] (a letter, digit or punctuation mark). */
    data class Text(val text: String) : KeyAction
    data object Shift : KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object Symbols : KeyAction
    data object Letters : KeyAction
    /** Opens the emoji panel; a long press switches the input mode instead. */
    data object Emoji : KeyAction
}

/** Input modes the keyboard offers; Mixed accepts pinyin, romaji and English in one string. */
enum class Mode { MIXED, CHINESE, JAPANESE, ENGLISH }

/**
 * Describes one key: what it shows, what it does and how much of its row it takes.
 *
 * Notes: [hint] is the character a long press types, shown in the key's corner when hints are on;
 * [width] is relative to the other keys and spacers of the row.
 */
data class Key(
    val label: String,
    val action: KeyAction,
    val width: Float = 1f,
    val hint: String? = null,
    val function: Boolean = false,
)

/** One keyboard row; [lead] and [trail] are empty widths that centre a shorter row. */
data class Row(val keys: List<Key>, val lead: Float = 0f, val trail: Float = 0f)

/** Places a key in view coordinates (pixels); the box excludes the gap around the key. */
data class PlacedKey(val key: Key, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/** Holds the rows of one keyboard page and maps it to view coordinates. */
data class Layout(val rows: List<Row>) {

    /**
     * Lays the rows out in a [width] × [height] area with [gap] pixels between keys.
     *
     * Returns: every key with its box, rows top to bottom and keys left to right.
     * Notes: each row gets an equal share of the height; widths follow the keys' weights.
     */
    fun place(width: Float, height: Float, gap: Float): List<PlacedKey> {
        val rowHeight = height / rows.size
        return rows.flatMapIndexed { index, row ->
            val units = row.lead + row.trail + row.keys.sumOf { it.width.toDouble() }.toFloat()
            val unit = width / units
            var x = row.lead * unit
            val top = index * rowHeight
            row.keys.map { key ->
                val w = key.width * unit
                PlacedKey(key, x + gap / 2, top + gap / 2, x + w - gap / 2, top + rowHeight - gap / 2)
                    .also { x += w }
            }
        }
    }

    companion object {
        /**
         * Finds the key a touch at ([x], [y]) means.
         *
         * Returns: the key whose box contains the point, otherwise the nearest key in the touched
         * row (touches in gaps and row margins still type), or null for an empty list.
         */
        fun hit(keys: List<PlacedKey>, x: Float, y: Float): PlacedKey? {
            keys.firstOrNull { x >= it.left && x < it.right && y >= it.top && y < it.bottom }?.let { return it }
            val row = keys.minByOrNull { kotlin.math.abs(it.centerY - y) }?.centerY ?: return null
            return keys.filter { it.centerY == row }.minByOrNull { kotlin.math.abs(it.centerX - x) }
        }

        private val digits = "1234567890".map(Char::toString)

        /**
         * Builds the letter page for [mode]; [shifted] shows capitals.
         *
         * Notes: the top row carries the digits as long-press hints; the bottom row follows the
         * design: ?123, comma, Emoji (long press: language), space, full stop, enter.
         */
        fun letters(mode: Mode, shifted: Boolean): Layout {
            fun letterRow(chars: String, hints: List<String?> = List(chars.length) { null }) =
                chars.mapIndexed { i, c ->
                    val s = if (shifted) c.uppercase() else c.toString()
                    Key(s, KeyAction.Text(s), hint = hints[i])
                }
            val (comma, stop) = punctuation(mode)
            return Layout(
                listOf(
                    Row(letterRow("qwertyuiop", digits)),
                    Row(letterRow("asdfghjkl", listOf("@", "#", "&", "*", "-", "+", "(", ")", "/")), lead = 0.5f, trail = 0.5f),
                    Row(
                        listOf(Key("⇧", KeyAction.Shift, 1.5f, function = true)) +
                            letterRow("zxcvbnm", listOf("'", "\"", ":", ";", "!", "?", "~")) +
                            Key("⌫", KeyAction.Backspace, 1.5f, function = true),
                    ),
                    bottomRow(comma, stop, KeyAction.Symbols, "?123"),
                ),
            )
        }

        /** Builds the digit and symbol page; its bottom row returns to letters. */
        fun symbols(mode: Mode): Layout {
            fun row(s: String) = s.map { Key(it.toString(), KeyAction.Text(it.toString())) }
            val (comma, stop) = punctuation(mode)
            val currency = if (mode == Mode.ENGLISH) "$" else "¥"
            return Layout(
                listOf(
                    Row(row("1234567890")),
                    Row(row("@#${currency}_&-+()/")),
                    Row(row("=*\"':;!?") + Key("⌫", KeyAction.Backspace, 2f, function = true)),
                    bottomRow(comma, stop, KeyAction.Letters, "ABC"),
                ),
            )
        }

        private fun bottomRow(comma: String, stop: String, page: KeyAction, pageLabel: String) = Row(
            listOf(
                Key(pageLabel, page, 1.5f, function = true),
                Key(comma, KeyAction.Text(comma), hint = "!"),
                Key("☺", KeyAction.Emoji, function = true),
                Key("", KeyAction.Space, 4f),
                Key(stop, KeyAction.Text(stop), hint = "?"),
                Key("⏎", KeyAction.Enter, 1.5f, function = true),
            ),
        )

        /** Returns the comma and full stop for [mode]: Chinese and Japanese forms or ASCII. */
        fun punctuation(mode: Mode) = when (mode) {
            Mode.MIXED, Mode.CHINESE -> "，" to "。"
            Mode.JAPANESE -> "、" to "。"
            Mode.ENGLISH -> "," to "."
        }
    }
}
