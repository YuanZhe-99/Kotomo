package com.yuanzhe.kotomo.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yuanzhe.kotomo.ime.FuzzyRule
import com.yuanzhe.kotomo.ime.Mode
import com.yuanzhe.kotomo.ime.Tool

/** Chooses when the keyboard splits into two thumb-zone halves. */
enum class SplitLayout { AUTO, ALWAYS, NEVER }

/** Chooses one-handed mode: off, or the keys pushed toward the left or right thumb. */
enum class OneHanded { OFF, LEFT, RIGHT }

/**
 * Chooses the script Chinese is committed in: Mainland Simplified, OpenCC standard Traditional, or
 * Taiwan or Hong Kong Traditional with their regional phrases (PLAN §8.4).
 */
enum class ChineseScript { SIMPLIFIED, TRADITIONAL, TAIWAN, HONG_KONG }

/** The keyboard's colors: following the system, always light, always dark, or Material You. */
enum class KeyboardTheme { SYSTEM, LIGHT, DARK, DYNAMIC }

/** The computer the PC layout types to (PLAN §10.11): names the modifiers and the shortcuts. */
enum class PcHost { WINDOWS, MACOS, LINUX }

/** The PC layout on a wide screen: the whole PC keyboard, or two phone-sized halves at the sides. */
enum class PcWide { FULL, SPLIT }

/** Chooses how strongly Mixed mode offers cross-language candidates (PLAN §2.3). */
enum class MixedLevel { CONSERVATIVE, BALANCED, AGGRESSIVE }

/**
 * Chooses what the space key does while a composition is open (ADR-010): mark a boundary and
 * keep typing, commit the first candidate, or convert and then move to the next candidate.
 *
 * Notes: each of Chinese, Japanese and Mixed mode keeps its own choice; only Mixed mode offers
 * [SEPARATE] (see [CompositionKeys.SPACE_CHOICES]).
 */
enum class SpaceKey { SEPARATE, COMMIT_FIRST, NEXT }

/**
 * Chooses what Enter commits while a composition is open (ADR-010): the composition as the field
 * shows it (the letters, or the kana in Japanese mode), or the first candidate.
 *
 * Notes: after the space key chose a candidate ([SpaceKey.NEXT]) the field shows that candidate,
 * and Enter commits it under either choice.
 */
enum class EnterKey { SHOWN, FIRST }

/** Holds one input mode's space and Enter behavior during composition. */
data class CompositionKeys(val space: SpaceKey, val enter: EnterKey) {
    companion object {
        /** Chinese mode as shipped: space commits the first candidate, Enter the letters. */
        val CHINESE = CompositionKeys(SpaceKey.COMMIT_FIRST, EnterKey.SHOWN)
        /** Japanese mode as Japanese keyboards do: space converts, Enter commits what is shown. */
        val JAPANESE = CompositionKeys(SpaceKey.NEXT, EnterKey.SHOWN)
        /** Mixed mode (ADR-010): space separates, Enter commits the first candidate. */
        val MIXED = CompositionKeys(SpaceKey.SEPARATE, EnterKey.FIRST)

        /** The space choices a mode offers: a boundary inside the composition exists in Mixed mode only. */
        fun spaceChoices(mode: Mode): List<SpaceKey> =
            if (mode == Mode.MIXED) SpaceKey.entries else listOf(SpaceKey.COMMIT_FIRST, SpaceKey.NEXT)
    }
}

/**
 * Holds the cross-language conversion switches of one input mode (PLAN §9.10); every switch is
 * on by default.
 *
 * Notes: Japanese mode and Mixed mode each keep their own copy.
 */
data class CrossLanguage(
    val pinyinToKanji: Boolean = true,
    val pinyinToKatakana: Boolean = true,
    val englishToKanji: Boolean = true,
    val englishToKatakana: Boolean = true,
    val wholeSentence: Boolean = true,
    val readings: Boolean = true,
    val emoji: Boolean = true,
)

/**
 * Holds Mixed mode's settings: the level, the cross-language switches, the per-language candidate
 * weights (0–100) and the space and Enter keys' behavior during composition.
 */
data class Mixed(
    val level: MixedLevel = MixedLevel.BALANCED,
    val cross: CrossLanguage = CrossLanguage(),
    val weightChinese: Int = 70,
    val weightEnglish: Int = 60,
    val weightJapanese: Int = 60,
    val weightEmoji: Int = 30,
    val keys: CompositionKeys = CompositionKeys.MIXED,
)

