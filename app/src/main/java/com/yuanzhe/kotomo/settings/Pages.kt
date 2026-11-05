package com.yuanzhe.kotomo.settings

import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.ime.Mode
import com.yuanzhe.kotomo.ime.Probe
import com.yuanzhe.kotomo.ime.RimeFiles
import com.yuanzhe.kotomo.ime.clipboard.ClipboardHistory
import kotlin.math.roundToInt

/** Names each input mode in the settings. */
@get:StringRes
internal val Mode.label get() = when (this) {
    Mode.MIXED -> R.string.mode_mixed
    Mode.CHINESE -> R.string.mode_chinese
    Mode.JAPANESE -> R.string.mode_japanese
    Mode.ENGLISH -> R.string.mode_english
}

/** Names each Chinese script in the settings and the quick panel. */
@get:StringRes
internal val ChineseScript.label get() = when (this) {
    ChineseScript.SIMPLIFIED -> R.string.script_simplified
    ChineseScript.TRADITIONAL -> R.string.script_traditional
    ChineseScript.TAIWAN -> R.string.script_taiwan
    ChineseScript.HONG_KONG -> R.string.script_hong_kong
}

/** Names each Mixed-mode level in the settings. */
@get:StringRes
internal val MixedLevel.label get() = when (this) {
    MixedLevel.CONSERVATIVE -> R.string.level_conservative
    MixedLevel.BALANCED -> R.string.level_balanced
    MixedLevel.AGGRESSIVE -> R.string.level_aggressive
}

/** Draws the Input modes page: the mode the keyboard starts in. */
@Composable
internal fun ModesPage(settings: Settings, store: SettingsStore) {
    Section(stringResource(R.string.modes_default)) {
        Mode.entries.forEach { mode ->
            val desc = when (mode) {
                Mode.MIXED -> R.string.mode_mixed_desc
                Mode.CHINESE -> R.string.mode_chinese_desc
                Mode.JAPANESE -> R.string.mode_japanese_desc
                Mode.ENGLISH -> R.string.mode_english_desc
            }
            RadioRow(mode.label, desc, settings.defaultMode == mode) { store.set { it.copy(defaultMode = mode) } }
        }
        Inset { Note(stringResource(R.string.modes_default_note)) }
    }
}

/**
 * Draws the Mixed mode page as in SettingsMixed: the level with example orders, the language
 * weights, the cross-language switches and the space key's behavior.
 */
@Composable
internal fun MixedPage(settings: Settings, store: SettingsStore) {
    val mixed = settings.mixed
    val update = { change: (Mixed) -> Mixed -> store.set { it.copy(mixed = change(it.mixed)) } }
    Section(stringResource(R.string.mixed_level)) {
        Inset {
            Choice(MixedLevel.entries.map { stringResource(it.label) }, mixed.level.ordinal) { i -> update { it.copy(level = MixedLevel.entries[i]) } }
            Note(
                stringResource(
                    when (mixed.level) {
                        MixedLevel.CONSERVATIVE -> R.string.level_conservative_desc
                        MixedLevel.BALANCED -> R.string.level_balanced_desc
                        MixedLevel.AGGRESSIVE -> R.string.level_aggressive_desc
                    },
                ),
            )
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(dimensionResource(R.dimen.settings_preview_radius)))
                    .background(MaterialTheme.colorScheme.background).padding(dimensionResource(R.dimen.settings_preview_pad)),
                verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_preview_pad)),
            ) {
                val (diannao, ai) = when (mixed.level) {
                    MixedLevel.CONSERVATIVE -> R.array.preview_diannao_conservative to R.array.preview_ai_conservative
                    MixedLevel.BALANCED -> R.array.preview_diannao_balanced to R.array.preview_ai_balanced
                    MixedLevel.AGGRESSIVE -> R.array.preview_diannao_aggressive to R.array.preview_ai_aggressive
                }
                Preview(stringResource(R.string.preview_input_diannao), diannao)
                Preview(stringResource(R.string.preview_input_ai), ai)
            }
        }
    }
    Section(stringResource(R.string.mixed_weights)) {
        Inset {
            Weight(R.string.weight_chinese, mixed.weightChinese) { v -> update { it.copy(weightChinese = v) } }
            Weight(R.string.weight_english, mixed.weightEnglish) { v -> update { it.copy(weightEnglish = v) } }
            Weight(R.string.weight_japanese, mixed.weightJapanese) { v -> update { it.copy(weightJapanese = v) } }
            Weight(R.string.weight_emoji, mixed.weightEmoji) { v -> update { it.copy(weightEmoji = v) } }
        }
    }
    Section(stringResource(R.string.mixed_cross)) {
        CrossSwitches(mixed.cross) { cross -> update { it.copy(cross = cross(it.cross)) } }
    }
    Section(stringResource(R.string.mixed_space)) {
        RadioRow(R.string.space_separate, R.string.space_separate_desc, mixed.space == CompositionSpace.SEPARATE) {
            update { it.copy(space = CompositionSpace.SEPARATE) }
        }
        RadioRow(R.string.space_commit, R.string.space_commit_desc, mixed.space == CompositionSpace.COMMIT_FIRST) {
            update { it.copy(space = CompositionSpace.COMMIT_FIRST) }
        }
    }
}

