package com.yuanzhe.kotomo.settings

import android.content.pm.ApplicationInfo
import android.content.res.Configuration
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.focus.onFocusChanged
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.ime.FuzzyRule
import com.yuanzhe.kotomo.ime.KeyboardMemory
import com.yuanzhe.kotomo.ime.OneHandedMemory
import com.yuanzhe.kotomo.ime.Posture
import com.yuanzhe.kotomo.ime.LearningData
import com.yuanzhe.kotomo.ime.Mode
import com.yuanzhe.kotomo.ime.Tool
import com.yuanzhe.kotomo.ime.QuickInput
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
    Mode.PC -> R.string.mode_pc
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

/**
 * Draws the Input modes page (PLAN §2.3): the mode the keyboard starts in; the language choice's
 * order, with a switch per mode to show or hide it (the PC layout's switch is [Settings.pcEnabled]);
 * the PC layout's host system and wide-screen layout (PLAN §10.11); and the apps fixed to a mode,
 * each with Remove.
 * Notes: the last shown mode cannot be hidden. Apps are named by their label when Android lets
 * Kotomo see it, otherwise by package name.
 */
@Composable
internal fun ModesPage(settings: Settings, store: SettingsStore) {
    val context = LocalContext.current
    Section(stringResource(R.string.modes_default)) {
        // The PC layout types to other computers; it is never where the keyboard starts.
        Mode.entries.filter { it != Mode.PC }.forEach { mode ->
            val desc = when (mode) {
                Mode.MIXED -> R.string.mode_mixed_desc
                Mode.CHINESE -> R.string.mode_chinese_desc
                Mode.JAPANESE -> R.string.mode_japanese_desc
                Mode.ENGLISH -> R.string.mode_english_desc
                Mode.PC -> R.string.mode_pc_desc
            }
            RadioRow(mode.label, desc, settings.defaultMode == mode) { store.set { it.copy(defaultMode = mode) } }
        }
        Inset {
            Note(stringResource(R.string.modes_default_note))
            if (settings.defaultMode !in settings.modes) Note(stringResource(R.string.modes_default_hidden))
        }
    }
    Section(stringResource(R.string.modes_order)) {
        val order = settings.modeOrder
        order.forEachIndexed { i, mode ->
            OrderRow(
                stringResource(mode.label),
                shown = mode !in settings.hiddenModes && (mode != Mode.PC || settings.pcEnabled),
                canHide = mode in settings.hiddenModes || settings.modes.size > 1,
                onMove = { delta ->
                    store.set { s ->
                        val list = s.modeOrder.toMutableList()
                        val at = list.indexOf(mode)
                        val to = (at + delta).coerceIn(0, list.lastIndex)
                        list.add(to, list.removeAt(at))
                        s.copy(modeOrder = list)
                    }
                },
                first = i == 0,
                last = i == order.lastIndex,
            ) { show ->
                store.set { s ->
                    val hidden = if (show) s.hiddenModes - mode else s.hiddenModes + mode
                    // The PC layout has its own switch, off until the person turns it on (PLAN §10.11).
                    if (mode == Mode.PC) s.copy(hiddenModes = hidden, pcEnabled = show) else s.copy(hiddenModes = hidden)
                }
            }
        }
        Inset { Note(stringResource(R.string.modes_order_note)) }
    }
    Section(stringResource(R.string.pc_section)) {
        Inset { Label(stringResource(R.string.pc_host)) }
        RadioRow(R.string.pc_host_windows, R.string.pc_host_windows_desc, settings.pcHost == PcHost.WINDOWS) { store.set { it.copy(pcHost = PcHost.WINDOWS) } }
        RadioRow(R.string.pc_host_macos, R.string.pc_host_macos_desc, settings.pcHost == PcHost.MACOS) { store.set { it.copy(pcHost = PcHost.MACOS) } }
        RadioRow(R.string.pc_host_linux, R.string.pc_host_linux_desc, settings.pcHost == PcHost.LINUX) { store.set { it.copy(pcHost = PcHost.LINUX) } }
        Inset { Label(stringResource(R.string.pc_wide)) }
        RadioRow(R.string.pc_wide_full, R.string.pc_wide_full_desc, settings.pcWide == PcWide.FULL) { store.set { it.copy(pcWide = PcWide.FULL) } }
        RadioRow(R.string.pc_wide_split, R.string.pc_wide_split_desc, settings.pcWide == PcWide.SPLIT) { store.set { it.copy(pcWide = PcWide.SPLIT) } }
        Inset { Note(stringResource(R.string.pc_note)) }
    }
    val memory = remember { KeyboardMemory.get(context) }
    val apps by memory.flow.collectAsState()
    // Every app with its own settings (PLAN §2.3, §10.10): a fixed mode, no learning, code.
    val pinned = apps.apps.filterValues { it.custom }.toSortedMap().toList()
    Section(stringResource(R.string.modes_pinned)) {
        pinned.forEach { (app, m) ->
            val what = listOfNotNull(
                m.pinned?.let { stringResource(it.label) },
                stringResource(R.string.app_setting_incognito).takeIf { m.incognito },
                stringResource(R.string.app_setting_code).takeIf { m.code },
            ).joinToString(stringResource(R.string.app_setting_separator))
            Row(
                Modifier.fillMaxWidth().heightIn(min = dimensionResource(R.dimen.settings_row_min_small))
                    .padding(horizontal = dimensionResource(R.dimen.settings_card_pad), vertical = dimensionResource(R.dimen.settings_row_pad)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_row_gap)),
            ) {
                Text(stringResource(R.string.modes_pinned_row, appLabel(context, app), what), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = { memory.update { mem -> mem.withApp(app) { it.copy(pinned = null, incognito = false, code = false) } } }) {
                    Text(stringResource(R.string.modes_unpin))
                }
            }
        }
        Inset {
            if (pinned.isEmpty()) Note(stringResource(R.string.modes_pinned_none))
            Note(stringResource(R.string.modes_remembered_note))
        }
    }
}

