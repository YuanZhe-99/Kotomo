package com.yuanzhe.kotomo.dictionary

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Names the file formats Kotomo imports and exports (PLAN §10.5). */
enum class Format(val extension: String, val mime: String) {
    TSV("tsv", "text/tab-separated-values"),
    CSV("csv", "text/csv"),
    JSON("json", "application/json"),
    /** A Rime `*.dict.yaml` word list: Chinese only, the lines after `...`. */
    RIME("dict.yaml", "application/x-yaml");

    companion object {
        /** Guesses the format from a file name, or null when the name does not say. */
        fun of(name: String?): Format? {
            val n = name?.lowercase() ?: return null
            return when {
                n.endsWith(".dict.yaml") || n.endsWith(".yaml") -> RIME
                n.endsWith(".tsv") || n.endsWith(".txt") -> TSV
                n.endsWith(".csv") -> CSV
                n.endsWith(".json") -> JSON
                else -> null
            }
        }
    }
}

/** Why an import line or a whole file was refused; the settings show it with the line number. */
enum class Problem {
    ENCODING, SYNTAX, NEWER_FORMAT, COLUMNS, LANGUAGE, EMPTY_TEXT, TEXT_TOO_LONG, TEXT_CHARACTERS,
    NO_READING, READING, READING_COUNT, WEIGHT,
}

/** One refused line: its 1-based line number in the file (0 for the whole file) and why. */
data class LineError(val line: Int, val problem: Problem)

/** Thrown when a whole file cannot be read: not strict UTF-8, malformed, or from a newer format. */
class ImportException(val problem: Problem, val line: Int = 0) : Exception(problem.name)

/**
 * The result of reading one file, before anything is stored: the valid [entries] (weights
 * scaled, file duplicates dropped), how many lines were refused and the first few of them,
 * how many repeated an earlier line, and how many [conflicts] with the entries the person already
 * has (counted by [Formats.preview]).
 */
data class ImportPreview(
    val entries: List<Entry>,
    val refused: Int,
    val firstErrors: List<LineError>,
    val duplicates: Int,
    val conflicts: Int = 0,
)

/**
 * Reads and writes dictionary files (PLAN §10.5) as pure functions on bytes and text, so every
 * check is unit-tested on the JVM: strict UTF-8 without a BOM, the columns, the language, the
 * word, the reading ([Readings]) and the weight ([Weights]); duplicates inside the file; conflicts
 * with existing entries.
 *
 * Notes: imported files come from anywhere, so nothing in them is trusted: every entry becomes
 * [Source.IMPORT] or [Source.GENERATED] whatever the file claims, and a JSON file from a newer
 * format version is refused rather than half read. TSV and CSV take an optional header naming the
 * columns (`language`, `text` or `word`, `reading`, `weight`, others kept as extra fields);
 * without one the columns are language, text, reading, weight. Dictionary packs are TSV with
 * that header and normalized readings.
 */
object Formats {
    /** The JSON format version this build reads and writes. */
    const val JSON_VERSION = 1
    /** How many refused lines the preview lists. */
    const val SHOWN_ERRORS = 5
    /** The column names, in the order written without a header. */
    val COLUMNS = listOf("language", "text", "reading", "weight")