/** Draws one example candidate order: the input, then numbered chips, the first highlighted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Preview(input: String, @ArrayRes candidates: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_chip_gap))) {
        Text(stringResource(R.string.mixed_preview_input, input), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val gap = dimensionResource(R.dimen.settings_chip_gap)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            stringArrayResource(candidates).forEachIndexed { i, text ->
                val first = i == 0
                Row(
                    Modifier.heightIn(min = dimensionResource(R.dimen.settings_chip_height)).clip(CircleShape)
                        .background(if (first) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .padding(horizontal = dimensionResource(R.dimen.settings_chip_pad)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    val ink = if (first) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    Text("${i + 1}", fontSize = dimensionResource(R.dimen.settings_chip_rank_text).value.sp, color = if (first) ink else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = ink)
                }
            }
        }
    }
}

/** Draws one candidate-weight slider (0–100 in steps of 10). */
@Composable
private fun Weight(@StringRes title: Int, value: Int, commit: (Int) -> Unit) {
    SliderRow(stringResource(title), value, Settings.WEIGHT_RANGE, WEIGHT_STEP, commit = commit)
}

/**
 * Draws the seven cross-language switches of one mode.
 * Inputs: change receives a transform of that mode's [CrossLanguage].
 */
@Composable
private fun CrossSwitches(cross: CrossLanguage, change: ((CrossLanguage) -> CrossLanguage) -> Unit) {
    SwitchRow(R.string.cross_pinyin_kanji, R.string.cross_pinyin_kanji_desc, cross.pinyinToKanji) { on -> change { it.copy(pinyinToKanji = on) } }
    SwitchRow(R.string.cross_pinyin_katakana, R.string.cross_pinyin_katakana_desc, cross.pinyinToKatakana) { on -> change { it.copy(pinyinToKatakana = on) } }
    SwitchRow(R.string.cross_english_kanji, R.string.cross_english_kanji_desc, cross.englishToKanji) { on -> change { it.copy(englishToKanji = on) } }
    SwitchRow(R.string.cross_english_katakana, R.string.cross_english_katakana_desc, cross.englishToKatakana) { on -> change { it.copy(englishToKatakana = on) } }
    SwitchRow(R.string.cross_sentence, R.string.cross_sentence_desc, cross.wholeSentence) { on -> change { it.copy(wholeSentence = on) } }
    SwitchRow(R.string.cross_readings, R.string.cross_readings_desc, cross.readings) { on -> change { it.copy(readings = on) } }
    SwitchRow(R.string.cross_emoji, R.string.cross_emoji_desc, cross.emoji) { on -> change { it.copy(emoji = on) } }
}

/** Draws the Chinese page: Mainland Simplified, Traditional, Taiwan or Hong Kong output. */
@Composable
internal fun ChinesePage(settings: Settings, store: SettingsStore) {
    Section(stringResource(R.string.chinese_script)) {
        Inset {
            val labels = ChineseScript.entries.map { stringResource(it.label) }
            Choice(labels, settings.chineseScript.ordinal) { i -> store.set { it.copy(chineseScript = ChineseScript.entries[i]) } }
            Note(stringResource(R.string.chinese_script_note))
        }
    }
}

