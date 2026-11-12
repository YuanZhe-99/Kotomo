package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.engine.Core
import com.yuanzhe.kotomo.engine.English
import com.yuanzhe.kotomo.engine.Mozc
import com.yuanzhe.kotomo.engine.Rime
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the conversion corpus (tests/conversion/corpus.tsv, PLAN.md §13.3) on the host through the
 * app's engine facades and its candidate assembly, and compares every line with the accepted
 * baseline (tests/conversion/baseline.tsv).
 *
 * Notes: runs only when tests/conversion/run.sh sets `kotomo.corpus.root` (the repository root,
 * whose build/ holds the host engine libraries and data); a plain `./gradlew check` skips it.
 * Writes build/conversion/results.tsv and build/conversion/report.txt. Fails when a line that
 * passed in the baseline no longer passes, or when the corpus is malformed; lines that fail in the
 * baseline too are known gaps, reported but not failing. Host results never stand in for device
 * results.
 *
 * A Chinese or Mixed line may name fuzzy-pinyin rules after its mode (`chinese+fuzzy:z_zh,n_l`, or
 * `+fuzzy:all`): the line then runs on a librime build deployed with the rule file the app itself
 * generates ([Fuzzy.yaml]), as the keyboard's deploy process does (T08).
 */
class ConversionCorpusTest {
    /**
     * One corpus line; [negative] lines list outputs that must not appear within [rank]; [fuzzy]
     * holds the fuzzy-pinyin rules it runs with (none for a plain mode).
     */
    private data class Line(
        val id: String,
        val mode: Mode,
        val context: String,
        val input: String,
        val expected: List<String>,
        val negative: Boolean,
        val rank: Int,
        val fuzzy: Set<FuzzyRule> = emptySet(),
    )

    /** One line's outcome: whether it passed, the position that decided it (1-based), the list. */
    private data class Outcome(val pass: Boolean, val position: Int?, val shown: List<String>)

    private lateinit var mozc: Mozc
    private lateinit var english: English
    private var core: Core? = null
    private lateinit var lexicon: File
    private var generation = 0L
    private lateinit var emoji: EmojiIndex
    private lateinit var rimeShared: File
    private lateinit var tmp: File
    /** The fuzzy-pinyin rules of the librime build open now. */
    private var rimeRules = emptySet<FuzzyRule>()

    @Test
    fun corpusHasNoRegressions() {
        val root = System.getProperty("kotomo.corpus.root")?.let(::File)
        assumeTrue("run tests/conversion/run.sh", root?.isDirectory == true)
        root!!
        val lines = parse(root.resolve("tests/conversion/corpus.tsv").readLines())
        tmp = Files.createTempDirectory("kotomo-corpus").toFile()
        try {
            open(root, tmp)
            // Lines with the same fuzzy rules run together, so each rule set deploys once.
            val outcomes = lines.sortedBy { line -> line.fuzzy.joinToString(",") { it.key } }
                .associate { it.id to judge(it, candidates(it)) }
            report(root, lines, outcomes)
        } finally {
            core?.close()
            Rime.shutdown()
            if (::mozc.isInitialized) mozc.close()
            if (::english.isInitialized) english.close()
            tmp.deleteRecursively()
        }
    }

    /**
     * Parses the corpus.
     * Returns: the lines in file order.
     * Notes: the corpus is an input file, so every column is checked: seven columns, a known mode,
     * a rank of 0, 1, 3 or 5, unique ids, and expected outputs that are all positive or all
     * negative.
     */
    private fun parse(text: List<String>): List<Line> {
        val lines = text.withIndex().filter { (_, l) -> l.isNotBlank() && !l.startsWith("#") && !l.startsWith("id\t") }
            .map { (n, l) ->
                val c = l.split('\t')
                require(c.size == 7) { "corpus line ${n + 1}: ${c.size} columns, want 7" }
                val modeName = c[1].substringBefore('+')
                val mode = Mode.entries.firstOrNull { it.name.equals(modeName, ignoreCase = true) }
                    ?: error("corpus line ${n + 1}: unknown mode ${c[1]}")
                val fuzzy = if ('+' in c[1]) {
                    val option = c[1].substringAfter('+')
                    require(option.startsWith("fuzzy:") && (mode == Mode.CHINESE || mode == Mode.MIXED)) {
                        "corpus line ${n + 1}: unknown option $option for ${mode.name.lowercase()}"
                    }
                    val names = option.removePrefix("fuzzy:")
                    if (names == "all") {
                        FuzzyRule.entries.toSet()
                    } else {
                        names.split(',').mapTo(mutableSetOf()) { name ->
                            FuzzyRule.entries.firstOrNull { it.key == name } ?: error("corpus line ${n + 1}: unknown fuzzy rule $name")
                        }
                    }
                } else {
                    emptySet()
                }
                val rank = c[5].toIntOrNull()
                require(rank in setOf(0, 1, 3, 5)) { "corpus line ${n + 1}: rank ${c[5]}" }
                val alternatives = c[4].split('|')
                val negative = alternatives.all { it.startsWith("!") }
                require(negative || alternatives.none { it.startsWith("!") }) { "corpus line ${n + 1}: mixed ! and plain outputs" }
                require(alternatives.all { it.removePrefix("!").isNotEmpty() }) { "corpus line ${n + 1}: empty output" }
                Line(c[0], mode, c[2], c[3], alternatives.map { it.removePrefix("!") }, negative, rank!!, fuzzy)
            }
        val duplicates = lines.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate corpus ids: $duplicates" }
        return lines
    }