/**
 * Holds the clipboard settings (PLAN §10.7): whether copied text is kept in the history, for how
 * many days unpinned items are kept, and whether the quick-paste strip offers what was just copied.
 *
 * Notes: none of them records anything in sensitive fields, for clips marked sensitive or in
 * incognito mode.
 */
data class ClipboardSettings(
    val history: Boolean = true,
    val retentionDays: Int = 7,
    val quickPaste: Boolean = true,
    /** Package names of the apps in whose fields nothing is recorded or offered (PLAN §10.7). */
    val excludedApps: Set<String> = emptySet(),
)

/**
 * Holds the switch of every keyboard gesture (PLAN §8.1); all on by default.
 *
 * Notes: [spaceCursor] moves the cursor by sliding along the space bar; [deleteSwipe] deletes
 * whole words by sliding left from backspace; [doubleSpacePeriod] turns two quick spaces after a
 * word into a full stop; [spaceLanguage] opens the language choice on a long press of the space
 * bar; [smartSpace] drops the space typed after a picked English word before punctuation and adds
 * one after it.
 */
data class Gestures(
    val spaceCursor: Boolean = true,
    val deleteSwipe: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val spaceLanguage: Boolean = true,
    val smartSpace: Boolean = true,
)

/**
 * Holds the AI settings (PLAN §11): whether the keyboard's AI button may hand text to Gemini Nano.
 *
 * Notes: off by default; while off, Kotomo makes no GenAI call at all.
 */
data class AiSettings(
    val enabled: Boolean = false,
)

/**
 * Holds every setting the person can change in the settings app, with the defaults a fresh
 * install starts from.
 *
 * Notes: immutable; change it with `copy` through [SettingsStore.set]. Stored as a versioned
 * Preferences DataStore file (see [SettingsFormat]); the format is documented in
 * `doc/en-us/settings-format.md`.
 */
data class Settings(
    val defaultMode: Mode = Mode.MIXED,
    val modeOrder: List<Mode> = Mode.entries,
    val hiddenModes: Set<Mode> = emptySet(),
    val mixed: Mixed = Mixed(),
    val japanese: CrossLanguage = CrossLanguage(),
    val japaneseKeys: CompositionKeys = CompositionKeys.JAPANESE,
    val chineseKeys: CompositionKeys = CompositionKeys.CHINESE,
    val chineseScript: ChineseScript = ChineseScript.SIMPLIFIED,
    /** The fuzzy-pinyin rules turned on (PLAN §9.6); none by default. */
    val chineseFuzzy: Set<FuzzyRule> = emptySet(),
    val englishSuggestions: Boolean = true,
    val englishAutoCapitalize: Boolean = true,
    val split: SplitLayout = SplitLayout.AUTO,
    val thumbZoneDp: Int = 268,
    val tabletopLowerHalf: Boolean = true,
    val oneHanded: OneHanded = OneHanded.OFF,
    val heightPercent: Int = 100,
    val longPressSymbols: Boolean = true,
    val keyHints: Boolean = true,
    val haptics: Boolean = true,
    val keySound: Boolean = false,
    val gestures: Gestures = Gestures(),
    /** Curly quotes for the ASCII quote keys (PLAN §10.8); off by default. */
    val smartQuotes: Boolean = false,
    /** An opening bracket or quote types its closing one too (PLAN §10.8); off by default. */
    val autoPair: Boolean = false,
    val incognito: Boolean = false,
    /** Keeps Mixed mode's learned readings encrypted at rest (PLAN §14); off by default. */
    val learnedEncrypted: Boolean = false,
    val clipboard: ClipboardSettings = ClipboardSettings(),
    val theme: KeyboardTheme = KeyboardTheme.SYSTEM,
    val keyRadiusDp: Int = 12,
    val ai: AiSettings = AiSettings(),
    /** Whether the PC layout is offered (PLAN §10.11); off by default, unlike the other modes. */
    val pcEnabled: Boolean = false,
    val pcHost: PcHost = PcHost.WINDOWS,
    val pcWide: PcWide = PcWide.FULL,
    /** The toolbar's tools after More, in the person's order (PLAN §10.10). */
    val toolOrder: List<Tool> = Tool.configurable,
    /** The tools kept off the toolbar; they wait under More. */
    val hiddenTools: Set<Tool> = Tool.offBar,
    /** Show an autofill service's inline suggestions in the bar (PLAN §10.10, Android 11 and later). */
    val inlineAutofill: Boolean = true,
    /** Key and layout customizations (PLAN §10.9). */
    val keyStyle: KeyStyle = KeyStyle(),
    /** The keyboard floats over the app and can be dragged (PLAN §10.9). */
    val floating: Boolean = false,
    /** Japanese mode uses the 12-key flick layout instead of romaji on QWERTY (PLAN §10.9). */
    val japaneseFlick: Boolean = false,
    /** After the first characters of an idiom, offer the rest (needs the Idioms dictionary; PLAN §10.8). */
    val idioms: Boolean = true,
    /** `v` at the start of a Chinese composition opens the quick input (PLAN §10.4). */
    val quickInput: Boolean = true,
    /** The person's quick-input replacements, `abbreviation=text` per line (QuickInput.parseCustom). */
    val quickCustom: String = "",
    /** `u` at the start of a Chinese composition looks characters up by strokes (PLAN §10.4). */
    val strokeLookup: Boolean = true,
) {
    /** The tools the toolbar shows after More, in order; the rest are under More. */
    val tools: List<Tool> get() = toolOrder.filter { it !in hiddenTools }

    /**
     * The modes the language choice offers, in the person's order (PLAN §2.3).
     * Notes: never empty; with every mode hidden all of them are offered. The PC layout is offered
     * only while [pcEnabled] is on, so it stays hidden for people who never turned it on.
     */
    val modes: List<Mode> get() = modeOrder.filter { it !in hiddenModes && (it != Mode.PC || pcEnabled) }
        .ifEmpty { modeOrder.filter { it != Mode.PC } }

    /**
     * Returns [wanted] when the language choice offers it, otherwise the default mode, otherwise
     * the first mode offered (a hidden mode is never used).
     */
    /** The space and Enter behavior of [mode]'s compositions; null for modes without a choice. */
    fun keys(mode: Mode): CompositionKeys? = when (mode) {
        Mode.CHINESE -> chineseKeys
        Mode.JAPANESE -> japaneseKeys
        Mode.MIXED -> mixed.keys
        else -> null
    }

    fun usable(wanted: Mode?): Mode = wanted?.takeIf { it in modes } ?: defaultMode.takeIf { it in modes } ?: modes.first()

    companion object {
        /** Allowed keyboard heights, in percent of the designed height. */
        val HEIGHT_RANGE = 80..120
        /** Allowed widths of one split half, in dp. */
        val THUMB_RANGE = 200..340
        /** Allowed Mixed-mode candidate weights. */
        val WEIGHT_RANGE = 0..100
        /** Allowed clipboard retention, in days. */
        val RETENTION_RANGE = 1..90
        /** Allowed key corner radii, in dp. */
        val RADIUS_RANGE = 0..24
    }
}

