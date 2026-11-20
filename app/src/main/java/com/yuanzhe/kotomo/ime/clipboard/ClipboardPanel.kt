package com.yuanzhe.kotomo.ime.clipboard

import android.graphics.Bitmap
import com.yuanzhe.kotomo.ime.kbColor
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.yuanzhe.kotomo.R

/** One tab of the clipboard panel and which items it lists. */
private enum class Tab(@StringRes val label: Int, val shows: (Clip) -> Boolean) {
    ALL(R.string.clip_tab_all, { true }),
    PINNED(R.string.clip_tab_pinned, { it.kept }),
    CODES(R.string.clip_tab_codes, { it.kind == ClipKind.CODE }),
    IMAGES(R.string.clip_tab_images, { it.isImage }),
}

/** How long ago an item was copied, as the panel words it. */
internal sealed interface Ago {
    data object JustNow : Ago
    data class Minutes(val n: Int) : Ago
    data class Hours(val n: Int) : Ago
    data object Yesterday : Ago
    data class Days(val n: Int) : Ago

    companion object {
        /**
         * Buckets the time between [thenUtcMs] and [nowUtcMs].
         * Notes: under a minute (or in the future, after a clock change) is "just now"; "yesterday"
         * is 24–48 hours, not the calendar day, so it needs no time zone.
         */
        fun of(nowUtcMs: Long, thenUtcMs: Long): Ago {
            val minutes = (nowUtcMs - thenUtcMs) / 60_000
            return when {
                minutes < 1 -> JustNow
                minutes < 60 -> Minutes(minutes.toInt())
                minutes < 24 * 60 -> Hours((minutes / 60).toInt())
                minutes < 48 * 60 -> Yesterday
                else -> Days((minutes / (24 * 60)).toInt())
            }
        }
    }
}

/** Converts a dimension token to a text size, keeping sp tokens' font scaling. */
@Composable
internal fun Dp.asText(): TextUnit = with(LocalDensity.current) { toSp() }