/** Names an app by its label, or by [app] (the package name) when Android does not show it to Kotomo. */
private fun appLabel(context: android.content.Context, app: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(app, 0)).toString()
}.getOrDefault(app)

/**
 * Draws one item of an ordered list (a mode of the language choice, a toolbar tool): its name, Up
 * and Down, and a switch that shows or hides it.
 * Inputs: [onMove] receives −1 or +1; [canHide] false keeps the last shown item on.
 */
@Composable
private fun OrderRow(
    label: String, shown: Boolean, canHide: Boolean, onMove: (Int) -> Unit, first: Boolean, last: Boolean, onShow: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = dimensionResource(R.dimen.settings_row_min_small))
            .padding(horizontal = dimensionResource(R.dimen.settings_card_pad), vertical = dimensionResource(R.dimen.settings_row_pad)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_row_gap)),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { onMove(-1) }, enabled = !first) {
            Icon(painterResource(R.drawable.ic_bar_collapse), stringResource(R.string.modes_up, label))
        }
        IconButton(onClick = { onMove(1) }, enabled = !last) {
            Icon(painterResource(R.drawable.ic_bar_expand), stringResource(R.string.modes_down, label))
        }
        val desc = stringResource(R.string.modes_shown, label)
        Switch(shown, onCheckedChange = onShow, enabled = canHide || !shown, modifier = Modifier.semantics { contentDescription = desc })
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
    KeysSections(Mode.MIXED, mixed.keys) { keys -> update { it.copy(keys = keys) } }
}

/**
 * Draws one mode's two choices for composition: what the space key does and what Enter commits
 * (ADR-010; `<mode>.space`, `<mode>.enter`).
 */