    /** Opens the three engines and the emoji index on the host builds under [root]/build. */
    private fun open(root: File, tmp: File) {
        val build = root.resolve("build")
        rimeShared = build.resolve("rime-prebuilt")
        val rimeUser = tmp.resolve("rime").apply { mkdirs() }
        check(Rime.initialize(rimeShared.path, rimeUser.path)) { "librime did not open on build/rime-prebuilt" }
        Rime.setOption("zh_hans", true)
        mozc = checkNotNull(Mozc.open(build.resolve("mozc/out/host/mozc.data").path, tmp.resolve("mozc").apply { mkdirs() }.path)) {
            "Mozc did not open build/mozc/out/host/mozc.data"
        }
        english = checkNotNull(English.open(build.resolve("lexicon/english/main_en_us.dict").path)) {
            "the English dictionary did not open"
        }
        lexicon = build.resolve("lexicon/cross.klx")
        check(lexicon.isFile) { "no compiled cross-language lexicon (kotomo_lexicon_compile)" }
        emoji = build.resolve("emoji/emoji.tsv").bufferedReader().useLines { EmojiIndex.parse(it.toList().asSequence()) }
    }

    /**
     * Lists the candidates the candidate bar would show for [line], with the keyboard's defaults
     * (Simplified Chinese, emoji suggestions on).
     * Notes: Chinese hands the whole input to librime; Mixed types it key by key into the
     * coordinator through [Core], as the keyboard does, and assembles the snapshot with the
     * default emoji weight; Japanese converts the letters in one Mozc session, predicts from
     * their reading and adds the coordinator's cross-language candidates; English asks the English engine, with the context as the previous word.
     */
    private fun candidates(line: Line): List<String> = when (line.mode) {
        Mode.CHINESE -> {
            useRules(line.fuzzy)
            Rime.clear()
            Rime.setInput(line.input)
            val texts = Rime.getCandidates(Candidates.CHINESE_LIMIT).map { it.text }
            Candidates.chinese(texts, Rime.getInput(), Candidates.emoji(emoji, Rime.getComposition())).map { it.first }
        }
        Mode.MIXED -> {
            useRules(line.fuzzy)
            Rime.clear()
            val c = core ?: checkNotNull(Core.open(rime = true, mozc, english, lexicon.path)) { "the coordinator did not open" }.also {
                core = it
                check(it.lexiconEntries > 0) { "the cross-language lexicon did not map" }
            }
            c.clear(++generation)
            var snapshot = checkNotNull(c.clear(++generation))
            for (key in line.input) snapshot = checkNotNull(c.key(++generation, key)) { "no snapshot for a key of ${line.id}" }
            check(snapshot.generation == generation && snapshot.input == line.input) { "${line.id}: the snapshot answers another request" }
            Candidates.mixed(
                snapshot.candidates.map { it.text }, snapshot.input, Candidates.emoji(emoji, line.input),
                Candidates.mixedEmojiPosition(MIXED_EMOJI_WEIGHT),
            ).map { it.first }
        }
        Mode.JAPANESE -> {
            val segments = mozc.sessionConvert(line.input, incognito = false).orEmpty()
            val reading = segments.joinToString("") { it.reading }
            val predicted = if (reading.isEmpty()) emptyList() else mozc.predict(reading, incognito = false)
            // As the keyboard does: the coordinator's cross-language candidates, Japanese only.
            Rime.clear()
            val c = core ?: checkNotNull(Core.open(rime = true, mozc, english, lexicon.path)) { "the coordinator did not open" }.also { core = it }
            c.options(++generation, Core.Options(chinese = false, english = false))
            val crossed = Candidates.japaneseCross(c.set(++generation, line.input))
            c.clear(++generation)
            c.options(++generation, Core.Options())
            val own = Candidates.japanese(segments, predicted)
            Candidates.withEmoji(Candidates.japaneseWithCross(own, crossed, reading).map { it.text }, Candidates.emoji(emoji, line.input))
        }
        Mode.ENGLISH -> if (line.input.isEmpty() && line.context.isEmpty()) {
            emptyList()
        } else {
            val words = english.suggestions(line.input, previous = line.context.ifEmpty { null }).map { it.word }
            Candidates.withEmoji(Candidates.english(line.input, words), Candidates.emoji(emoji, line.input))
        }
        // The PC layout converts nothing; no corpus line uses it.
        Mode.PC -> emptyList()
    }

