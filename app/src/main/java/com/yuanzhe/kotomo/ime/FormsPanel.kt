package com.yuanzhe.kotomo.ime

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.ime.Inflection.Form
import com.yuanzhe.kotomo.ime.Inflection.Kind
import com.yuanzhe.kotomo.ime.Inflection.LexicalCategory
import com.yuanzhe.kotomo.ime.Inflection.Register
import kotlin.math.abs

/**
 * UI state driving [FormsPanel].
 *
 * Inputs: [target] holds the resolved word, surrounding sentence context (`prefix`/`suffix`), and
 * conjugated forms across registers; [allowScriptVariants] enables hiragana / katakana / half-width
 * katakana pills when inspecting an isolated word or segment without attached affixes.
 */
data class FormsView(
    val target: Inflection.Target,
    val allowScriptVariants: Boolean = target.prefix.isEmpty() && target.suffix.isEmpty(),
)

/** Maps a politeness [register] tab to its localized label string resource. */
fun registerLabel(register: Register): Int = when (register) {
    Register.PLAIN -> R.string.register_plain
    Register.POLITE -> R.string.register_polite
    Register.HONORIFIC -> R.string.register_honorific
    Register.HUMBLE -> R.string.register_humble
}

/** Maps a resolved word [kind] to its localized grammatical class summary. */
fun kindLabel(kind: Kind): Int = when (kind) {
    is Kind.Godan -> R.string.kind_godan
    is Kind.Ichidan -> R.string.kind_ichidan
    Kind.Kuru -> R.string.kind_kuru
    is Kind.Suru -> R.string.kind_suru
    is Kind.Adjective -> R.string.kind_adj_i
    Kind.NaAdjective -> R.string.kind_adj_na
    is Kind.Lexical -> when (kind.category) {
        LexicalCategory.NOUN -> R.string.kind_lex_noun
        LexicalCategory.PRONOUN -> R.string.kind_lex_pronoun
        LexicalCategory.ADVERB -> R.string.kind_lex_adverb
        LexicalCategory.EXPRESSION -> R.string.kind_lex_expression
    }
}

/** Maps a [form] entry to its default localized label in [FormsPanel]. */
fun formLabel(form: Form): Int = when (form) {
    Form.PLAIN -> R.string.form_plain
    Form.POLITE -> R.string.form_polite
    Form.MASU -> R.string.form_masu
    Form.MASEN -> R.string.form_masen
    Form.MASHITA -> R.string.form_mashita
    Form.MASENDESHITA -> R.string.form_masendeshita
    Form.MASHOU -> R.string.form_mashou
    Form.POTENTIAL_POLITE -> R.string.form_potential_polite
    Form.TE_KUDASAI -> R.string.form_te_kudasai
    Form.POLITE_NEGATIVE -> R.string.form_polite_negative
    Form.TE -> R.string.form_te
    Form.TA -> R.string.form_ta
    Form.NAI -> R.string.form_nai
    Form.PAST_NEGATIVE -> R.string.form_past_negative
    Form.BA -> R.string.form_ba
    Form.POTENTIAL -> R.string.form_potential
    Form.PASSIVE -> R.string.form_passive
    Form.CAUSATIVE -> R.string.form_causative
    Form.VOLITIONAL -> R.string.form_volitional
    Form.IMPERATIVE -> R.string.form_imperative
    Form.ADNOMINAL -> R.string.form_adnominal
    Form.ADVERBIAL -> R.string.form_adverbial
    Form.HONORIFIC_PLAIN -> R.string.form_honorific_plain
    Form.HONORIFIC -> R.string.form_honorific
    Form.HONORIFIC_ALT -> R.string.form_honorific_alt
    Form.HONORIFIC_PAST -> R.string.form_honorific_past
    Form.HONORIFIC_PASSIVE -> R.string.form_honorific_passive
    Form.HONORIFIC_REQUEST -> R.string.form_honorific_request
    Form.HUMBLE_PLAIN -> R.string.form_humble_plain
    Form.HUMBLE -> R.string.form_humble
    Form.HUMBLE_ALT -> R.string.form_humble_alt
    Form.HUMBLE_PAST -> R.string.form_humble_past
}