/** Draws the Japanese page: Japanese mode's own cross-language switches. */
@Composable
internal fun JapanesePage(settings: Settings, store: SettingsStore) {
    Section(stringResource(R.string.japanese_cross)) {
        Inset { Note(stringResource(R.string.japanese_cross_note)) }
        CrossSwitches(settings.japanese) { change -> store.set { it.copy(japanese = change(it.japanese)) } }
    }
}

/** Draws the English page: suggestions and auto-capitalization. */
@Composable
internal fun EnglishPage(settings: Settings, store: SettingsStore) {
    Section(null) {
        SwitchRow(R.string.english_suggestions, R.string.english_suggestions_desc, settings.englishSuggestions) { on -> store.set { it.copy(englishSuggestions = on) } }
        SwitchRow(R.string.english_autocap, R.string.english_autocap_desc, settings.englishAutoCapitalize) { on -> store.set { it.copy(englishAutoCapitalize = on) } }
    }
}

/**
 * Draws the Layout and size page as in SettingsTablet: large-screen and foldable settings beside
 * the phone settings, stacked when the page is narrower than `settings_columns_min`.
 */
@Composable
internal fun LayoutPage(settings: Settings, store: SettingsStore) {
    val large: @Composable ColumnScope.() -> Unit = {
        Inset {
            Label(stringResource(R.string.split_title))
            val labels = listOf(stringResource(R.string.split_auto), stringResource(R.string.split_always), stringResource(R.string.split_never))
            Choice(labels, settings.split.ordinal) { i -> store.set { it.copy(split = SplitLayout.entries[i]) } }
            SliderRow(
                stringResource(R.string.thumb_title), settings.thumbZoneDp, Settings.THUMB_RANGE, THUMB_STEP,
                label = { stringResource(R.string.value_dp, it) }, note = stringResource(R.string.thumb_note),
            ) { v -> store.set { it.copy(thumbZoneDp = v) } }
        }
        SwitchRow(R.string.tabletop_title, R.string.tabletop_desc, settings.tabletopLowerHalf) { on -> store.set { it.copy(tabletopLowerHalf = on) } }
    }
    val phone: @Composable ColumnScope.() -> Unit = {
        Inset {
            Label(stringResource(R.string.one_handed_title))
            val labels = listOf(stringResource(R.string.one_handed_off), stringResource(R.string.one_handed_left), stringResource(R.string.one_handed_right))
            Choice(labels, settings.oneHanded.ordinal) { i -> store.set { it.copy(oneHanded = OneHanded.entries[i]) } }
            SliderRow(
                stringResource(R.string.height_title), settings.heightPercent, Settings.HEIGHT_RANGE, HEIGHT_STEP,
                label = { stringResource(R.string.value_percent, it) },
            ) { v -> store.set { it.copy(heightPercent = v) } }
        }
    }
    BoxWithConstraints {
        if (maxWidth >= dimensionResource(R.dimen.settings_columns_min)) {
            Row(horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_gutter))) {
                Section(stringResource(R.string.layout_large), Modifier.weight(1f), large)
                Section(stringResource(R.string.layout_phone), Modifier.weight(1f), phone)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_section_gap))) {
                Section(stringResource(R.string.layout_phone), content = phone)
                Section(stringResource(R.string.layout_large), content = large)
            }
        }
    }
}

/**
 * Draws the Keys and gestures page: long-press symbols, corner hints, vibration and sound, and a
 * switch for every gesture (PLAN §8.1).
 */