@Composable
private fun KeysSections(mode: Mode, keys: CompositionKeys, change: (CompositionKeys) -> Unit) {
    Section(stringResource(R.string.mixed_space)) {
        CompositionKeys.spaceChoices(mode).forEach { choice ->
            val (title, desc) = when (choice) {
                SpaceKey.SEPARATE -> R.string.space_separate to R.string.space_separate_desc
                SpaceKey.COMMIT_FIRST -> R.string.space_commit to R.string.space_commit_desc
                SpaceKey.NEXT -> R.string.space_next to R.string.space_next_desc
            }
            RadioRow(title, desc, keys.space == choice) { change(keys.copy(space = choice)) }
        }
    }
    Section(stringResource(R.string.composition_enter)) {
        RadioRow(R.string.enter_shown, if (mode == Mode.JAPANESE) R.string.enter_shown_kana_desc else R.string.enter_shown_desc, keys.enter == EnterKey.SHOWN) {
            change(keys.copy(enter = EnterKey.SHOWN))
        }
        RadioRow(R.string.enter_first, R.string.enter_first_desc, keys.enter == EnterKey.FIRST) { change(keys.copy(enter = EnterKey.FIRST)) }
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

/**
 * Draws the Chinese page: Mainland Simplified, Traditional, Taiwan or Hong Kong output, and one
 * switch per fuzzy-pinyin rule (PLAN §9.6), initials and finals in their own sections, then the
 * space and Enter keys during composition.
 */
@Composable
internal fun ChinesePage(settings: Settings, store: SettingsStore) {
    Section(stringResource(R.string.chinese_script)) {
        Inset {
            val labels = ChineseScript.entries.map { stringResource(it.label) }
            Choice(labels, settings.chineseScript.ordinal) { i -> store.set { it.copy(chineseScript = ChineseScript.entries[i]) } }
            Note(stringResource(R.string.chinese_script_note))
        }
    }
    Section(stringResource(R.string.double_pinyin)) {
        Inset {
            val options = listOf<com.yuanzhe.kotomo.ime.DoublePinyin?>(null) + com.yuanzhe.kotomo.ime.DoublePinyin.entries
            val labels = listOf(R.string.double_full, R.string.double_microsoft, R.string.double_sogou, R.string.double_xiaohe, R.string.double_ziranma).map { stringResource(it) }
            Choice(labels, options.indexOf(settings.doublePinyin)) { i -> store.set { it.copy(doublePinyin = options[i]) } }
            Note(stringResource(R.string.double_pinyin_note))
        }
    }
    Section(null) {
        SwitchRow(R.string.chinese_idioms, R.string.chinese_idioms_desc, settings.idioms) { on -> store.set { it.copy(idioms = on) } }
        SwitchRow(R.string.chinese_strokes, R.string.chinese_strokes_desc, settings.strokeLookup) { on -> store.set { it.copy(strokeLookup = on) } }
        SwitchRow(R.string.chinese_quick, R.string.chinese_quick_desc, settings.quickInput) { on -> store.set { it.copy(quickInput = on) } }
        Inset {
            var text by remember(settings.quickCustom) { mutableStateOf(settings.quickCustom) }
            val apply = { store.set { it.copy(quickCustom = QuickInput.formatCustom(QuickInput.parseCustom(text))) } }
            androidx.compose.material3.OutlinedTextField(
                text, { text = it },
                Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) apply() },
                label = { Text(stringResource(R.string.chinese_quick_custom)) },
                placeholder = { Text(stringResource(R.string.chinese_quick_custom_example)) },
                minLines = 2, maxLines = 6,
            )
            Note(stringResource(R.string.chinese_quick_note))
        }
    }
    val (initials, finals) = FuzzyRule.entries.partition { it.ordinal <= FuzzyRule.L_R.ordinal }
    Section(stringResource(R.string.fuzzy_initials)) {
        Inset { Note(stringResource(R.string.fuzzy_note)) }
        FuzzySwitches(initials, settings, store)
    }
    Section(stringResource(R.string.fuzzy_finals)) {
        FuzzySwitches(finals, settings, store)
    }
    KeysSections(Mode.CHINESE, settings.chineseKeys) { keys -> store.set { it.copy(chineseKeys = keys) } }
}