/**
 * Shows the clipboard history in the keyboard's place per the design (PhoneClipboard): back,
 * the tabs All / Pinned and phrases / Codes, a two-column grid of cards (tag, time, pin mark, up to
 * three lines of text; a code card shows its code large with context), and a footer saying how the
 * history is kept, with Manage.
 *
 * Inputs: history is [ClipboardHistory.items] (collectAsState); retentionDays and recording come
 * from the clipboard settings (recording false: the history is off); appExcluded says whether the
 * app being typed in is one where nothing is kept (null: no app, no toggle); onPaste commits
 * text; onPin pins or unpins; onDelete deletes; onExclude adds or removes that app; onBack
 * returns to the keys; onManage opens the clipboard settings.
 * Notes: tapping a card pastes it (a code card pastes the code); long-pressing it opens a menu
 * over the panel with Pin or Unpin, Paste the whole text (code cards from a longer message),
 * Delete and Cancel, each line at least the panel touch size (A6, device feedback 2026-11-13); a
 * tap outside the menu closes it. The times are measured from when the panel opened.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipboardPanel(
    history: State<List<Clip>>,
    retentionDays: Int,
    recording: Boolean,
    onPaste: (String) -> Unit,
    onPin: (Clip, Boolean) -> Unit,
    onDelete: (Clip) -> Unit,
    onBack: () -> Unit,
    onManage: () -> Unit,
    appExcluded: Boolean? = null,
    onExclude: (Boolean) -> Unit = {},
    onPasteImage: (Clip) -> Unit = {},
    loadThumb: suspend (Clip, Int) -> Bitmap? = { _, _ -> null },
) {
    var tab by remember { mutableStateOf(Tab.ALL) }
    var actions by remember { mutableStateOf<Long?>(null) }
    val now = remember { System.currentTimeMillis() }
    val items = history.value.filter(tab.shows)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(kbColor(R.color.kb_background))) {
            TopBar(tab, onBack) {
                tab = it
                actions = null
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (items.isEmpty()) {
                    Text(
                        stringResource(
                            when (tab) {
                                Tab.ALL -> R.string.clip_empty_all
                                Tab.PINNED -> R.string.clip_empty_pinned
                                Tab.CODES -> R.string.clip_empty_codes
                                Tab.IMAGES -> R.string.clip_empty_images
                            },
                        ),
                        Modifier.align(Alignment.Center).padding(dimensionResource(R.dimen.clip_footer_pad)),
                        fontSize = dimensionResource(R.dimen.clip_text).asText(),
                        color = kbColor(R.color.kb_ink_secondary),
                    )
                } else {
                    val gap = dimensionResource(R.dimen.clip_card_gap)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(integerResource(R.integer.clip_columns)),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = dimensionResource(R.dimen.clip_grid_pad_h),
                            vertical = dimensionResource(R.dimen.clip_grid_pad_v),
                        ),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        items(items, key = { it.id }) { clip ->
                            Card(
                                clip,
                                now,
                                loadThumb = loadThumb,
                                onClick = { if (clip.isImage) onPasteImage(clip) else onPaste(clip.code?.value ?: clip.text) },
                                onLongClick = { actions = clip.id },
                            )
                        }
                    }
                }
            }
            Footer(retentionDays, recording, appExcluded, onExclude, onManage)
        }
        // Over the whole panel, so a short keyboard does not squeeze the menu's lines.
        items.firstOrNull { it.id == actions }?.let { clip ->
            Actions(
                clip,
                onPin = { actions = null; onPin(clip, !clip.pinned) },
                onPasteAll = { actions = null; onPaste(clip.text) },
                onDelete = { actions = null; onDelete(clip) },
                onCancel = { actions = null },
            )
        }
    }
}

/** Draws the top row: back, the tabs. */
@Composable
private fun TopBar(tab: Tab, onBack: () -> Unit, onTab: (Tab) -> Unit) {
    val back = stringResource(R.string.clip_back)
    Row(
        Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.clip_bar_height))
            .padding(horizontal = dimensionResource(R.dimen.clip_bar_pad_h)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.clip_bar_gap)),
    ) {
        Box(
            Modifier
                .size(dimensionResource(R.dimen.clip_icon_button))
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_settings_back),
                contentDescription = null,
                modifier = Modifier.size(dimensionResource(R.dimen.clip_icon)),
                colorFilter = ColorFilter.tint(kbColor(R.color.kb_ink)),
            )
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.clip_bar_gap)),
        ) {
            Tab.entries.forEach { t ->
                val selected = t == tab
                Box(
                    Modifier
                        .height(dimensionResource(R.dimen.clip_tab_height))
                        .clip(RoundedCornerShape(dimensionResource(R.dimen.clip_tab_height) / 2))
                        .background(colorResource(if (selected) R.color.kb_key else android.R.color.transparent))
                        .clickable { onTab(t) }
                        .semantics {
                            role = Role.Tab
                            this.selected = selected
                        }
                        .padding(horizontal = dimensionResource(R.dimen.clip_tab_pad)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(t.label),
                        fontSize = dimensionResource(R.dimen.clip_tab_text).asText(),
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = colorResource(if (selected) R.color.kb_on_accent_container else R.color.kb_ink),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Names a clip's tag, or null for plain text. Notes: a quick phrase shows the phrase tag. */
@StringRes
private fun tagOf(clip: Clip): Int? = when {
    clip.phrase -> R.string.clip_tag_phrase
    else -> when (clip.kind) {
        ClipKind.TEXT -> null
        ClipKind.LINK -> R.string.clip_tag_link
        ClipKind.ADDRESS -> R.string.clip_tag_address
        ClipKind.PHONE -> R.string.clip_tag_phone
        ClipKind.CODE -> R.string.clip_tag_code
        ClipKind.IMAGE -> R.string.clip_tag_image
    }
}

/** Words how long ago a clip was copied; a quick phrase says it is one instead. */
@Composable
private fun timeOf(clip: Clip, now: Long): String {
    if (clip.phrase) return stringResource(R.string.clip_time_phrase)
    return when (val ago = Ago.of(now, clip.timeUtcMs)) {
        Ago.JustNow -> stringResource(R.string.clip_time_now)
        is Ago.Minutes -> pluralStringResource(R.plurals.clip_time_minutes, ago.n, ago.n)
        is Ago.Hours -> pluralStringResource(R.plurals.clip_time_hours, ago.n, ago.n)
        Ago.Yesterday -> stringResource(R.string.clip_time_yesterday)
        is Ago.Days -> pluralStringResource(R.plurals.clip_time_days, ago.n, ago.n)
    }
}

/** Draws one history card: its tag, time and pin mark over the text, code or image thumbnail. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Card(
    clip: Clip,
    now: Long,
    loadThumb: suspend (Clip, Int) -> Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val secondary = kbColor(R.color.kb_ink_secondary)
    val meta = dimensionResource(R.dimen.clip_meta_text).asText()
    CardFrame(
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = stringResource(R.string.clip_actions),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.clip_meta_gap)),
        ) {
            tagOf(clip)?.let { tag ->
                val phrase = clip.phrase
                val neutral = clip.kind != ClipKind.CODE
                Text(
                    stringResource(tag),
                    Modifier
                        .clip(RoundedCornerShape(dimensionResource(R.dimen.clip_tag_radius)))
                        .background(
                            colorResource(
                                when {
                                    phrase -> R.color.clip_tag_phrase
                                    neutral -> R.color.clip_tag_neutral
                                    else -> R.color.kb_accent_container
                                },
                            ),
                        )
                        .padding(horizontal = dimensionResource(R.dimen.clip_tag_pad_h), vertical = dimensionResource(R.dimen.clip_tag_pad_v)),
                    fontSize = meta,
                    fontWeight = FontWeight.Bold,
                    color = colorResource(
                        when {
                            phrase -> R.color.clip_on_tag_phrase
                            neutral -> R.color.clip_on_tag_neutral
                            else -> R.color.kb_on_accent_container
                        },
                    ),
                    maxLines = 1,
                )
            }
            Text(timeOf(clip, now), Modifier.weight(1f), fontSize = meta, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (clip.pinned) {
                val pinned = stringResource(R.string.clip_pinned)
                Image(
                    painterResource(R.drawable.ic_clip_pin),
                    contentDescription = pinned,
                    modifier = Modifier.size(dimensionResource(R.dimen.clip_pin_icon)),
                    colorFilter = ColorFilter.tint(kbColor(R.color.kb_accent)),
                )
            }
        }
        val code = clip.code
        when {
            clip.isImage -> {
                val thumbPx = with(LocalDensity.current) { 160.dp.roundToPx() }
                val bitmap by produceState<Bitmap?>(null, clip.image?.file, thumbPx) {
                    value = loadThumb(clip, thumbPx)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(dimensionResource(R.dimen.clip_thumb_radius)))
                        .background(kbColor(R.color.kb_background)),
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let { bmp ->
                        Image(
                            bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.clip_tag_image),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
            code != null -> {
                Text(code.value, fontSize = dimensionResource(R.dimen.clip_code_text).asText(), color = kbColor(R.color.kb_ink), maxLines = 1)
                if (code.context.isNotEmpty()) {
                    Text(code.context, fontSize = meta, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            else -> {
                Text(
                    clip.text,
                    fontSize = dimensionResource(R.dimen.clip_text).asText(),
                    color = kbColor(R.color.kb_ink),
                    maxLines = integerResource(R.integer.clip_text_lines),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Draws a card's long-press menu over the panel: the clip's first line (or image dimensions),
 * then Pin or Unpin, Paste the whole text, Delete and Cancel.
 * Notes: drawn inside the panel (a popup window would be clipped by the input method window),
 * at the bottom, no wider than the menu token on wide screens; the dimmed area around it cancels.
 */
@Composable
private fun Actions(clip: Clip, onPin: () -> Unit, onPasteAll: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(kbColor(R.color.kb_key_shadow))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.BottomCenter,
    ) {
        androidx.compose.material3.Surface(
            Modifier
                .padding(dimensionResource(R.dimen.panel_menu_margin))
                .widthIn(max = dimensionResource(R.dimen.panel_menu_width))
                .fillMaxWidth()
                // Taps on the menu's own padding do not fall through to the cancelling area.
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(dimensionResource(R.dimen.panel_menu_radius)),
            color = kbColor(R.color.kb_key),
            shadowElevation = dimensionResource(R.dimen.panel_menu_elevation),
        ) {
            // Scrolls rather than shrinking a line below the touch size when the keyboard is short.
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = dimensionResource(R.dimen.panel_menu_pad_v))) {
                val summary = clip.image?.let { img -> "${stringResource(R.string.clip_tag_image)} · ${img.width}×${img.height}" }
                    ?: clip.text.lineSequence().first().trim()
                Text(
                    summary,
                    Modifier.padding(horizontal = dimensionResource(R.dimen.panel_menu_pad_h), vertical = dimensionResource(R.dimen.panel_menu_pad_v)),
                    fontSize = dimensionResource(R.dimen.clip_meta_text).asText(),
                    color = kbColor(R.color.kb_ink_secondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Action(if (clip.pinned) R.string.clip_unpin else R.string.clip_pin, onPin)
                if (!clip.isImage && clip.code != null && clip.code.value != clip.text.trim()) Action(R.string.clip_paste_all, onPasteAll)
                Action(R.string.clip_delete, onDelete)
                Action(R.string.clip_cancel, onCancel, accent = false)
            }
        }
    }
}

/** Draws one menu line, at least the panel touch size tall, in the accent color (or the ink color when not [accent]). */
@Composable
private fun Action(@StringRes label: Int, onClick: () -> Unit, accent: Boolean = true) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = dimensionResource(R.dimen.panel_touch))
            .clickable(onClick = onClick)
            .padding(horizontal = dimensionResource(R.dimen.panel_menu_pad_h)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            stringResource(label),
            fontSize = dimensionResource(R.dimen.panel_menu_text).asText(),
            color = kbColor(if (accent) R.color.kb_accent else R.color.kb_ink),
            maxLines = 1,
        )
    }
}

