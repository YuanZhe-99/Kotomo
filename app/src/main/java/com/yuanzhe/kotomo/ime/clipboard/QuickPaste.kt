package com.yuanzhe.kotomo.ime.clipboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.yuanzhe.kotomo.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Decides what the quick-paste strip offers: the text just copied, for a short window after
 * copying, shown once per clip (PLAN §10.7).
 *
 * Inputs: windowMs is how long after copying the strip is offered.
 * Notes: main thread. A clip leaves the strip when it is pasted, closed, replaced by a newer copy
 * or older than the window, and the same clip (same text and time) is never offered again after
 * that. Holds the text in memory only; nothing is written.
 */
class QuickPaste(private val windowMs: Long = WINDOW_MS) {
    private val state = MutableStateFlow<Copied?>(null)
    /** The text whose strip was last pasted or closed, so the same clip is not offered twice. */
    private var finished: Copied? = null

    /** The clip on offer, or null; does not expire by itself (use [current]). */
    val offer: StateFlow<Copied?> = state

    /**
     * Offers [copied] on the strip, replacing an older offer.
     * Notes: ignored when that same clip was already pasted or closed.
     */
    fun offer(copied: Copied) {
        if (copied == finished) return
        state.value = copied
    }

    /**
     * Returns the clip to show at [nowUtcMs], or null.
     * Side effects: drops an offer older than the window.
     */
    fun current(nowUtcMs: Long): Copied? {
        val c = state.value ?: return null
        if (nowUtcMs - c.atUtcMs !in 0 until windowMs) {
            state.value = null
            return null
        }
        return c
    }

    /** Returns how long the current offer still shows at [nowUtcMs], for scheduling its hiding; 0 when none. */
    fun remainingMs(nowUtcMs: Long): Long = current(nowUtcMs)?.let { it.atUtcMs + windowMs - nowUtcMs } ?: 0

    /** Removes the offer after it was pasted or closed; that clip is not offered again. */
    fun done() {
        finished = state.value ?: finished
        state.value = null
    }

    /** One chip of the strip: a code (the likeliest is [best]) or the copied text. */
    sealed interface Chip {
        data class Code(val code: VerificationCode, val best: Boolean) : Chip
        data class Text(val text: String) : Chip
    }

    companion object {
        /** How long after copying the strip is offered. */
        const val WINDOW_MS = 60_000L

        /**
         * Lists the chips for [copied]: its codes, likeliest first, then the copied text.
         * Inputs: numberField is true for number fields (code fields), where only codes are
         * offered when there are any.
         * Notes: when the whole clip is the code, only the code chip is shown.
         */
        fun chips(copied: Copied, numberField: Boolean): List<Chip> {
            val codes = copied.codes.mapIndexed { i, c -> Chip.Code(c, best = i == 0) }
            val onlyCodes = codes.isNotEmpty() && (numberField || VerificationCodes.bare(copied.text) != null)
            return if (onlyCodes) codes else codes + Chip.Text(copied.text)
        }
    }
}

/**
 * Shows the quick-paste strip above the keys per the design (PhoneQuickPaste): a line saying what
 * is offered with a close button, then chips for each verification code (the likeliest
 * highlighted, marked when several are plausible, each with a little context) and for the text
 * just copied.
 *
 * Inputs: copied is [QuickPaste.current]; numberField is true in number fields; onPaste commits a
 * chip's text; onClose hides the strip.
 * Notes: the caller calls [QuickPaste.done] after onPaste and onClose.
 */
