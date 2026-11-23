package com.yuanzhe.kotomo.ime.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yuanzhe.kotomo.ime.kbColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yuanzhe.kotomo.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Decides what the quick-paste strip offers: the text or image just copied, for a short window
 * after copying, shown once per clip (PLAN §10.7).
 *
 * Inputs: windowMs is how long after copying the strip is offered.
 * Notes: main thread. A clip leaves the strip when it is pasted, closed, replaced by a newer copy
 * or older than the window, and the same clip (same text/image and time) is never offered again
 * after that. Once the person begins typing in a field ([suppressIn]), that clip stays quiet in
 * that field until a newer clip arrives while remaining available in other fields until it expires.
 * Holds the clip in memory only; nothing is written.
 */
class QuickPaste(private val windowMs: Long = WINDOW_MS) {
    private val state = MutableStateFlow<Copied?>(null)
    /** The clip whose strip was last pasted or closed, so the same clip is not offered twice. */
    private var finished: Copied? = null
    /** Fields where typing began while the current clip was offered. */
    private val suppressedFields = HashSet<String>()

    /** The clip on offer, or null; does not expire by itself (use [current]). */
    val offer: StateFlow<Copied?> = state

    /**
     * Offers [copied] on the strip, replacing an older offer.
     * Notes: ignored when that same clip was already pasted or closed; a different clip clears
     * prior per-field typing suppressions so new copies still appear in non-empty fields.
     */
    fun offer(copied: Copied) {
        if (copied == finished) return
        if (copied != state.value) suppressedFields.clear()
        state.value = copied
    }

    /**
     * Returns the clip to show at [nowUtcMs] for [fieldKey] (or any field when null), or null.
     * Side effects: drops an offer older than the window.
     */
    fun current(nowUtcMs: Long, fieldKey: String? = null): Copied? {
        val c = state.value ?: return null
        if (nowUtcMs - c.atUtcMs !in 0 until windowMs) {
            state.value = null
            suppressedFields.clear()
            return null
        }
        if (fieldKey != null && fieldKey in suppressedFields) return null
        return c
    }

    /** Suppresses the current offer in [fieldKey] once typing starts there without dismissing other fields. */
    fun suppressIn(fieldKey: String) {
        if (state.value != null) suppressedFields += fieldKey
    }

    /** Returns how long the current offer still shows at [nowUtcMs], for scheduling its hiding; 0 when none. */
    fun remainingMs(nowUtcMs: Long): Long = current(nowUtcMs)?.let { it.atUtcMs + windowMs - nowUtcMs } ?: 0

    /** Removes the offer after it was pasted or closed; that clip is not offered again. */
    fun done() {
        suppressedFields.clear()
        finished = state.value ?: finished
        state.value = null
    }

    /** One chip of the strip: a code (the likeliest is [best]), the copied text, or a copied image. */
    sealed interface Chip {
        data class Code(val code: VerificationCode, val best: Boolean) : Chip
        data class Text(val text: String) : Chip
        data class Image(val image: CopiedImage) : Chip
    }

    companion object {
        /** How long after copying the strip is offered. */
        const val WINDOW_MS = 60_000L

        /**
         * Lists the chips for [copied]: its image when present and the field accepts images (and
         * is not a number field), or its codes (likeliest first) and copied text.
         * Inputs: numberField is true for number fields (code fields), where only codes are
         * offered when there are any; acceptsImages says whether the field accepts rich content.
         * Notes: when the whole clip is the code, only the code chip is shown.
         */
        fun chips(copied: Copied, numberField: Boolean, acceptsImages: Boolean = true): List<Chip> {
            copied.image?.let { img ->
                return if (acceptsImages && !numberField) listOf(Chip.Image(img)) else emptyList()
            }
            val codes = copied.codes.mapIndexed { i, c -> Chip.Code(c, best = i == 0) }
            val onlyCodes = codes.isNotEmpty() && (numberField || VerificationCodes.bare(copied.text) != null)
            return if (onlyCodes) codes else codes + Chip.Text(copied.text)
        }
    }
}

/**
 * Shows the single-row `bar_height` quick-paste strip in the candidate bar's slot: a leading
 * More button so tools remain reachable, a scrollable row of pill chips (verification codes,
 * copied text, or a copied image thumbnail), and a trailing close button.
 *
 * Inputs: copied is [QuickPaste.current]; numberField is true in number fields; acceptsImages says
 * whether the field accepts pasted images; onPaste commits a text/code chip; onPasteImage commits
 * a copied image; onMore opens the More panel; onClose hides the strip.
 * Notes: the caller calls [QuickPaste.done] after onPaste, onPasteImage and onClose.
 */