/**
 * Holds the key and layout customizations of PLAN §10.9: the gap between keys, the size of key
 * labels, a row of digits above the letters, the space bar's width, the function keys mirrored to
 * the other side (Enter and ⇧ on the left), and the person's own long-press symbols per letter.
 * Notes: [longPress] maps a lowercase letter to the symbols its long press offers, the first also
 * drawn in the key's corner; [KeyStyle.parse] reads the settings' text form.
 */
data class KeyStyle(
    val gapDp: Int = 6,
    val textPercent: Int = 100,
    val numberRow: Boolean = false,
    val spacePercent: Int = 100,
    val mirrored: Boolean = false,
    val longPress: Map<Char, String> = emptyMap(),
) {
    companion object {
        /** Allowed gaps between keys, in dp. */
        val GAP_RANGE = 2..12
        /** Allowed key label sizes, in percent of the design's. */
        val TEXT_RANGE = 80..140
        /** Allowed space bar widths, in percent of the design's. */
        val SPACE_RANGE = 60..140
        /** The most symbols one letter's long press may offer. */
        const val MAX_SYMBOLS = 12

        /**
         * Reads long-press symbols from their text form: one `letter=symbols` per line. Lines
         * that are not a letter a–z, an equals sign and at least one symbol
         * are skipped; letters are lowercased, symbols cut at [MAX_SYMBOLS], the last line for a
         * letter wins.
         */
        fun parse(text: String): Map<Char, String> = text.split('\n').mapNotNull { line ->
            val at = line.indexOf('=')
            if (at < 0) return@mapNotNull null
            val letter = line.substring(0, at).trim().lowercase().singleOrNull()?.takeIf { it in 'a'..'z' } ?: return@mapNotNull null
            val cps = line.substring(at + 1).filterNot { it.isWhitespace() }.codePoints().toArray().take(MAX_SYMBOLS)
            if (cps.isEmpty()) null else letter to cps.joinToString("") { String(Character.toChars(it)) }
        }.toMap()

        /** Writes long-press symbols as [parse] reads them, one letter per line in a–z order. */
        fun format(map: Map<Char, String>): String = map.toSortedMap().entries.joinToString("\n") { (k, v) -> "$k=$v" }
    }
}