    /**
     * Decodes [bytes] as strict UTF-8.
     * Returns: the text without a leading BOM. Throws [ImportException] (ENCODING) on any invalid
     * byte sequence, and on U+FFFD, which a broken earlier conversion leaves behind.
     */
    fun decode(bytes: ByteArray): String {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw ImportException(Problem.ENCODING)
        }
        val line = text.indexOf('\uFFFD').takeIf { it >= 0 }?.let { at -> text.substring(0, at).count { it == '\n' } + 1 }
        if (line != null) throw ImportException(Problem.ENCODING, line)
        return text.removePrefix("\uFEFF")
    }

    /**
     * Reads one file.
     * Inputs: lang is the language for rows that name none (TSV and CSV without a language
     * column, JSON entries without one); generated marks a file made with an LLM, whose entries
     * become [Source.GENERATED] with weights capped at [Weights.GENERATED_MAX].
     * Returns: the preview without conflicts (see [preview]). Throws [ImportException] when the
     * whole file is unreadable.
     */
    fun read(bytes: ByteArray, format: Format, lang: Lang, generated: Boolean): ImportPreview {
        val text = decode(bytes)
        val rows = when (format) {
            Format.TSV -> table(text.lines().mapIndexed { i, l -> i + 1 to l.removeSuffix("\r").split('\t') }.filter { (_, c) -> c.any { it.isNotBlank() } })
            Format.CSV -> table(csv(text))
            Format.JSON -> json(text)
            Format.RIME -> rime(text)
        }
        return validate(rows, lang, if (generated) Source.GENERATED else Source.IMPORT)
    }

    /**
     * Counts the [conflicts] of a preview with the entries already stored: entries with the same
     * language, word and reading. Returns: the preview with that count.
     */
    fun preview(read: ImportPreview, existing: Collection<Entry>): ImportPreview {
        val keys = existing.mapTo(HashSet()) { it.key }
        return read.copy(conflicts = read.entries.count { it.key in keys })
    }

    /** One row as the file holds it, before checking: its line, the fields by column name, the rest. */
    private data class Row(val line: Int, val fields: Map<String, Any?>)

    /** Maps TSV or CSV records to rows, through the header when the first record is one. */
    private fun table(records: List<Pair<Int, List<String>>>): List<Row> {
        if (records.isEmpty()) return emptyList()
        val first = records.first().second.map { it.trim().lowercase() }
        val header = "text" in first || "word" in first
        val names = if (header) first.map { if (it == "word") "text" else it } else COLUMNS
        if (header && names.distinct().size != names.size) throw ImportException(Problem.COLUMNS, records.first().first)
        return records.drop(if (header) 1 else 0).map { (line, cells) ->
            if (cells.size > names.size) return@map Row(line, mapOf(COLUMNS_ERROR to true))
            Row(line, names.zip(cells).filter { it.second.isNotEmpty() }.toMap())
        }
    }

    /**
     * Splits CSV (RFC 4180) into records: quoted fields may hold commas, quotes written twice and
     * line breaks. Returns: each record with the line it starts on. Throws on an unclosed quote.
     */
    private fun csv(text: String): List<Pair<Int, List<String>>> {
        val records = ArrayList<Pair<Int, List<String>>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var line = 1
        var start = 1
        var quoted = false
        var i = 0
        fun endRecord() {
            fields.add(field.toString())
            field.clear()
            if (fields.any { it.isNotBlank() }) records.add(start to fields)
            fields = ArrayList()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> { if (c == '\n') line++; field.append(c) }
                c == '"' && field.isEmpty() -> quoted = true
                c == ',' -> { fields.add(field.toString()); field.clear() }
                c == '\r' && text.getOrNull(i + 1) == '\n' -> {}
                c == '\n' -> { endRecord(); line++; start = line }
                else -> field.append(c)
            }
            i++
        }
        if (quoted) throw ImportException(Problem.SYNTAX, start)
        endRecord()
        return records
    }

    /**
     * Reads the JSON format (the fields of PLAN appendix B): an object whose `entries` array holds
     * objects with `language`, `text` (or `word`), `reading` and `weight`; any other member is kept.
     * Throws on malformed JSON and on a `format_version` newer than [JSON_VERSION].
     */
    private fun json(text: String): List<Row> {
        val root = try {
            Json.parse(text)
        } catch (e: Json.JsonException) {
            throw ImportException(Problem.SYNTAX, text.substring(0, e.offset.coerceIn(0, text.length)).count { it == '\n' } + 1)
        }
        @Suppress("UNCHECKED_CAST")
        val o = root as? Map<String, Any?> ?: throw ImportException(Problem.SYNTAX, 1)
        val version = o["format_version"]
        if (version != null && (version !is Long || version > JSON_VERSION)) throw ImportException(Problem.NEWER_FORMAT)
        val entries = o.list("entries") ?: throw ImportException(Problem.SYNTAX, 1)
        // A JSON entry has no line of its own; its position (1-based) stands in.
        return entries.mapIndexed { i, e ->
            @Suppress("UNCHECKED_CAST")
            val m = e as? Map<String, Any?> ?: return@mapIndexed Row(i + 1, mapOf(COLUMNS_ERROR to true))
            Row(i + 1, m.mapKeys { (k, _) -> if (k == "word" && "text" !in m) "text" else k })
        }
    }

    /** Reads a Rime word list: after the `...` line, `text<TAB>code[<TAB>weight]`, `#` comments. */
    private fun rime(text: String): List<Row> {
        val lines = text.lines()
        val body = lines.indexOfFirst { it.trimEnd() == "..." }
        if (body < 0) throw ImportException(Problem.SYNTAX, 1)
        return lines.withIndex().drop(body + 1)
            .filter { (_, l) -> l.isNotBlank() && !l.startsWith("#") }
            .map { (i, l) ->
                val c = l.removeSuffix("\r").split('\t')
                if (c.size > 4) return@map Row(i + 1, mapOf(COLUMNS_ERROR to true))
                Row(i + 1, buildMap {
                    put("language", Lang.ZH.code)
                    put("text", c[0])
                    c.getOrNull(1)?.let { put("reading", it) }
                    c.getOrNull(2)?.let { put("weight", it) }
                })
            }
    }

    /** Checks every row and turns the valid ones into entries of [source] with scaled weights. */
    private fun validate(rows: List<Row>, defaultLang: Lang, source: Source): ImportPreview {
        val errors = ArrayList<LineError>()
        val valid = ArrayList<Triple<Entry, Double?, Int>>()
        for (row in rows) {
            val f = row.fields
            fun refuse(p: Problem) { errors.add(LineError(row.line, p)) }
            if (f[COLUMNS_ERROR] == true) { refuse(Problem.COLUMNS); continue }
            val lang = when (val l = f["language"]) {
                null -> defaultLang
                is String -> Lang.of(l) ?: run { refuse(Problem.LANGUAGE); null }
                else -> { refuse(Problem.LANGUAGE); null }
            } ?: continue
            val (text, textProblem) = Readings.text(f["text"] as? String ?: "")
            if (text == null) { refuse(textProblem!!); continue }
            val rawReading = f["reading"]
            if (rawReading != null && rawReading !is String) { refuse(Problem.READING); continue }
            val (reading, readingProblem) = Readings.reading(lang, text, rawReading as String?)
            if (readingProblem != null) { refuse(readingProblem); continue }
            val weight: Double? = when (val w = f["weight"]) {
                null -> null
                is Long -> w.toDouble()
                is Double -> w
                is String -> if (w.isBlank()) null else w.trim().toDoubleOrNull() ?: Double.NaN
                else -> Double.NaN
            }
            if (weight != null && (weight.isNaN() || weight.isInfinite() || weight < 0)) { refuse(Problem.WEIGHT); continue }
            val extra = f.filterKeys { it !in COLUMNS && it != COLUMNS_ERROR }
            valid.add(Triple(Entry(lang, text, reading, 0, source, extra), weight, row.line))
        }
        val weights = Weights.scale(valid.map { it.second }, generated = source == Source.GENERATED)
        val seen = HashSet<Triple<Lang, String, String?>>()
        var duplicates = 0
        val entries = ArrayList<Entry>()
        valid.forEachIndexed { i, (entry, _, _) ->
            if (seen.add(entry.key)) entries.add(entry.copy(weight = weights[i])) else duplicates++
        }
        return ImportPreview(entries, errors.size, errors.take(SHOWN_ERRORS), duplicates)
    }

    /** Marks a row with more cells than columns, or a JSON entry that is not an object. */
    private const val COLUMNS_ERROR = "\u0000columns"

    /**
     * Writes [entries] in [format].
     * Returns: the file's text. TSV and CSV carry the header; JSON the format version and each
     * entry's extra fields; a Rime word list only the Chinese entries, with spaced pinyin.
     * Notes: the source is never written: an exported file imported again is untrusted like any
     * other file.
     */
    fun write(entries: List<Entry>, format: Format): String = when (format) {
        Format.TSV -> (listOf(COLUMNS) + entries.map(::cells)).joinToString("") { it.joinToString("\t", postfix = "\n") }
        Format.CSV -> (listOf(COLUMNS) + entries.map(::cells)).joinToString("") { row ->
            row.joinToString(",", postfix = "\r\n") { if (it.any { c -> c == ',' || c == '"' || c == '\n' || c == '\r' }) "\"" + it.replace("\"", "\"\"") + "\"" else it }
        }
        Format.JSON -> Json.write(
            linkedMapOf(
                "format_version" to JSON_VERSION.toLong(),
                "entries" to entries.map { e ->
                    LinkedHashMap<String, Any?>().apply {
                        put("language", e.lang.code)
                        put("text", e.text)
                        e.reading?.let { put("reading", it) }
                        put("weight", e.weight.toLong())
                        putAll(e.extra.filterKeys { it !in COLUMNS })
                    }
                },
            ),
            indent = true,
        ) + "\n"
        Format.RIME -> buildString {
            append("# Kotomo export (Chinese entries)\n---\nname: kotomo_export\nversion: \"1\"\nsort: by_weight\n...\n")
            entries.filter { it.lang == Lang.ZH && it.reading != null }.forEach { append(it.text).append('\t').append(it.reading).append('\t').append(it.weight).append('\n') }
        }
    }

    /** The TSV and CSV cells of one entry, in [COLUMNS] order. */
    private fun cells(e: Entry) = listOf(e.lang.code, e.text, e.reading.orEmpty(), e.weight.toString())
}