/** Draws the switches of [rules]; each turns one fuzzy-pinyin rule on or off on its own. */
@Composable
private fun FuzzySwitches(rules: List<FuzzyRule>, settings: Settings, store: SettingsStore) {
    rules.forEach { rule ->
        val (title, desc) = rule.labels
        SwitchRow(title, desc, rule in settings.chineseFuzzy) { on ->
            store.set { it.copy(chineseFuzzy = if (on) it.chineseFuzzy + rule else it.chineseFuzzy - rule) }
        }
    }
}

/** Names a fuzzy-pinyin rule and gives its example, as string resources. */
private val FuzzyRule.labels get() = when (this) {
    FuzzyRule.Z_ZH -> R.string.fuzzy_z_zh to R.string.fuzzy_z_zh_desc
    FuzzyRule.C_CH -> R.string.fuzzy_c_ch to R.string.fuzzy_c_ch_desc
    FuzzyRule.S_SH -> R.string.fuzzy_s_sh to R.string.fuzzy_s_sh_desc
    FuzzyRule.N_L -> R.string.fuzzy_n_l to R.string.fuzzy_n_l_desc
    FuzzyRule.F_H -> R.string.fuzzy_f_h to R.string.fuzzy_f_h_desc
    FuzzyRule.L_R -> R.string.fuzzy_l_r to R.string.fuzzy_l_r_desc
    FuzzyRule.AN_ANG -> R.string.fuzzy_an_ang to R.string.fuzzy_an_ang_desc
    FuzzyRule.EN_ENG -> R.string.fuzzy_en_eng to R.string.fuzzy_en_eng_desc
    FuzzyRule.IN_ING -> R.string.fuzzy_in_ing to R.string.fuzzy_in_ing_desc
    FuzzyRule.IAN_IANG -> R.string.fuzzy_ian_iang to R.string.fuzzy_ian_iang_desc
    FuzzyRule.UAN_UANG -> R.string.fuzzy_uan_uang to R.string.fuzzy_uan_uang_desc
}