@Composable
internal fun KeysPage(settings: Settings, store: SettingsStore) {
    Section(null) {
        SwitchRow(R.string.keys_long_press, R.string.keys_long_press_desc, settings.longPressSymbols) { on -> store.set { it.copy(longPressSymbols = on) } }
        SwitchRow(R.string.keys_hints, R.string.keys_hints_desc, settings.keyHints) { on -> store.set { it.copy(keyHints = on) } }
        SwitchRow(R.string.keys_haptics, R.string.keys_haptics_desc, settings.haptics) { on -> store.set { it.copy(haptics = on) } }
        SwitchRow(R.string.keys_sound, R.string.keys_sound_desc, settings.keySound) { on -> store.set { it.copy(keySound = on) } }
    }
    val g = settings.gestures
    fun gesture(change: (Gestures) -> Gestures) = store.set { it.copy(gestures = change(it.gestures)) }
    Section(stringResource(R.string.gestures_section)) {
        SwitchRow(R.string.gesture_space_cursor, R.string.gesture_space_cursor_desc, g.spaceCursor) { on -> gesture { it.copy(spaceCursor = on) } }
        SwitchRow(R.string.gesture_delete_swipe, R.string.gesture_delete_swipe_desc, g.deleteSwipe) { on -> gesture { it.copy(deleteSwipe = on) } }
        SwitchRow(R.string.gesture_double_space, R.string.gesture_double_space_desc, g.doubleSpacePeriod) { on -> gesture { it.copy(doubleSpacePeriod = on) } }
        SwitchRow(R.string.gesture_space_language, R.string.gesture_space_language_desc, g.spaceLanguage) { on -> gesture { it.copy(spaceLanguage = on) } }
        SwitchRow(R.string.gesture_smart_space, R.string.gesture_smart_space_desc, g.smartSpace) { on -> gesture { it.copy(smartSpace = on) } }
    }
}

/**
 * Draws the Themes page: the keyboard's colors (system, light, dark, Material You on Android 12
 * and later) and the key corner radius. The keyboard repaints as soon as one changes.
 */
@Composable
internal fun ThemesPage(settings: Settings, store: SettingsStore) {
    Section(null) {
        Inset {
            Label(stringResource(R.string.theme_title))
            val options = buildList {
                add(KeyboardTheme.SYSTEM to stringResource(R.string.theme_system))
                add(KeyboardTheme.LIGHT to stringResource(R.string.theme_light))
                add(KeyboardTheme.DARK to stringResource(R.string.theme_dark))
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    add(KeyboardTheme.DYNAMIC to stringResource(R.string.theme_dynamic))
                }
            }
            Choice(options.map { it.second }, options.indexOfFirst { it.first == settings.theme }.coerceAtLeast(0)) { i ->
                store.set { it.copy(theme = options[i].first) }
            }
            SliderRow(
                stringResource(R.string.theme_radius), settings.keyRadiusDp, Settings.RADIUS_RANGE, RADIUS_STEP,
                label = { stringResource(R.string.value_dp, it) },
            ) { v -> store.set { it.copy(keyRadiusDp = v) } }
        }
    }
}

/**
 * Draws the Privacy page: the incognito switch, the clipboard settings (history, how long it
 * keeps unpinned items, quick paste, clearing) and what Kotomo keeps and sends.
 */