/**
 * Maps [Settings] to and from the keys of its Preferences file.
 *
 * Notes: enum values are stored by their lowercase constant name, so renaming a constant is a
 * format change. Values out of range or unknown (from a newer version) read as the default and
 * stay untouched in the file until the person changes that setting; keys this version does not
 * know are never removed.
 */
internal object SettingsFormat {
    /** The format version this build writes. */
    const val VERSION = 1

    val version = intPreferencesKey("format_version")
    val updated = longPreferencesKey("updated_utc_ms")
    private val defaultMode = stringPreferencesKey("default_mode")
    private val modeOrder = stringPreferencesKey("modes.order")
    private val hiddenModes = stringPreferencesKey("modes.hidden")
    private val mixedLevel = stringPreferencesKey("mixed.level")
    private val mixedWeightChinese = intPreferencesKey("mixed.weight_chinese")
    private val mixedWeightEnglish = intPreferencesKey("mixed.weight_english")
    private val mixedWeightJapanese = intPreferencesKey("mixed.weight_japanese")
    private val mixedWeightEmoji = intPreferencesKey("mixed.weight_emoji")
    private val mixedSpace = stringPreferencesKey("mixed.space")
    private val mixedEnter = stringPreferencesKey("mixed.enter")
    private val japaneseSpace = stringPreferencesKey("japanese.space")
    private val japaneseEnter = stringPreferencesKey("japanese.enter")
    private val chineseSpace = stringPreferencesKey("chinese.space")
    private val chineseEnter = stringPreferencesKey("chinese.enter")
    private val chineseScript = stringPreferencesKey("chinese.script")
    private val englishSuggestions = booleanPreferencesKey("english.suggestions")
    private val englishAutoCapitalize = booleanPreferencesKey("english.auto_capitalize")
    private val split = stringPreferencesKey("layout.split")
    private val thumbZone = intPreferencesKey("layout.thumb_zone_dp")
    private val tabletop = booleanPreferencesKey("layout.tabletop_lower_half")
    private val oneHanded = stringPreferencesKey("layout.one_handed")
    private val height = intPreferencesKey("layout.height_percent")
    private val longPress = booleanPreferencesKey("keys.long_press_symbols")
    private val keyHints = booleanPreferencesKey("keys.corner_hints")
    private val haptics = booleanPreferencesKey("keys.haptics")
    private val keySound = booleanPreferencesKey("keys.sound")
    private val spaceCursor = booleanPreferencesKey("gestures.space_cursor")
    private val deleteSwipe = booleanPreferencesKey("gestures.delete_swipe")
    private val doubleSpace = booleanPreferencesKey("gestures.double_space_period")
    private val spaceLanguage = booleanPreferencesKey("gestures.space_language")
    private val smartSpace = booleanPreferencesKey("gestures.smart_space")
    private val smartQuotes = booleanPreferencesKey("punctuation.smart_quotes")
    private val autoPair = booleanPreferencesKey("punctuation.auto_pair")
    private val incognito = booleanPreferencesKey("privacy.incognito")
    private val learnedEncrypted = booleanPreferencesKey("privacy.learned_encrypted")
    private val clipHistory = booleanPreferencesKey("clipboard.history")
    private val clipRetention = intPreferencesKey("clipboard.retention_days")
    private val clipQuickPaste = booleanPreferencesKey("clipboard.quick_paste")
    private val clipExcluded = stringPreferencesKey("clipboard.excluded_apps")
    private val theme = stringPreferencesKey("theme.choice")
    private val keyRadius = intPreferencesKey("theme.key_radius_dp")
    private val aiEnabled = booleanPreferencesKey("ai.enabled")
    private val pcEnabled = booleanPreferencesKey("pc.enabled")
    private val pcHost = stringPreferencesKey("pc.host")
    private val pcWide = stringPreferencesKey("pc.wide")
    private val toolOrder = stringPreferencesKey("toolbar.order")
    private val hiddenTools = stringPreferencesKey("toolbar.hidden")
    private val inlineAutofill = booleanPreferencesKey("toolbar.inline_autofill")
    private val keyGap = intPreferencesKey("keys.gap_dp")
    private val keyText = intPreferencesKey("keys.text_percent")
    private val numberRow = booleanPreferencesKey("layout.number_row")
    private val spaceWidth = intPreferencesKey("layout.space_percent")
    private val mirrored = booleanPreferencesKey("layout.mirrored")
    private val longPressMap = stringPreferencesKey("keys.long_press_custom")
    private val floating = booleanPreferencesKey("layout.floating")
    private val japaneseFlick = booleanPreferencesKey("japanese.flick")
    private val idioms = booleanPreferencesKey("chinese.idioms")
    private val quickInput = booleanPreferencesKey("chinese.quick_input")
    private val quickCustom = stringPreferencesKey("chinese.quick_custom")
    private val strokeLookup = booleanPreferencesKey("chinese.stroke_lookup")

