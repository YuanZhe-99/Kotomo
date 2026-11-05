package com.yuanzhe.kotomo.dictionary

/**
 * Reads and writes JSON for the dictionary files, the import format and the online catalog.
 *
 * Values map to Kotlin as: object → `Map<String, Any?>` (a [LinkedHashMap], key order kept),
 * array → `List<Any?>`, string → [String], number → [Long] when integral and in range, else
 * [Double], `true`/`false` → [Boolean], `null` → null.
 *
 * Notes: pure Kotlin so the unit tests run on the JVM (Android's `org.json` is a stub there).
 * Input comes from files and downloads, so every malformed document throws [JsonException]
 * rather than crashing; nesting deeper than [MAX_DEPTH] is refused.
 */
internal object Json {
    /** A malformed JSON document; [offset] is the character position where reading stopped. */
    class JsonException(message: String, val offset: Int) : Exception("$message at $offset")

    private const val MAX_DEPTH = 64

    /**
     * Parses one JSON document.
     * Returns: the value as described on [Json]. Throws [JsonException] on malformed input or
     * trailing content.
     */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.space()
        val value = reader.value(0)
        reader.space()
        if (reader.pos != text.length) throw JsonException("trailing content", reader.pos)
        return value
    }

    /**
     * Writes [value] as JSON.
     * Inputs: maps, lists, strings, numbers, booleans and null (anything else is written as its
     * string); [indent] true puts each array element and object member on its own line.
     */
    fun write(value: Any?, indent: Boolean = false): String = StringBuilder().also { write(it, value, indent, 0) }.toString()

    private fun write(out: StringBuilder, value: Any?, indent: Boolean, depth: Int) {
        fun newline(d: Int) {
            if (indent) out.append('\n').append("  ".repeat(d))
        }
        when (value) {
            null -> out.append("null")
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Double -> out.append(if (value.isFinite()) value.toString() else "null")
            is Float -> write(out, value.toDouble(), indent, depth)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    newline(depth + 1)
                    string(out, k.toString())
                    out.append(if (indent) ": " else ":")
                    write(out, v, indent, depth + 1)
                }
                if (!first) newline(depth)
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    newline(depth + 1)
                    write(out, v, indent, depth + 1)
                }
                if (value.isNotEmpty()) newline(depth)
                out.append(']')
            }
            else -> string(out, value.toString())
        }
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    /** Recursive-descent reader over [text]. */
    private class Reader(val text: String) {
        var pos = 0

        fun space() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }

        fun fail(message: String): Nothing = throw JsonException(message, pos)

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nested too deeply")
            if (pos >= text.length) fail("unexpected end")
            return when (val c = text[pos]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
            }
        }

        fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("unexpected token")
            pos += word.length
            return value
        }

        fun obj(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++
            space()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                space()
                if (pos >= text.length || text[pos] != '"') fail("expected a member name")
                val key = string()
                space()
                if (pos >= text.length || text[pos] != ':') fail("expected ':'")
                pos++
                space()
                map[key] = value(depth + 1)
                space()
                if (pos >= text.length) fail("unexpected end")
                when (text[pos++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> { pos--; fail("expected ',' or '}'") }
                }
            }
        }

        fun array(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            pos++
            space()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                space()
                list.add(value(depth + 1))
                space()
                if (pos >= text.length) fail("unexpected end")
                when (text[pos++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> { pos--; fail("expected ',' or ']'") }
                }
            }
        }

        fun string(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("unterminated string")
                val c = text[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (pos >= text.length) fail("unterminated string")
                        when (val e = text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("bad escape")
                                val code = text.substring(pos, pos + 4).toIntOrNull(16) ?: fail("bad escape")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> { pos--; fail("bad escape '\\$e'") }
                        }
                    }
                    c < ' ' -> { pos--; fail("control character in string") }
                    else -> out.append(c)
                }
            }
        }

        fun number(): Any {
            val start = pos
            if (text[pos] == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val raw = text.substring(start, pos)
            if (raw.none { it == '.' || it == 'e' || it == 'E' }) raw.toLongOrNull()?.let { return it }
            return raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: run { pos = start; fail("bad number") }
        }
    }
}

/** Returns the member [key] of a JSON object as a string, or null when missing or not a string. */
internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String

/** Returns the member [key] of a JSON object as a whole number, or null. */
internal fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Double -> v.takeIf { it % 1.0 == 0.0 && it >= Long.MIN_VALUE && it <= Long.MAX_VALUE }?.toLong()
    else -> null
}

/** Returns the member [key] of a JSON object as an object, or null. */
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

/** Returns the member [key] of a JSON object as an array, or null. */
internal fun Map<String, Any?>.list(key: String): List<Any?>? = this[key] as? List<Any?>