@Composable
internal fun PrivacyPage(settings: Settings, store: SettingsStore) {
    val context = LocalContext.current
    val clip = settings.clipboard
    Section(null) {
        SwitchRow(R.string.incognito_title, R.string.incognito_desc, settings.incognito) { on -> store.set { it.copy(incognito = on) } }
    }
    Section(stringResource(R.string.clip_settings_section)) {
        SwitchRow(R.string.clip_settings_history, R.string.clip_settings_history_desc, clip.history) { on ->
            store.set { it.copy(clipboard = it.clipboard.copy(history = on)) }
        }
        Inset {
            Label(stringResource(R.string.clip_settings_keep))
            Choice(RETENTION_CHOICES.map { pluralStringResource(R.plurals.clip_settings_days, it, it) }, RETENTION_CHOICES.indexOf(clip.retentionDays)) { i ->
                store.set { it.copy(clipboard = it.clipboard.copy(retentionDays = RETENTION_CHOICES[i])) }
            }
            OutlinedButton(onClick = { ClipboardHistory.get(context).clearUnpinned() }) { Text(stringResource(R.string.clip_settings_clear)) }
        }
        SwitchRow(R.string.clip_settings_quick, R.string.clip_settings_quick_desc, clip.quickPaste) { on ->
            store.set { it.copy(clipboard = it.clipboard.copy(quickPaste = on)) }
        }
    }
    Section(null) {
        Inset {
            listOf(R.string.privacy_offline, R.string.privacy_local, R.string.privacy_sensitive, R.string.privacy_diagnostics).forEach {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Draws the AI page: the switch that lets the keyboard's AI button work (off by default) and what
 * it does and never does.
 */
@Composable
internal fun AiPage(settings: Settings, store: SettingsStore) {
    Section(null) {
        SwitchRow(R.string.ai_enable, R.string.ai_enable_desc, settings.ai.enabled) { on -> store.set { it.copy(ai = it.ai.copy(enabled = on)) } }
    }
    Section(null) {
        Inset {
            listOf(R.string.ai_note_how, R.string.ai_note_device, R.string.ai_note_fields, R.string.ai_note_languages).forEach {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The clipboard retentions the Privacy page offers, in days. */
private val RETENTION_CHOICES = listOf(1, 7, 30)

/** Draws a page that only explains what arrives later. */
@Composable
internal fun NotePage(@StringRes text: Int) {
    Section(null) { Inset { Text(stringResource(text), style = MaterialTheme.typography.bodyMedium) } }
}

/**
 * Draws spike S1's diagnostics: the device, the stored latency and memory summary, the Chinese
 * engine's setup and self-check, with Refresh and Clear.
 * Side effects: Clear deletes the stored measurements.
 * Notes: re-read whenever the screen resumes, since the keyboard records them in another window.
 */
@Composable
internal fun DiagnosticsPage() {
    val context = LocalContext.current
    var summary by remember { mutableStateOf(Probe.load(context)) }
    LifecycleResumeEffect(Unit) {
        summary = Probe.load(context)
        onPauseOrDispose {}
    }
    Section(null) {
        Inset {
            Text(stringResource(R.string.diag_note), style = MaterialTheme.typography.bodySmall)
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            Text(
                stringResource(
                    R.string.diag_device, Build.MODEL, Build.VERSION.RELEASE,
                    stringResource(if (debuggable) R.string.build_debug else R.string.build_release),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Measure(stringResource(R.string.diag_key), summary.keyDraw?.let { stringResource(R.string.diag_series, it.p50, it.p95, it.max, it.count) })
            Measure(stringResource(R.string.diag_candidates), summary.candidateDraw?.let { stringResource(R.string.diag_series, it.p50, it.p95, it.max, it.count) })
            Measure(stringResource(R.string.diag_cold), summary.coldStartMs?.let { stringResource(R.string.diag_ms, it.toInt()) })
            Measure(stringResource(R.string.diag_compose), summary.composeFirstFrameMs?.let { stringResource(R.string.diag_ms, it.toInt()) })
            RimeFiles(context).readCheck()?.let { Measure(stringResource(R.string.diag_rime_check), it) }
            val rimeStatus = RimeFiles(context).readStatus()
            Measure(
                stringResource(R.string.diag_rime),
                when (rimeStatus) {
                    RimeFiles.Status.Running -> stringResource(R.string.diag_rime_running)
                    RimeFiles.Status.Prebuilt -> stringResource(R.string.diag_rime_prebuilt, summary.rimeDeployMs?.toInt() ?: 0)
                    is RimeFiles.Status.Failed -> stringResource(R.string.diag_rime_failed, rimeStatus.reason)
                    is RimeFiles.Status.Partial -> stringResource(R.string.diag_rime_partial, summary.rimeDeployMs?.toInt() ?: 0, rimeStatus.tasks)
                    else -> summary.rimeDeployMs?.let { stringResource(R.string.diag_ms_mb, it.toInt(), (summary.rimeDeployPeakKb ?: 0) / 1024.0) }
                },
            )
            Measure(stringResource(R.string.diag_memory), summary.pssKb?.let { stringResource(R.string.diag_mb, it / 1024.0) })
            Row(horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_group_gap))) {
                OutlinedButton(onClick = { summary = Probe.load(context) }) { Text(stringResource(R.string.diag_refresh)) }
                OutlinedButton(onClick = {
                    Probe.clear(context)
                    summary = Probe.load(context)
                }) { Text(stringResource(R.string.diag_clear)) }
            }
        }
    }
}

/** Draws one diagnostics measurement; a long value (an error report) goes under its label. */
@Composable
private fun Measure(label: String, value: String?) {
    val shown = value ?: stringResource(R.string.diag_none)
    if (shown.length > LONG_VALUE) {
        Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_chip_gap))) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(shown, style = MaterialTheme.typography.bodySmall)
        }
    } else {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(shown, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Draws a home group: its title above a rounded card of rows, as in SettingsHome.
 */
@Composable
internal fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_group_gap))) {
        Text(
            title, Modifier.padding(horizontal = dimensionResource(R.dimen.settings_group_title_pad)),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(dimensionResource(R.dimen.settings_card_radius))).background(MaterialTheme.colorScheme.surface), content = content)
    }
}

/**
 * Draws a page section: a rounded card with its title inside, as in SettingsMixed.
 * Notes: rows (switches, radio buttons) span the card; anything else goes in an [Inset].
 */
@Composable
internal fun Section(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val pad = dimensionResource(R.dimen.settings_card_pad)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(dimensionResource(R.dimen.settings_card_radius)))
            .background(MaterialTheme.colorScheme.surface).padding(vertical = pad / 2),
    ) {
        if (title != null) {
            Text(
                title, Modifier.padding(horizontal = pad, vertical = pad / 2),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
            )
        }
        content()
    }
}