    /**
     * Opens librime on a build with the fuzzy-pinyin [rules]: the prebuilt data for none, else a
     * user directory holding the app's rule file, deployed on first use (as the deploy process
     * deploys into its staging directory).
     */
    private fun useRules(rules: Set<FuzzyRule>) {
        if (rules == rimeRules) return
        val text = Fuzzy.yaml(rules)
        val user = tmp.resolve("rime-" + Fuzzy.hash(text).take(12))
        val fresh = !user.isDirectory
        user.mkdirs()
        if (text != null) user.resolve(Fuzzy.FILE).writeText(text)
        Rime.initialize(rimeShared.path, user.path)
        if (fresh && text != null) check(Rime.deploy()) { "librime did not deploy the fuzzy rules ${rules.map { it.key }}: ${Rime.deployFailures()}" }
        Rime.setOption("zh_hans", true)
        rimeRules = rules
    }

    /**
     * Decides a line: a positive line passes when an expected output is within its rank (0: in
     * the list at all); a negative line passes when none is.
     * Returns: for a positive line the position of the first expected output anywhere in the
     * list; for a negative line the position of the first forbidden output within the rank.
     */
    private fun judge(line: Line, shown: List<String>): Outcome {
        val within = if (line.rank == 0) shown.size else minOf(line.rank, shown.size)
        return if (line.negative) {
            val hit = shown.take(within).indexOfFirst { it in line.expected }
            Outcome(hit < 0, if (hit < 0) null else hit + 1, shown)
        } else {
            val at = shown.indexOfFirst { it in line.expected }
            Outcome(at in 0 until within, if (at < 0) null else at + 1, shown)
        }
    }

    /**
     * Writes the results and the report, then fails on regressions against the baseline.
     * Side effects: writes build/conversion/results.tsv and build/conversion/report.txt.
     */
    private fun report(root: File, lines: List<Line>, outcomes: Map<String, Outcome>) {
        val out = root.resolve("build/conversion").apply { mkdirs() }
        val results = lines.map { line ->
            val o = outcomes.getValue(line.id)
            listOf(line.id, if (o.pass) "PASS" else "FAIL", o.position?.toString() ?: "-", o.shown.take(SHOWN).joinToString(" / "))
                .joinToString("\t")
        }
        out.resolve("results.tsv").writeText((listOf(RESULTS_HEADER) + results).joinToString("\n", postfix = "\n"))

        val baseline = root.resolve("tests/conversion/baseline.tsv").takeIf { it.isFile }?.readLines().orEmpty()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("id\t") }
            .associate { it.substringBefore('\t') to it.split('\t').getOrElse(1) { "" } }
        val now = outcomes.mapValues { if (it.value.pass) "PASS" else "FAIL" }
        val regressions = lines.filter { baseline[it.id] == "PASS" && now[it.id] == "FAIL" }.map { it.id }
        val fixed = lines.filter { baseline[it.id] == "FAIL" && now[it.id] == "PASS" }.map { it.id }
        val added = lines.filter { it.id !in baseline }.map { it.id }

        val text = buildString {
            appendLine("Conversion corpus: ${now.count { it.value == "PASS" }} of ${lines.size} lines pass")
            lines.groupBy { it.id.substringBefore('-') }.forEach { (area, group) ->
                appendLine("  $area: ${group.count { now[it.id] == "PASS" }} of ${group.size}")
            }
            appendLine()
            lines.forEach { line ->
                val o = outcomes.getValue(line.id)
                val want = line.expected.joinToString("|") { if (line.negative) "!$it" else it }
                val mode = line.mode.name.lowercase() + if (line.fuzzy.isEmpty()) "" else "+fuzzy:" + line.fuzzy.joinToString(",") { it.key }
                appendLine("${now[line.id]} ${line.id} $mode '${line.input}' want $want within ${line.rank}: " +
                    "at ${o.position ?: "-"} [${o.shown.take(SHOWN).joinToString(" / ")}]")
            }
            appendLine()
            appendLine("Against tests/conversion/baseline.tsv: ${regressions.size} regressed, ${fixed.size} fixed, ${added.size} new")
            regressions.forEach { appendLine("  REGRESSED $it") }
            fixed.forEach { appendLine("  FIXED $it") }
            added.forEach { appendLine("  NEW $it") }
        }
        out.resolve("report.txt").writeText(text)
        assertTrue("corpus lines regressed: $regressions (build/conversion/report.txt)", regressions.isEmpty())
    }

    private companion object {
        /** Candidates kept per line in the results and the report. */
        const val SHOWN = 5
        /** The default of `mixed.weight_emoji`. */
        const val MIXED_EMOJI_WEIGHT = 30
        const val RESULTS_HEADER = "id\tstatus\tposition\tshown"
    }
}