    /**
     * Reads the settings from a Preferences file.
     * Returns: the stored values, with defaults for anything missing, unknown or out of range.
     * Notes: an empty file (a fresh install, no file yet) gives [Settings]'s defaults.
     */
    fun read(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            defaultMode = p.enum(defaultMode, Mode.entries, d.defaultMode),
            // Every mode appears once: unknown names are skipped, missing modes join at the end.
            modeOrder = (p.names(modeOrder, Mode.entries) + Mode.entries).distinct(),
            hiddenModes = p.names(hiddenModes, Mode.entries).toSet(),
            mixed = Mixed(
                level = p.enum(mixedLevel, MixedLevel.entries, d.mixed.level),
                cross = readCross(p, "mixed"),
                weightChinese = p.int(mixedWeightChinese, Settings.WEIGHT_RANGE, d.mixed.weightChinese),
                weightEnglish = p.int(mixedWeightEnglish, Settings.WEIGHT_RANGE, d.mixed.weightEnglish),
                weightJapanese = p.int(mixedWeightJapanese, Settings.WEIGHT_RANGE, d.mixed.weightJapanese),
                weightEmoji = p.int(mixedWeightEmoji, Settings.WEIGHT_RANGE, d.mixed.weightEmoji),
                keys = readKeys(p, Mode.MIXED, mixedSpace, mixedEnter, d.mixed.keys),
            ),
            japanese = readCross(p, "japanese"),
            japaneseKeys = readKeys(p, Mode.JAPANESE, japaneseSpace, japaneseEnter, d.japaneseKeys),
            chineseKeys = readKeys(p, Mode.CHINESE, chineseSpace, chineseEnter, d.chineseKeys),
            chineseScript = p.enum(chineseScript, ChineseScript.entries, d.chineseScript),
            chineseFuzzy = FuzzyRule.entries.filterTo(mutableSetOf()) { p[fuzzyKey(it)] ?: (it in d.chineseFuzzy) },
            englishSuggestions = p[englishSuggestions] ?: d.englishSuggestions,
            englishAutoCapitalize = p[englishAutoCapitalize] ?: d.englishAutoCapitalize,
            split = p.enum(split, SplitLayout.entries, d.split),
            thumbZoneDp = p.int(thumbZone, Settings.THUMB_RANGE, d.thumbZoneDp),
            tabletopLowerHalf = p[tabletop] ?: d.tabletopLowerHalf,
            oneHanded = p.enum(oneHanded, OneHanded.entries, d.oneHanded),
            heightPercent = p.int(height, Settings.HEIGHT_RANGE, d.heightPercent),
            longPressSymbols = p[longPress] ?: d.longPressSymbols,
            keyHints = p[keyHints] ?: d.keyHints,
            haptics = p[haptics] ?: d.haptics,
            keySound = p[keySound] ?: d.keySound,
            gestures = Gestures(
                spaceCursor = p[spaceCursor] ?: d.gestures.spaceCursor,
                deleteSwipe = p[deleteSwipe] ?: d.gestures.deleteSwipe,
                doubleSpacePeriod = p[doubleSpace] ?: d.gestures.doubleSpacePeriod,
                spaceLanguage = p[spaceLanguage] ?: d.gestures.spaceLanguage,
                smartSpace = p[smartSpace] ?: d.gestures.smartSpace,
            ),
            smartQuotes = p[smartQuotes] ?: d.smartQuotes,
            autoPair = p[autoPair] ?: d.autoPair,
            incognito = p[incognito] ?: d.incognito,
            learnedEncrypted = p[learnedEncrypted] ?: d.learnedEncrypted,
            clipboard = ClipboardSettings(
                history = p[clipHistory] ?: d.clipboard.history,
                retentionDays = p.int(clipRetention, Settings.RETENTION_RANGE, d.clipboard.retentionDays),
                quickPaste = p[clipQuickPaste] ?: d.clipboard.quickPaste,
                excludedApps = p[clipExcluded]?.split(',')?.map { it.trim() }?.filter { PACKAGE.matches(it) }?.toSet() ?: d.clipboard.excludedApps,
            ),
            theme = p.enum(theme, KeyboardTheme.entries, d.theme),
            keyRadiusDp = p.int(keyRadius, Settings.RADIUS_RANGE, d.keyRadiusDp),
            ai = AiSettings(enabled = p[aiEnabled] ?: d.ai.enabled),
            pcEnabled = p[pcEnabled] ?: d.pcEnabled,
            pcHost = p.enum(pcHost, PcHost.entries, d.pcHost),
            pcWide = p.enum(pcWide, PcWide.entries, d.pcWide),
            toolOrder = (p.names(toolOrder, Tool.configurable) + Tool.configurable).distinct(),
            // A tool this version adds after the list was saved keeps its default place.
            hiddenTools = if (p[hiddenTools] == null) d.hiddenTools else p.names(hiddenTools, Tool.configurable).toSet() +
                Tool.offBar.filter { it.key !in p.rawNames(toolOrder) },
            inlineAutofill = p[inlineAutofill] ?: d.inlineAutofill,
            keyStyle = KeyStyle(
                gapDp = p.int(keyGap, KeyStyle.GAP_RANGE, d.keyStyle.gapDp),
                textPercent = p.int(keyText, KeyStyle.TEXT_RANGE, d.keyStyle.textPercent),
                numberRow = p[numberRow] ?: d.keyStyle.numberRow,
                spacePercent = p.int(spaceWidth, KeyStyle.SPACE_RANGE, d.keyStyle.spacePercent),
                mirrored = p[mirrored] ?: d.keyStyle.mirrored,
                longPress = p[longPressMap]?.let(KeyStyle::parse) ?: d.keyStyle.longPress,
            ),
            floating = p[floating] ?: d.floating,
            japaneseFlick = p[japaneseFlick] ?: d.japaneseFlick,
            idioms = p[idioms] ?: d.idioms,
            quickInput = p[quickInput] ?: d.quickInput,
            quickCustom = p[quickCustom] ?: d.quickCustom,
            strokeLookup = p[strokeLookup] ?: d.strokeLookup,
        )
    }

    /**
     * Writes the settings that differ between [old] and [new] into [p].
     * Inputs: old is what [read] gives for the file as it is now.
     * Side effects: sets only the changed keys, stamps [version] (never lowering a newer one) and
     * [updated] (UTC milliseconds) when anything changed.
     * Notes: writing only changes keeps a newer version's values in settings this version reads as
     * defaults.
     */
    fun write(p: MutablePreferences, old: Settings, new: Settings, nowUtcMs: Long) {
        if (old == new) return
        p.put(defaultMode, old.defaultMode.key, new.defaultMode.key)
        if (old.modeOrder != new.modeOrder) p[modeOrder] = p.joinNames(modeOrder, new.modeOrder, Mode.entries)
        if (old.hiddenModes != new.hiddenModes) p[hiddenModes] = p.joinNames(hiddenModes, new.modeOrder.filter { it in new.hiddenModes }, Mode.entries)
        p.put(mixedLevel, old.mixed.level.key, new.mixed.level.key)
        writeCross(p, "mixed", old.mixed.cross, new.mixed.cross)
        p.put(mixedWeightChinese, old.mixed.weightChinese, new.mixed.weightChinese)
        p.put(mixedWeightEnglish, old.mixed.weightEnglish, new.mixed.weightEnglish)
        p.put(mixedWeightJapanese, old.mixed.weightJapanese, new.mixed.weightJapanese)
        p.put(mixedWeightEmoji, old.mixed.weightEmoji, new.mixed.weightEmoji)
        writeKeys(p, mixedSpace, mixedEnter, old.mixed.keys, new.mixed.keys)
        writeKeys(p, japaneseSpace, japaneseEnter, old.japaneseKeys, new.japaneseKeys)
        writeKeys(p, chineseSpace, chineseEnter, old.chineseKeys, new.chineseKeys)
        writeCross(p, "japanese", old.japanese, new.japanese)
        p.put(chineseScript, old.chineseScript.key, new.chineseScript.key)
        FuzzyRule.entries.forEach { p.put(fuzzyKey(it), it in old.chineseFuzzy, it in new.chineseFuzzy) }
        p.put(englishSuggestions, old.englishSuggestions, new.englishSuggestions)
        p.put(englishAutoCapitalize, old.englishAutoCapitalize, new.englishAutoCapitalize)
        p.put(split, old.split.key, new.split.key)
        p.put(thumbZone, old.thumbZoneDp, new.thumbZoneDp)
        p.put(tabletop, old.tabletopLowerHalf, new.tabletopLowerHalf)
        p.put(oneHanded, old.oneHanded.key, new.oneHanded.key)
        p.put(height, old.heightPercent, new.heightPercent)
        p.put(longPress, old.longPressSymbols, new.longPressSymbols)
        p.put(keyHints, old.keyHints, new.keyHints)
        p.put(haptics, old.haptics, new.haptics)
        p.put(keySound, old.keySound, new.keySound)
        p.put(spaceCursor, old.gestures.spaceCursor, new.gestures.spaceCursor)
        p.put(deleteSwipe, old.gestures.deleteSwipe, new.gestures.deleteSwipe)
        p.put(doubleSpace, old.gestures.doubleSpacePeriod, new.gestures.doubleSpacePeriod)
        p.put(spaceLanguage, old.gestures.spaceLanguage, new.gestures.spaceLanguage)
        p.put(smartSpace, old.gestures.smartSpace, new.gestures.smartSpace)
        p.put(smartQuotes, old.smartQuotes, new.smartQuotes)
        p.put(autoPair, old.autoPair, new.autoPair)
        p.put(incognito, old.incognito, new.incognito)
        p.put(learnedEncrypted, old.learnedEncrypted, new.learnedEncrypted)
        p.put(clipHistory, old.clipboard.history, new.clipboard.history)
        p.put(clipRetention, old.clipboard.retentionDays, new.clipboard.retentionDays)
        p.put(clipQuickPaste, old.clipboard.quickPaste, new.clipboard.quickPaste)
        if (old.clipboard.excludedApps != new.clipboard.excludedApps) p[clipExcluded] = new.clipboard.excludedApps.sorted().joinToString(",")
        p.put(theme, old.theme.key, new.theme.key)
        p.put(keyRadius, old.keyRadiusDp, new.keyRadiusDp)
        p.put(aiEnabled, old.ai.enabled, new.ai.enabled)
        p.put(pcEnabled, old.pcEnabled, new.pcEnabled)
        p.put(pcHost, old.pcHost.key, new.pcHost.key)
        p.put(pcWide, old.pcWide.key, new.pcWide.key)
        p.put(inlineAutofill, old.inlineAutofill, new.inlineAutofill)
        p.put(keyGap, old.keyStyle.gapDp, new.keyStyle.gapDp)
        p.put(keyText, old.keyStyle.textPercent, new.keyStyle.textPercent)
        p.put(numberRow, old.keyStyle.numberRow, new.keyStyle.numberRow)
        p.put(spaceWidth, old.keyStyle.spacePercent, new.keyStyle.spacePercent)
        p.put(mirrored, old.keyStyle.mirrored, new.keyStyle.mirrored)
        p.put(floating, old.floating, new.floating)
        p.put(japaneseFlick, old.japaneseFlick, new.japaneseFlick)
        p.put(idioms, old.idioms, new.idioms)
        p.put(quickInput, old.quickInput, new.quickInput)
        p.put(quickCustom, old.quickCustom, new.quickCustom)
        p.put(strokeLookup, old.strokeLookup, new.strokeLookup)
        if (old.keyStyle.longPress != new.keyStyle.longPress) p[longPressMap] = KeyStyle.format(new.keyStyle.longPress)
        // Both lists are written together, so a later read knows which tools were in the list.
        if (old.toolOrder != new.toolOrder || old.hiddenTools != new.hiddenTools) {
            p[toolOrder] = p.joinNames(toolOrder, new.toolOrder, Tool.configurable)
            p[hiddenTools] = p.joinNames(hiddenTools, new.toolOrder.filter { it in new.hiddenTools }, Tool.configurable)
        }
        p[version] = maxOf(p[version] ?: 0, VERSION)
        p[updated] = nowUtcMs
    }

    /** Reads one mode's cross-language switches under the key prefix [mode]. */
    private fun readCross(p: Preferences, mode: String): CrossLanguage {
        val d = CrossLanguage()
        return CrossLanguage(
            pinyinToKanji = p[crossKey(mode, "pinyin_to_kanji")] ?: d.pinyinToKanji,
            pinyinToKatakana = p[crossKey(mode, "pinyin_to_katakana")] ?: d.pinyinToKatakana,
            englishToKanji = p[crossKey(mode, "english_to_kanji")] ?: d.englishToKanji,
            englishToKatakana = p[crossKey(mode, "english_to_katakana")] ?: d.englishToKatakana,
            wholeSentence = p[crossKey(mode, "whole_sentence")] ?: d.wholeSentence,
            readings = p[crossKey(mode, "readings")] ?: d.readings,
            emoji = p[crossKey(mode, "emoji")] ?: d.emoji,
        )
    }

    /** Writes the changed cross-language switches of one mode under the key prefix [mode]. */
    private fun writeCross(p: MutablePreferences, mode: String, old: CrossLanguage, new: CrossLanguage) {
        if (old == new) return
        p.put(crossKey(mode, "pinyin_to_kanji"), old.pinyinToKanji, new.pinyinToKanji)
        p.put(crossKey(mode, "pinyin_to_katakana"), old.pinyinToKatakana, new.pinyinToKatakana)
        p.put(crossKey(mode, "english_to_kanji"), old.englishToKanji, new.englishToKanji)
        p.put(crossKey(mode, "english_to_katakana"), old.englishToKatakana, new.englishToKatakana)
        p.put(crossKey(mode, "whole_sentence"), old.wholeSentence, new.wholeSentence)
        p.put(crossKey(mode, "readings"), old.readings, new.readings)
        p.put(crossKey(mode, "emoji"), old.emoji, new.emoji)
    }

    /** Names the key of one cross-language switch, `<mode>.cross.<name>`. */
    private fun crossKey(mode: String, name: String) = booleanPreferencesKey("$mode.cross.$name")

    /** Reads one mode's space and Enter choices; a space value the mode does not offer reads as [default]. */
    private fun readKeys(p: Preferences, mode: Mode, space: Preferences.Key<String>, enter: Preferences.Key<String>, default: CompositionKeys) =
        CompositionKeys(p.enum(space, CompositionKeys.spaceChoices(mode), default.space), p.enum(enter, EnterKey.entries, default.enter))

    private fun writeKeys(p: MutablePreferences, space: Preferences.Key<String>, enter: Preferences.Key<String>, old: CompositionKeys, new: CompositionKeys) {
        p.put(space, old.space.key, new.space.key)
        p.put(enter, old.enter.key, new.enter.key)
    }

    /** Names the key of one fuzzy-pinyin rule's switch, `chinese.fuzzy.<rule>`. */
    private fun fuzzyKey(rule: FuzzyRule) = booleanPreferencesKey("chinese.fuzzy.${rule.key}")

    /** Names an enum value as stored: its lowercase constant name. */
    private val Enum<*>.key get() = name.lowercase()

    /** Reads an enum stored by [key], or [default] when missing or unknown. */
    private fun <E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, values: List<E>, default: E): E =
        this[key]?.let { raw -> values.firstOrNull { it.key == raw } } ?: default

    /** Reads a comma-separated list of [values]' names, skipping names this version does not know. */
    private fun <E : Enum<E>> Preferences.names(key: Preferences.Key<String>, values: List<E>): List<E> =
        rawNames(key).mapNotNull { raw -> values.firstOrNull { it.key == raw } }

    /** Reads a comma-separated list of names as stored. */
    private fun Preferences.rawNames(key: Preferences.Key<String>): List<String> = this[key].orEmpty().split(',').filter { it.isNotEmpty() }

    /**
     * Joins [items] for [key], keeping the names this version does not know (a newer version's
     * modes or tools) after them, so they survive a change made here.
     */
    private fun <E : Enum<E>> MutablePreferences.joinNames(key: Preferences.Key<String>, items: List<E>, values: List<E>): String {
        val unknown = rawNames(key).filter { raw -> values.none { it.key == raw } }
        return (items.map { it.key } + unknown).joinToString(",")
    }

    /** Reads an int, or [default] when missing or outside [range]. */
    private fun Preferences.int(key: Preferences.Key<Int>, range: IntRange, default: Int): Int =
        this[key]?.takeIf { it in range } ?: default

    /** Sets [key] to [new] only when it differs from [old]. */
    /** An Android package name: dot-separated Java identifiers (anything else in the stored list is dropped). */
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    private fun <T> MutablePreferences.put(key: Preferences.Key<T>, old: T, new: T) {
        if (old != new) this[key] = new
    }
}