/** Pads non-row content of a [Section] to the card's inner edges. */
@Composable
internal fun Inset(content: @Composable ColumnScope.() -> Unit) {
    val pad = dimensionResource(R.dimen.settings_card_pad)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = pad, vertical = pad / 2),
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_card_gap)),
        content = content,
    )
}

/** Draws a row's round badge with one glyph. */
@Composable
internal fun Badge(text: String) {
    Row(
        Modifier.size(dimensionResource(R.dimen.settings_badge)).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = dimensionResource(R.dimen.settings_badge_text).value.sp)
    }
}

/** Draws the thin line between rows of a card. */
@Composable
internal fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Draws a switch row: title, description and a switch; the whole row toggles. */
@Composable
internal fun SwitchRow(@StringRes title: Int, @StringRes desc: Int, checked: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = dimensionResource(R.dimen.settings_row_min_small))
            .toggleable(checked, role = Role.Switch, onValueChange = change)
            .padding(horizontal = dimensionResource(R.dimen.settings_card_pad), vertical = dimensionResource(R.dimen.settings_row_pad)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_row_gap)),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}

/** Draws a radio row: a radio button, title and description; the whole row selects. */
@Composable
private fun RadioRow(@StringRes title: Int, @StringRes desc: Int, selected: Boolean, select: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = dimensionResource(R.dimen.settings_row_min_small))
            .selectable(selected, role = Role.RadioButton, onClick = select)
            .padding(horizontal = dimensionResource(R.dimen.settings_card_pad), vertical = dimensionResource(R.dimen.settings_row_pad)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_row_gap)),
    ) {
        RadioButton(selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Draws a single-choice segmented control over [labels]. */
@Composable
private fun Choice(labels: List<String>, selected: Int, select: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected, onClick = { select(i) },
                shape = SegmentedButtonDefaults.itemShape(i, labels.size), label = { Text(label, maxLines = 2) },
            )
        }
    }
}

/**
 * Draws a slider with its title and current value.
 * Inputs: step is the distance between allowed values; commit receives the value when the drag
 * ends, so a drag writes the settings file once.
 */
@Composable
private fun SliderRow(
    title: String, value: Int, range: IntRange, step: Int,
    label: @Composable (Int) -> String = { it.toString() }, note: String? = null, commit: (Int) -> Unit,
) {
    var dragged by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(label(dragged.roundToInt()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            dragged, { dragged = it },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
            onValueChangeFinished = { commit(dragged.roundToInt()) },
        )
        if (note != null) Note(note)
    }
}

/** Draws a control's label inside a section. */
@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

/** Draws secondary explanatory text. */
@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A diagnostics value longer than this goes under its label. */
private const val LONG_VALUE = 40
private const val WEIGHT_STEP = 10
private const val THUMB_STEP = 4
private const val HEIGHT_STEP = 5
private const val RADIUS_STEP = 2