@Composable
fun QuickPasteStrip(copied: Copied, numberField: Boolean, onPaste: (String) -> Unit, onClose: () -> Unit) {
    val chips = QuickPaste.chips(copied, numberField)
    val codes = chips.count { it is QuickPaste.Chip.Code }
    val region = stringResource(R.string.quick_region)
    val secondary = colorResource(R.color.kb_ink_secondary)
    Column(
        Modifier
            .fillMaxWidth()
            .background(colorResource(R.color.kb_background))
            .semantics { paneTitle = region }
            .padding(
                start = dimensionResource(R.dimen.quick_pad_h),
                end = dimensionResource(R.dimen.quick_pad_h),
                top = dimensionResource(R.dimen.quick_pad_top),
                bottom = dimensionResource(R.dimen.quick_pad_bottom),
            ),
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.quick_gap)),
    ) {
        Row(
            Modifier.padding(start = dimensionResource(R.dimen.quick_header_pad)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.quick_gap)),
        ) {
            Image(
                painterResource(R.drawable.ic_tool_clipboard),
                contentDescription = null,
                modifier = Modifier.size(dimensionResource(R.dimen.quick_header_icon)),
                colorFilter = ColorFilter.tint(secondary),
            )
            Text(
                stringResource(
                    when {
                        codes > 1 -> R.string.quick_codes_many
                        codes == 1 -> R.string.quick_code_one
                        else -> R.string.quick_copied_hint
                    },
                ),
                Modifier.weight(1f),
                fontSize = dimensionResource(R.dimen.quick_header_text).asText(),
                color = secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val close = stringResource(R.string.quick_close)
            Box(
                Modifier
                    .size(dimensionResource(R.dimen.quick_close))
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = close },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painterResource(R.drawable.ic_clip_close),
                    contentDescription = null,
                    modifier = Modifier.size(dimensionResource(R.dimen.quick_close_icon)),
                    colorFilter = ColorFilter.tint(secondary),
                )
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.quick_gap)),
        ) {
            chips.forEach { chip ->
                when (chip) {
                    is QuickPaste.Chip.Code -> CodeChip(chip, marked = codes > 1) { onPaste(chip.code.value) }
                    is QuickPaste.Chip.Text -> TextChip(chip.text) { onPaste(chip.text) }
                }
            }
        }
    }
}

/** Draws one code chip: the code in large letters, "more likely" on the best of several, its context. */
@Composable
private fun CodeChip(chip: QuickPaste.Chip.Code, marked: Boolean, onClick: () -> Unit) {
    val ink = colorResource(if (chip.best) R.color.kb_on_accent_container else R.color.kb_ink)
    val sub = colorResource(if (chip.best) R.color.kb_on_accent_container else R.color.kb_ink_secondary)
    Chip(
        width = dimensionResource(if (chip.best && marked) R.dimen.quick_chip_best_width else R.dimen.quick_chip_width),
        background = colorResource(if (chip.best) R.color.kb_accent_container else R.color.kb_key),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.quick_gap))) {
            Text(
                chip.code.value,
                fontSize = dimensionResource(R.dimen.quick_code_text).asText(),
                fontWeight = FontWeight.Bold,
                letterSpacing = dimensionResource(R.dimen.quick_code_spacing).asText(),
                color = ink,
                maxLines = 1,
            )
            if (chip.best && marked) {
                Text(stringResource(R.string.quick_more_likely), fontSize = dimensionResource(R.dimen.quick_context_text).asText(), color = ink, maxLines = 1)
            }
        }
        if (chip.code.context.isNotEmpty()) {
            Text(
                chip.code.context,
                fontSize = dimensionResource(R.dimen.quick_context_text).asText(),
                color = sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Draws the chip of the text just copied: "Just copied" with the clipboard icon, and the text's start. */
@Composable
private fun TextChip(text: String, onClick: () -> Unit) {
    val ink = colorResource(R.color.kb_ink)
    Chip(dimensionResource(R.dimen.quick_chip_width), colorResource(R.color.kb_key), onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.quick_gap))) {
            Image(
                painterResource(R.drawable.ic_tool_clipboard),
                contentDescription = null,
                modifier = Modifier.size(dimensionResource(R.dimen.quick_chip_icon)),
                colorFilter = ColorFilter.tint(ink),
            )
            Text(
                stringResource(R.string.quick_copied),
                fontSize = dimensionResource(R.dimen.quick_copied_text).asText(),
                fontWeight = FontWeight.Medium,
                color = ink,
                maxLines = 1,
            )
        }
        Text(
            text.replace(whitespace, " ").trim(),
            fontSize = dimensionResource(R.dimen.quick_context_text).asText(),
            color = colorResource(R.color.kb_ink_secondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val whitespace = Regex("\\s+")

/** Draws a chip's rounded, tappable frame. */
@Composable
private fun Chip(width: androidx.compose.ui.unit.Dp, background: androidx.compose.ui.graphics.Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(width)
            .height(dimensionResource(R.dimen.quick_chip_height))
            .clip(RoundedCornerShape(dimensionResource(R.dimen.quick_chip_radius)))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = dimensionResource(R.dimen.quick_chip_pad_h)),
        verticalArrangement = Arrangement.Center,
    ) { content() }
}