/** Converts katakana codepoints (`ァ`–`ヶ`) in [kana] to hiragana while leaving other text intact. */
private fun toHiragana(kana: String): String = buildString(kana.length) {
    for (ch in kana) {
        append(if (ch in '\u30A1'..'\u30F6') (ch.code - 0x60).toChar() else ch)
    }
}

/** Converts hiragana codepoints (`ぁ`–`ゖ`) in [kana] to katakana while leaving other text intact. */
private fun toKatakana(kana: String): String = buildString(kana.length) {
    for (ch in kana) {
        append(if (ch in '\u3041'..'\u3096') (ch.code + 0x60).toChar() else ch)
    }
}

/**
 * Displays Japanese verb/adjective conjugations, keigo registers, and curated lexical equivalents
 * inside the keyboard panel area (PLAN §10.1).
 *
 * Inputs: [view] supplies the resolved [Inflection.Target] and script-variant policy; [onPick]
 * receives `(committedSurface, segmentReading)` when the user selects a form card or script variant;
 * [onBack] closes the panel and restores the previous keyboard or segment view.
 * Notes: defaults to the first non-empty register (always [Register.PLAIN] when present so common
 * plain inflections such as `食べない` are visible without switching tabs), supports horizontal
 * swipe across available registers, and switches from 3 columns to 2 columns at large font scales.
 */