/** Draws the rounded card frame of the grid, at the design's card height. */
@Composable
private fun CardFrame(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.clip_card_height))
            .clip(RoundedCornerShape(dimensionResource(R.dimen.clip_card_radius)))
            .background(kbColor(R.color.kb_key))
            .then(modifier)
            .padding(horizontal = dimensionResource(R.dimen.clip_card_pad_h), vertical = dimensionResource(R.dimen.clip_card_pad_v)),
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.clip_card_inner_gap)),
    ) { content() }
}

/**
 * Draws the footer: how the history is kept (off, or not in this app), the toggle for the app
 * being typed in, and Manage.
 */
@Composable
private fun Footer(retentionDays: Int, recording: Boolean, appExcluded: Boolean?, onExclude: (Boolean) -> Unit, onManage: () -> Unit) {
    val secondary = kbColor(R.color.kb_ink_secondary)
    Row(
        Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.clip_footer_height))
            .padding(horizontal = dimensionResource(R.dimen.clip_footer_pad)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.clip_card_gap)),
    ) {
        Image(
            painterResource(R.drawable.ic_clip_lock),
            contentDescription = null,
            modifier = Modifier.size(dimensionResource(R.dimen.clip_footer_icon)),
            colorFilter = ColorFilter.tint(secondary),
        )
        Text(
            when {
                !recording -> stringResource(R.string.clip_footer_off)
                appExcluded == true -> stringResource(R.string.clip_footer_app_off)
                else -> stringResource(R.string.clip_footer, pluralStringResource(R.plurals.clip_kept_days, retentionDays, retentionDays))
            },
            Modifier.weight(1f),
            fontSize = dimensionResource(R.dimen.clip_footer_text).asText(),
            color = secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (appExcluded != null && recording) {
            Box(
                Modifier
                    .height(dimensionResource(R.dimen.clip_tab_height))
                    .clip(RoundedCornerShape(dimensionResource(R.dimen.clip_tab_height) / 2))
                    .clickable { onExclude(!appExcluded) }
                    .padding(horizontal = dimensionResource(R.dimen.clip_manage_pad)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (appExcluded) R.string.clip_app_include else R.string.clip_app_exclude),
                    fontSize = dimensionResource(R.dimen.clip_manage_text).asText(), color = kbColor(R.color.kb_accent), maxLines = 1,
                )
            }
        }
        Box(
            Modifier
                .height(dimensionResource(R.dimen.clip_tab_height))
                .clip(RoundedCornerShape(dimensionResource(R.dimen.clip_tab_height) / 2))
                .clickable(onClick = onManage)
                .padding(horizontal = dimensionResource(R.dimen.clip_manage_pad)),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.clip_manage), fontSize = dimensionResource(R.dimen.clip_manage_text).asText(), color = kbColor(R.color.kb_accent))
        }
    }
}