/** Draws the Japanese page: Japanese mode's own cross-language switches and its space and Enter keys. */
@Composable
internal fun JapanesePage(settings: Settings, store: SettingsStore) {
    Section(null) {
        SwitchRow(R.string.japanese_flick, R.string.japanese_flick_desc, settings.japaneseFlick) { on -> store.set { it.copy(japaneseFlick = on) } }
    }
    Section(stringResource(R.string.japanese_cross)) {
        Inset { Note(stringResource(R.string.japanese_cross_note)) }
        CrossSwitches(settings.japanese) { change -> store.set { it.copy(japanese = change(it.japanese)) } }
    }
    KeysSections(Mode.JAPANESE, settings.japaneseKeys) { keys -> store.set { it.copy(japaneseKeys = keys) } }
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
 * the phone settings, stacked when the page is narrower than `settings_columns_min`. One-handed
 * mode (side and width) is the one of the screen and orientation the page is shown on (PLAN §8.10);
 * then the toolbar's tools (PLAN §10.10).
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
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val memory = remember { KeyboardMemory.get(context) }
    val remembered by memory.flow.collectAsState()
    // One-handed mode is kept per screen and orientation: this page changes the one it is shown on.
    val screen = Posture.screenKey(config.smallestScreenWidthDp, config.orientation == Configuration.ORIENTATION_LANDSCAPE)
    val hand = remembered.oneHandedOn(screen, settings.oneHanded)
    fun changeHand(change: (OneHandedMemory) -> OneHandedMemory) = memory.update { it.withOneHanded(screen, settings.oneHanded, change) }
    val phone: @Composable ColumnScope.() -> Unit = {
        Inset {
            Label(stringResource(R.string.one_handed_title))
            val labels = listOf(stringResource(R.string.one_handed_off), stringResource(R.string.one_handed_left), stringResource(R.string.one_handed_right))
            Choice(labels, hand.side.ordinal) { i -> changeHand { it.copy(side = OneHanded.entries[i]) } }
            SliderRow(
                stringResource(R.string.one_handed_width), hand.widthPercent, OneHandedMemory.WIDTH_RANGE, ONE_HANDED_STEP,
                label = { stringResource(R.string.value_percent, it) }, note = stringResource(R.string.one_handed_note),
            ) { v -> changeHand { it.copy(widthPercent = v) } }
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
    ToolbarSection(settings, store)
}

/**
 * Draws the toolbar's tools in the person's order, each with Up, Down and a switch that puts it on
 * the bar or under More (PLAN §10.10).
 */
@Composable
private fun ToolbarSection(settings: Settings, store: SettingsStore) {
    Section(stringResource(R.string.toolbar_section)) {
        val order = settings.toolOrder
        order.forEachIndexed { i, tool ->
            OrderRow(
                stringResource(tool.label),
                shown = tool !in settings.hiddenTools,
                canHide = true,
                onMove = { delta ->
                    store.set { s ->
                        val list = s.toolOrder.toMutableList()
                        val at = list.indexOf(tool)
                        list.add((at + delta).coerceIn(0, list.lastIndex), list.removeAt(at))
                        s.copy(toolOrder = list)
                    }
                },
                first = i == 0,
                last = i == order.lastIndex,
            ) { show -> store.set { s -> s.copy(hiddenTools = if (show) s.hiddenTools - tool else s.hiddenTools + tool) } }
        }
        Inset { Note(stringResource(R.string.toolbar_note)) }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            SwitchRow(R.string.toolbar_autofill, R.string.toolbar_autofill_desc, settings.inlineAutofill) { on -> store.set { it.copy(inlineAutofill = on) } }
        }
    }
}

/**
 * Draws the Keys and gestures page: long-press symbols, corner hints, vibration and sound, a
 * switch for every gesture (PLAN §8.1) and the smart punctuation switches (PLAN §10.8).
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
    KeyStyleSection(settings.keyStyle, settings.floating, { on -> store.set { it.copy(floating = on) } }) { change -> store.set { it.copy(keyStyle = change(it.keyStyle)) } }
    Section(stringResource(R.string.punct_section)) {
        SwitchRow(R.string.punct_smart_quotes, R.string.punct_smart_quotes_desc, settings.smartQuotes) { on -> store.set { it.copy(smartQuotes = on) } }
        SwitchRow(R.string.punct_auto_pair, R.string.punct_auto_pair_desc, settings.autoPair) { on -> store.set { it.copy(autoPair = on) } }
    }
}

/**
 * Draws the key and row customizations (PLAN §10.9): the gap, the label size and the space width
 * as sliders, the floating keyboard, the number row and mirrored function keys as switches, and the person's long-press
 * symbols as text (one `letter=symbols` per line, applied when the field loses focus or Done).
 */
@Composable
private fun KeyStyleSection(style: KeyStyle, floating: Boolean, setFloating: (Boolean) -> Unit, change: ((KeyStyle) -> KeyStyle) -> Unit) {
    Section(stringResource(R.string.keystyle_section)) {
        Inset {
            SliderRow(stringResource(R.string.keystyle_gap), style.gapDp, KeyStyle.GAP_RANGE, 1, label = { stringResource(R.string.value_dp, it) }) { v ->
                change { it.copy(gapDp = v) }
            }
            SliderRow(stringResource(R.string.keystyle_text), style.textPercent, KeyStyle.TEXT_RANGE, 10, label = { stringResource(R.string.value_percent, it) }) { v ->
                change { it.copy(textPercent = v) }
            }
            SliderRow(stringResource(R.string.keystyle_space), style.spacePercent, KeyStyle.SPACE_RANGE, 10, label = { stringResource(R.string.value_percent, it) }) { v ->
                change { it.copy(spacePercent = v) }
            }
        }
        SwitchRow(R.string.tool_floating, R.string.keystyle_floating_desc, floating) { on -> setFloating(on) }
        SwitchRow(R.string.keystyle_number_row, R.string.keystyle_number_row_desc, style.numberRow) { on -> change { it.copy(numberRow = on) } }
        SwitchRow(R.string.keystyle_mirrored, R.string.keystyle_mirrored_desc, style.mirrored) { on -> change { it.copy(mirrored = on) } }
        Inset {
            var text by remember(style.longPress) { mutableStateOf(KeyStyle.format(style.longPress)) }
            val apply = { change { it.copy(longPress = KeyStyle.parse(text)) } }
            androidx.compose.material3.OutlinedTextField(
                text, { text = it },
                Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) apply() },
                label = { Text(stringResource(R.string.keystyle_long_press)) },
                placeholder = { Text(stringResource(R.string.keystyle_long_press_example)) },
                minLines = 2, maxLines = 6,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { apply() }),
            )
            Note(stringResource(R.string.keystyle_long_press_note))
        }
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
 * Draws the Privacy page: the incognito switch, clearing the learning data (after a confirmation,
 * [LearningData]), the link to the learned readings ([LearnedPage]), the clipboard settings (history, how long it
 * keeps unpinned items, quick paste, clearing, the link to the history page [ClipboardPage]) and
 * what Kotomo keeps and sends.
 * Inputs: open navigates to a sub-page.
 */