@Composable
fun FormsPanel(
    view: State<FormsView?>,
    onPick: (text: String, reading: String) -> Unit,
    onBack: () -> Unit,
) {
    val current = view.value ?: return
    val target = current.target
    val word = target.word

    val gap = dimensionResource(R.dimen.forms_gap)
    val radius = dimensionResource(R.dimen.forms_radius)
    val chipHeight = dimensionResource(R.dimen.forms_chip_height)
    val chipPad = dimensionResource(R.dimen.forms_chip_pad)
    val closeSize = dimensionResource(R.dimen.forms_close_size)
    val tabBarHeight = dimensionResource(R.dimen.forms_tab_bar_height)
    val variantHeight = dimensionResource(R.dimen.forms_variant_height)

    val density = LocalDensity.current
    val headerSp = with(density) { dimensionResource(R.dimen.forms_header_text).toSp() }
    val metaSp = with(density) { dimensionResource(R.dimen.forms_meta_text).toSp() }
    val tabSp = with(density) { dimensionResource(R.dimen.forms_tab_text).toSp() }
    val labelSp = with(density) { dimensionResource(R.dimen.forms_label_text).toSp() }
    val textSp = with(density) { dimensionResource(R.dimen.forms_text).toSp() }

    val grouped = remember(current) { target.forms.groupBy { it.register } }
    val availableRegisters = remember(grouped) {
        Register.entries.filter { grouped[it].orEmpty().isNotEmpty() }
    }
    val initialRegister = availableRegisters.firstOrNull() ?: Register.PLAIN
    var selectedRegister by remember(current) { mutableStateOf(initialRegister) }
    if (selectedRegister !in availableRegisters && availableRegisters.isNotEmpty()) {
        selectedRegister = initialRegister
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(current, selectedRegister) { scrollState.scrollTo(0) }

    val scriptVariants = remember(current) {
        if (!current.allowScriptVariants || target.prefix.isNotEmpty() || target.suffix.isNotEmpty()) {
            emptyList()
        } else {
            val reading = word.reading
            if (reading.none { it in '\u3041'..'\u3096' || it in '\u30A1'..'\u30F6' }) {
                emptyList()
            } else {
                listOf(
                    toHiragana(reading),
                    toKatakana(reading),
                    Inflection.halfWidthKatakana(reading),
                ).filter { it.isNotEmpty() }.distinct().takeIf { it.size >= 2 }.orEmpty()
            }
        }
    }

    val closeDesc = stringResource(R.string.candidate_details_close)
    val bgColor = kbColor(R.color.kb_background)
    val keyColor = kbColor(R.color.kb_key)
    val fnColor = kbColor(R.color.kb_key_function)
    val inkColor = kbColor(R.color.kb_ink)
    val secondaryColor = kbColor(R.color.kb_ink_secondary)
    val accentBg = kbColor(R.color.kb_accent_container)
    val accentFg = kbColor(R.color.kb_on_accent_container)

    Column(
        Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(horizontal = gap, vertical = gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = closeSize),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    Text(
                        text = word.text,
                        fontSize = headerSp,
                        fontWeight = FontWeight.Bold,
                        color = inkColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (word.reading.isNotEmpty() && word.reading != word.text) {
                        Text(
                            text = word.reading,
                            fontSize = metaSp,
                            color = secondaryColor,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = stringResource(kindLabel(word.kind)),
                        fontSize = metaSp,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (target.prefix.isNotEmpty() || target.suffix.isNotEmpty()) {
                    val preview = buildAnnotatedString {
                        append(target.prefix)
                        withStyle(SpanStyle(background = accentBg, color = accentFg, fontWeight = FontWeight.Medium)) {
                            append(target.currentSurface)
                        }
                        append(target.suffix)
                    }
                    Text(
                        text = preview,
                        fontSize = metaSp,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier
                    .size(closeSize)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = closeDesc },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_clip_close),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(inkColor),
                )
            }
        }

        val tabOuterRadius = radius + 4.dp
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = tabBarHeight)
                .clip(RoundedCornerShape(tabOuterRadius))
                .background(fnColor)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Register.entries.forEach { reg ->
                val hasForms = grouped[reg].orEmpty().isNotEmpty()
                val isSelected = reg == selectedRegister
                val tabLabel = stringResource(registerLabel(reg))
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = tabBarHeight - 6.dp)
                        .clip(RoundedCornerShape(radius))
                        .background(if (isSelected) accentBg else Color.Transparent)
                        .clickable(enabled = hasForms, role = Role.Tab) { selectedRegister = reg }
                        .semantics { selected = isSelected }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tabLabel,
                        color = when {
                            isSelected -> accentFg
                            hasForms -> inkColor
                            else -> secondaryColor.copy(alpha = 0.45f)
                        },
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = tabSp),
                    )
                }
            }
        }

        val columns = if (density.fontScale >= 1.4f) 2 else 3
        val activeForms = grouped[selectedRegister].orEmpty()
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(current, availableRegisters) {
                    if (availableRegisters.size <= 1) return@pointerInput
                    var dragSum = 0f
                    val thresholdPx = with(density) { 48.dp.toPx() }
                    detectHorizontalDragGestures(
                        onDragStart = { dragSum = 0f },
                        onDragCancel = { dragSum = 0f },
                        onHorizontalDrag = { _, dragAmount -> dragSum += dragAmount },
                        onDragEnd = {
                            val idx = availableRegisters.indexOf(selectedRegister)
                            if (idx >= 0) {
                                val next = when {
                                    dragSum <= -thresholdPx && idx < availableRegisters.lastIndex -> idx + 1
                                    dragSum >= thresholdPx && idx > 0 -> idx - 1
                                    else -> idx
                                }
                                if (next != idx) selectedRegister = availableRegisters[next]
                            }
                            dragSum = 0f
                        },
                    )
                }
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            activeForms.chunked(columns).forEach { rowItems ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    rowItems.forEach { item ->
                        val isCurrent = item.text == target.currentSurface
                        val cardBg = if (isCurrent) accentBg else keyColor
                        val cardFg = if (isCurrent) accentFg else inkColor
                        val cardSub = if (isCurrent) accentFg else secondaryColor
                        val fullText = target.prefix + item.text + target.suffix
                        val fullReading = if (target.prefix.isEmpty()) item.reading + target.suffix else item.reading
                        Column(
                            Modifier
                                .weight(1f)
                                .heightIn(min = chipHeight)
                                .clip(RoundedCornerShape(radius))
                                .background(cardBg)
                                .clickable { onPick(fullText, fullReading) }
                                .padding(horizontal = chipPad, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = item.text,
                                color = cardFg,
                                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = textSp),
                            )
                            Text(
                                text = stringResource(item.customLabel ?: formLabel(item.form)),
                                color = cardSub,
                                fontSize = labelSp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    repeat(columns - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            if (scriptVariants.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.forms_script_variants),
                        fontSize = labelSp,
                        color = secondaryColor,
                        maxLines = 1,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        scriptVariants.forEach { variant ->
                            val isCurrent = variant == target.currentSurface
                            Box(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = variantHeight)
                                    .clip(RoundedCornerShape(radius))
                                    .background(if (isCurrent) accentBg else keyColor)
                                    .clickable { onPick(variant, word.reading) }
                                    .padding(horizontal = chipPad, vertical = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = variant,
                                    color = if (isCurrent) accentFg else inkColor,
                                    maxLines = 1,
                                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = textSp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
