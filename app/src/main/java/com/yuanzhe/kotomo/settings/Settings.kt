package com.yuanzhe.kotomo.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yuanzhe.kotomo.ime.FuzzyRule
import com.yuanzhe.kotomo.ime.Mode

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

/** Chooses how strongly Mixed mode offers cross-language candidates (PLAN §2.3). */
enum class MixedLevel { CONSERVATIVE, BALANCED, AGGRESSIVE }

/** Chooses what the space key does while a Mixed-mode composition is open. */
enum class CompositionSpace { SEPARATE, COMMIT_FIRST }

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
 * weights (0–100) and the space key's behavior during composition.
 */
data class Mixed(
    val level: MixedLevel = MixedLevel.BALANCED,
    val cross: CrossLanguage = CrossLanguage(),
    val weightChinese: Int = 70,
    val weightEnglish: Int = 60,
    val weightJapanese: Int = 60,
    val weightEmoji: Int = 30,
    val space: CompositionSpace = CompositionSpace.SEPARATE,
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
    val clipboard: ClipboardSettings = ClipboardSettings(),
    val theme: KeyboardTheme = KeyboardTheme.SYSTEM,
    val keyRadiusDp: Int = 12,
    val ai: AiSettings = AiSettings(),
) {
    /**
     * The modes the language choice offers, in the person's order (PLAN §2.3).
     * Notes: never empty; with every mode hidden all of them are offered.
     */
    val modes: List<Mode> get() = modeOrder.filter { it !in hiddenModes }.ifEmpty { modeOrder }

    /**
     * Returns [wanted] when the language choice offers it, otherwise the default mode, otherwise
     * the first mode offered (a hidden mode is never used).
     */
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
    private val clipHistory = booleanPreferencesKey("clipboard.history")
    private val clipRetention = intPreferencesKey("clipboard.retention_days")
    private val clipQuickPaste = booleanPreferencesKey("clipboard.quick_paste")
    private val clipExcluded = stringPreferencesKey("clipboard.excluded_apps")
    private val theme = stringPreferencesKey("theme.choice")
    private val keyRadius = intPreferencesKey("theme.key_radius_dp")
    private val aiEnabled = booleanPreferencesKey("ai.enabled")

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
            modeOrder = (p.modes(modeOrder) + Mode.entries).distinct(),
            hiddenModes = p.modes(hiddenModes).toSet(),
            mixed = Mixed(
                level = p.enum(mixedLevel, MixedLevel.entries, d.mixed.level),
                cross = readCross(p, "mixed"),
                weightChinese = p.int(mixedWeightChinese, Settings.WEIGHT_RANGE, d.mixed.weightChinese),
                weightEnglish = p.int(mixedWeightEnglish, Settings.WEIGHT_RANGE, d.mixed.weightEnglish),
                weightJapanese = p.int(mixedWeightJapanese, Settings.WEIGHT_RANGE, d.mixed.weightJapanese),
                weightEmoji = p.int(mixedWeightEmoji, Settings.WEIGHT_RANGE, d.mixed.weightEmoji),
                space = p.enum(mixedSpace, CompositionSpace.entries, d.mixed.space),
            ),
            japanese = readCross(p, "japanese"),
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
            clipboard = ClipboardSettings(
                history = p[clipHistory] ?: d.clipboard.history,
                retentionDays = p.int(clipRetention, Settings.RETENTION_RANGE, d.clipboard.retentionDays),
                quickPaste = p[clipQuickPaste] ?: d.clipboard.quickPaste,
                excludedApps = p[clipExcluded]?.split(',')?.map { it.trim() }?.filter { PACKAGE.matches(it) }?.toSet() ?: d.clipboard.excludedApps,
            ),
            theme = p.enum(theme, KeyboardTheme.entries, d.theme),
            keyRadiusDp = p.int(keyRadius, Settings.RADIUS_RANGE, d.keyRadiusDp),
            ai = AiSettings(enabled = p[aiEnabled] ?: d.ai.enabled),
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
        if (old.modeOrder != new.modeOrder) p[modeOrder] = p.joinModes(modeOrder, new.modeOrder)
        if (old.hiddenModes != new.hiddenModes) p[hiddenModes] = p.joinModes(hiddenModes, new.modeOrder.filter { it in new.hiddenModes })
        p.put(mixedLevel, old.mixed.level.key, new.mixed.level.key)
        writeCross(p, "mixed", old.mixed.cross, new.mixed.cross)
        p.put(mixedWeightChinese, old.mixed.weightChinese, new.mixed.weightChinese)
        p.put(mixedWeightEnglish, old.mixed.weightEnglish, new.mixed.weightEnglish)
        p.put(mixedWeightJapanese, old.mixed.weightJapanese, new.mixed.weightJapanese)
        p.put(mixedWeightEmoji, old.mixed.weightEmoji, new.mixed.weightEmoji)
        p.put(mixedSpace, old.mixed.space.key, new.mixed.space.key)
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
        p.put(clipHistory, old.clipboard.history, new.clipboard.history)
        p.put(clipRetention, old.clipboard.retentionDays, new.clipboard.retentionDays)
        p.put(clipQuickPaste, old.clipboard.quickPaste, new.clipboard.quickPaste)
        if (old.clipboard.excludedApps != new.clipboard.excludedApps) p[clipExcluded] = new.clipboard.excludedApps.sorted().joinToString(",")
        p.put(theme, old.theme.key, new.theme.key)
        p.put(keyRadius, old.keyRadiusDp, new.keyRadiusDp)
        p.put(aiEnabled, old.ai.enabled, new.ai.enabled)
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

    /** Names the key of one fuzzy-pinyin rule's switch, `chinese.fuzzy.<rule>`. */
    private fun fuzzyKey(rule: FuzzyRule) = booleanPreferencesKey("chinese.fuzzy.${rule.key}")

    /** Names an enum value as stored: its lowercase constant name. */
    private val Enum<*>.key get() = name.lowercase()

    /** Reads an enum stored by [key], or [default] when missing or unknown. */
    private fun <E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, values: List<E>, default: E): E =
        this[key]?.let { raw -> values.firstOrNull { it.key == raw } } ?: default

    /** Reads a comma-separated list of mode names, skipping names this version does not know. */
    private fun Preferences.modes(key: Preferences.Key<String>): List<Mode> =
        this[key].orEmpty().split(',').mapNotNull { raw -> Mode.entries.firstOrNull { it.key == raw } }

    /**
     * Joins [modes] for [key], keeping the names this version does not know (a newer version's
     * modes) after them, so they survive a change made here.
     */
    private fun MutablePreferences.joinModes(key: Preferences.Key<String>, modes: List<Mode>): String {
        val unknown = this[key].orEmpty().split(',').filter { raw -> raw.isNotEmpty() && Mode.entries.none { it.key == raw } }
        return (modes.map { it.key } + unknown).joinToString(",")
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