@Composable
internal fun PrivacyPage(settings: Settings, store: SettingsStore, open: (Page) -> Unit) {
    val context = LocalContext.current
    val clip = settings.clipboard
    var confirm by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    Section(null) {
        SwitchRow(R.string.incognito_title, R.string.incognito_desc, settings.incognito) { on -> store.set { it.copy(incognito = on) } }
        Inset {
            Label(stringResource(R.string.learning_clear_title))
            Note(stringResource(if (cleared) R.string.learning_cleared else R.string.learning_clear_desc))
            OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.learning_clear)) }
            OutlinedButton(onClick = { open(Page.LEARNED) }) { Text(stringResource(R.string.learned_open)) }
        }
        // The store follows the switch at once; the keyboard also tells it the stored value.
        SwitchRow(R.string.learned_encrypt_title, R.string.learned_encrypt_desc, settings.learnedEncrypted) { on ->
            store.set { it.copy(learnedEncrypted = on) }
            com.yuanzhe.kotomo.ime.MixedLearning.get(context).setEncrypted(on)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.learning_clear_confirm_title)) },
            text = { Text(stringResource(R.string.learning_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    LearningData.clear(context) { cleared = true }
                }) { Text(stringResource(R.string.learning_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.learning_clear_cancel)) } },
        )
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
            OutlinedButton(onClick = { open(Page.CLIPBOARD) }) { Text(stringResource(R.string.clip_settings_open)) }
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
            Measure(stringResource(R.string.diag_engine), summary.engineWork?.let { stringResource(R.string.diag_series, it.p50, it.p95, it.max, it.count) })
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

/**
 * Draws a single-choice segmented control over [labels].
 * Notes: four or more choices use the smaller label style, so a word such as "Traditional" fits
 * its segment instead of breaking inside the word.
 */