@Composable
fun QuickPasteStrip(
    copied: Copied,
    numberField: Boolean,
    acceptsImages: Boolean = true,
    onPaste: (String) -> Unit,
    onPasteImage: (CopiedImage) -> Unit = {},
    onMore: () -> Unit = {},
    onClose: () -> Unit,
) {
    val chips = QuickPaste.chips(copied, numberField, acceptsImages)
    if (chips.isEmpty()) return
    val codes = chips.count { it is QuickPaste.Chip.Code }
    val region = stringResource(R.string.quick_region)
    val moreDesc = stringResource(R.string.tool_more)
    val closeDesc = stringResource(R.string.quick_close)
    val ink = kbColor(R.color.kb_ink)
    val secondary = kbColor(R.color.kb_ink_secondary)
    val gap = dimensionResource(R.dimen.quick_gap)
    Row(
        Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.bar_height))
            .background(kbColor(R.color.kb_background))
            .semantics { paneTitle = region },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(dimensionResource(R.dimen.toolbar_button))
                .fillMaxHeight()
                .clickable(onClick = onMore)
                .semantics { contentDescription = moreDesc },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_tool_more),
                contentDescription = null,
                modifier = Modifier.size(dimensionResource(R.dimen.kb_icon)),
                colorFilter = ColorFilter.tint(ink),
            )
        }
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(end = gap),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            chips.forEach { chip ->
                when (chip) {
                    is QuickPaste.Chip.Code -> CodeChip(
                        chip,
                        marked = codes > 1,
                        showContext = true,
                    ) { onPaste(chip.code.value) }
                    is QuickPaste.Chip.Text -> TextChip(chip.text) { onPaste(chip.text) }
                    is QuickPaste.Chip.Image -> ImageChip(chip.image) { onPasteImage(chip.image) }
                }
            }
        }
        Box(
            Modifier
                .padding(end = gap)
                .size(dimensionResource(R.dimen.quick_close))
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .semantics { contentDescription = closeDesc },
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
}

/** Draws one code pill: icon, bold verification code, and "more likely" or compact context. */
@Composable
private fun CodeChip(chip: QuickPaste.Chip.Code, marked: Boolean, showContext: Boolean, onClick: () -> Unit) {
    val ink = kbColor(if (chip.best) R.color.kb_on_accent_container else R.color.kb_ink)
    val sub = kbColor(if (chip.best) R.color.kb_on_accent_container else R.color.kb_ink_secondary)
    val bg = kbColor(if (chip.best) R.color.kb_accent_container else R.color.kb_key)
    PillChip(
        background = bg,
        maxWidth = dimensionResource(R.dimen.quick_code_max_width),
        onClick = onClick,
    ) {
        Image(
            painterResource(R.drawable.ic_tool_clipboard),
            contentDescription = null,
            modifier = Modifier.size(dimensionResource(R.dimen.quick_chip_icon)),
            colorFilter = ColorFilter.tint(ink),
        )
        Text(
            chip.code.value,
            fontSize = dimensionResource(R.dimen.quick_code_text).asText(),
            fontWeight = FontWeight.Bold,
            letterSpacing = dimensionResource(R.dimen.quick_code_spacing).asText(),
            color = ink,
            maxLines = 1,
        )
        if (chip.best && marked) {
            Text(
                stringResource(R.string.quick_more_likely),
                fontSize = dimensionResource(R.dimen.quick_context_text).asText(),
                color = sub,
                maxLines = 1,
            )
        } else if (showContext && chip.code.context.isNotEmpty()) {
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

/** Draws a single-line pill of the copied text: clipboard icon and ellipsized preview. */
@Composable
private fun TextChip(text: String, onClick: () -> Unit) {
    val ink = kbColor(R.color.kb_ink)
    val secondary = kbColor(R.color.kb_ink_secondary)
    val preview = text.replace(whitespace, " ").trim()
    val copiedLabel = stringResource(R.string.quick_copied)
    PillChip(
        background = kbColor(R.color.kb_key),
        maxWidth = dimensionResource(R.dimen.quick_text_max_width),
        contentDescription = "$copiedLabel: $preview",
        onClick = onClick,
    ) {
        Image(
            painterResource(R.drawable.ic_tool_clipboard),
            contentDescription = null,
            modifier = Modifier.size(dimensionResource(R.dimen.quick_chip_icon)),
            colorFilter = ColorFilter.tint(secondary),
        )
        Text(
            preview,
            fontSize = dimensionResource(R.dimen.quick_copied_text).asText(),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Draws a single-line pill of a copied image: rounded thumbnail preview beside "Image". */
@Composable
private fun ImageChip(image: CopiedImage, onClick: () -> Unit) {
    val thumbSize = dimensionResource(R.dimen.quick_thumb_size)
    val thumbPx = with(LocalDensity.current) { thumbSize.roundToPx() }
    val bitmap by produceState<Bitmap?>(null, image.sha256, thumbPx) {
        value = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return@withContext null
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= thumbPx) sample *= 2
            BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    val ink = kbColor(R.color.kb_ink)
    val label = stringResource(R.string.quick_copied_image)
    PillChip(
        background = kbColor(R.color.kb_key),
        maxWidth = Dp.Unspecified,
        paddingStart = 4.dp,
        paddingEnd = dimensionResource(R.dimen.quick_chip_pad_h),
        onClick = onClick,
    ) {
        Box(
            Modifier
                .size(thumbSize)
                .clip(RoundedCornerShape(12.dp))
                .background(kbColor(R.color.kb_background)),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let { bmp ->
                Image(
                    bmp.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Text(
            label,
            fontSize = dimensionResource(R.dimen.quick_copied_text).asText(),
            color = ink,
            maxLines = 1,
        )
    }
}

private val whitespace = Regex("\\s+")

/** Draws a single-row, `bar_pill_height`-tall tappable pill on the quick-paste strip. */
@Composable
private fun PillChip(
    background: Color,
    maxWidth: Dp,
    paddingStart: Dp = dimensionResource(R.dimen.quick_chip_pad_h),
    paddingEnd: Dp = dimensionResource(R.dimen.quick_chip_pad_h),
    contentDescription: String? = null,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .height(dimensionResource(R.dimen.bar_pill_height))
            .then(if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier)
            .clip(RoundedCornerShape(dimensionResource(R.dimen.bar_pill_radius)))
            .background(background)
            .clickable(onClick = onClick)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .padding(start = paddingStart, end = paddingEnd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.quick_gap)),
        content = content,
    )
}