@Composable
internal fun Choice(labels: List<String>, selected: Int, select: (Int) -> Unit) {
    val style = if (labels.size > 3) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected, onClick = { select(i) },
                shape = SegmentedButtonDefaults.itemShape(i, labels.size), label = { Text(label, maxLines = 2, style = style) },
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
internal fun Label(text: String) {
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
private const val ONE_HANDED_STEP = 2
private const val RADIUS_STEP = 2

/**
 * Draws the Learned readings page (PLAN §9.8, §9.9): what Mixed mode learned from the person's
 * choices, most recent first, each with what was typed and what it became, and the ways to rank
 * it lower, hide it, keep it first or delete it.
 * Notes: reads and changes [com.yuanzhe.kotomo.ime.MixedLearning]; the keyboard follows at once.
 * The list shows the [LEARNED_SHOWN] most recent readings.
 */
@Composable
internal fun LearnedPage() {
    val store = com.yuanzhe.kotomo.ime.MixedLearning.get(LocalContext.current)
    val learned by store.flow.collectAsState()
    val readings = learned.readings.values.sortedByDescending { it.usedUtcMs }
    val context = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }
    val exported = stringResource(R.string.learned_exported)
    val failed = stringResource(R.string.learned_file_failed)
    val notLearned = stringResource(R.string.learned_import_unknown)
    // The person picks where the export goes and which file is imported (Storage Access Framework).
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        result = runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(store.export().toByteArray()) } }
            .fold({ exported }, { failed })
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            // One byte more than the limit is enough to tell a file that is too large.
            context.contentResolver.openInputStream(uri)!!.use { input ->
                val bytes = ByteArray(LEARNED_IMPORT_MAX + 1)
                var size = 0
                while (size < bytes.size) size += input.read(bytes, size, bytes.size - size).takeIf { it > 0 } ?: break
                bytes.copyOf(size)
            }
        }.getOrNull()
        val count = text?.takeIf { it.size <= LEARNED_IMPORT_MAX }?.let { store.import(String(it)) }
        result = when {
            text == null -> failed
            count == null -> notLearned
            else -> context.resources.getQuantityString(R.plurals.learned_imported, count, count)
        }
    }
    Section(null) {
        Inset {
            Note(stringResource(if (readings.isEmpty()) R.string.learned_empty else R.string.learned_desc))
            androidx.compose.foundation.layout.FlowRow {
                TextButton(onClick = { save.launch(LEARNED_EXPORT_NAME) }) { Text(stringResource(R.string.learned_export)) }
                TextButton(onClick = { pick.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                    Text(stringResource(R.string.learned_import))
                }
            }
            Note(result ?: stringResource(R.string.learned_export_note))
        }
        readings.take(LEARNED_SHOWN).forEach { reading ->
            Inset {
                Label("${reading.input} → ${reading.text}")
                Note(
                    when (reading.rank) {
                        com.yuanzhe.kotomo.ime.LearnedRank.NORMAL -> pluralStringResource(R.plurals.learned_uses, reading.count, reading.count)
                        com.yuanzhe.kotomo.ime.LearnedRank.DEMOTED -> stringResource(R.string.learned_demoted)
                        com.yuanzhe.kotomo.ime.LearnedRank.BLOCKED -> stringResource(R.string.learned_blocked)
                        com.yuanzhe.kotomo.ime.LearnedRank.PINNED -> stringResource(R.string.learned_pinned)
                    },
                )
                androidx.compose.foundation.layout.FlowRow {
                    val normal = reading.rank == com.yuanzhe.kotomo.ime.LearnedRank.NORMAL
                    listOf(
                        com.yuanzhe.kotomo.ime.LearnedRank.PINNED to R.string.learned_pin,
                        com.yuanzhe.kotomo.ime.LearnedRank.DEMOTED to R.string.learned_demote,
                        com.yuanzhe.kotomo.ime.LearnedRank.BLOCKED to R.string.learned_block,
                    ).forEach { (rank, label) ->
                        if (normal) TextButton(onClick = { store.update { it.ranked(reading, rank) } }) { Text(stringResource(label)) }
                    }
                    if (!normal) {
                        TextButton(onClick = { store.update { it.ranked(reading, com.yuanzhe.kotomo.ime.LearnedRank.NORMAL) } }) {
                            Text(stringResource(R.string.learned_reset))
                        }
                    }
                    TextButton(onClick = { store.update { it.without(reading) } }) { Text(stringResource(R.string.learned_delete)) }
                }
            }
        }
    }
}

/** The name offered for an export, and the largest file an import reads (bytes). */
private const val LEARNED_EXPORT_NAME = "kotomo-learned.json"
private const val LEARNED_IMPORT_MAX = 4 * 1024 * 1024

/** How many learned readings the page lists. */
private const val LEARNED_SHOWN = 200
