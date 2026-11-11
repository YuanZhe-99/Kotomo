package com.yuanzhe.kotomo.ime

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.yuanzhe.kotomo.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the emoji the person sent from the panel, newest first, in app-private preferences.
 *
 * Notes: format 1 is the key [FORMAT] = 1 and [ITEMS] = the emoji joined by newlines; other keys
 * of the file are left alone. The file is excluded from Android backup (data_extraction_rules).
 * [add] is called only when the field allows learning (the behavior contract's sensitive fields).
 */
private class Recents(private val prefs: SharedPreferences) {
    /** The stored emoji, newest first, at most [MAX]. Notes: read on an I/O thread. */
    val items: MutableList<String> = prefs.getString(ITEMS, null).orEmpty()
        .split('\n').filter { it.isNotBlank() && it.length <= MAX_LENGTH }.distinct().take(MAX).toMutableList()

    /**
     * Moves an emoji to the front and saves the list.
     * Side effects: writes the preferences asynchronously (apply()).
     */
    fun add(emoji: String) {
        items.remove(emoji)
        items.add(0, emoji)
        while (items.size > MAX) items.removeAt(items.lastIndex)
        prefs.edit {
            putInt(FORMAT, 1)
            putString(ITEMS, items.joinToString("\n"))
        }
    }

    companion object {
        const val FILE = "emoji_recents"
        const val FORMAT = "format"
        const val ITEMS = "items"
        /** Four rows of a phone-width grid. */
        const val MAX = 32
        /** Longer than any emoji sequence; anything longer in the file is not an emoji. */
        const val MAX_LENGTH = 32
    }
}

/** What the panel needs before it can draw: the emoji data and the stored recents. */
private class PanelData(val index: EmojiIndex, val recents: Recents?, val shownRecents: List<String>)

/** One cell of the emoji grid: a section title, the empty-recents hint or an emoji. */
private sealed interface Cell {
    data class Title(val label: Int) : Cell
    data object Empty : Cell
    data class Emoji(val text: String, val toned: Boolean) : Cell
}

/** The open skin-tone picker: its choices and the long-pressed cell's place in the grid, in px. */
private class Picker(val choices: List<String>, val centerX: Int, val top: Int, val bottom: Int)

/** Maps an emoji group id of the asset to its title. */
private fun groupTitle(id: String): Int = when (id) {
    "smileys" -> R.string.emoji_smileys
    "people" -> R.string.emoji_people
    "animals" -> R.string.emoji_animals
    "food" -> R.string.emoji_food
    "travel" -> R.string.emoji_travel
    "activities" -> R.string.emoji_activities
    "objects" -> R.string.emoji_objects
    "symbols" -> R.string.emoji_symbols
    else -> R.string.emoji_flags
}

/**
 * Shows the emoji panel in the keyboard's place, after Gboard's: every emoji of the system font
 * in one adaptive grid with a title per group, Recents first, and the group tabs between ABC and
 * backspace at the bottom; long-pressing an emoji with skin tones (marked in its corner) opens a
 * tone picker above it.
 *
 * Inputs: [onEmoji] commits one emoji (a base emoji or a chosen tone); [onBackspace] deletes
 * before the cursor; [onBack] returns to the keyboard; [recordRecents] adds sent emoji to Recents
 * and must be false in sensitive fields and incognito (passwords, IME_FLAG_NO_PERSONALIZED_LEARNING);
 * [personal] false (before the first unlock) neither reads nor shows Recents.
 * Side effects: reads the emoji asset and the recents file on an I/O thread; writes the recents
 * file when [recordRecents] is true.
 * Notes: built with Compose inside the input method window (S1's Compose check); the first frame
 * is timed by the service. Recents shows the list as it was when the panel opened, so cells do
 * not move under the finger; what is sent now appears the next time. [recordRecents] defaults to
 * false so a caller that does not know the field never records.
 */
@Composable
fun EmojiPanel(
    onEmoji: (String) -> Unit,
    onBackspace: () -> Unit,
    onBack: () -> Unit,
    recordRecents: Boolean = false,
    personal: Boolean = true,
) {
    val context = LocalContext.current
    val data by produceState<PanelData?>(null) {
        value = withContext(Dispatchers.IO) {
            // Before the first unlock (Direct Boot) the recents file cannot be read: no Recents.
            val recents = if (personal) Recents(context.getSharedPreferences(Recents.FILE, Context.MODE_PRIVATE)) else null
            PanelData(EmojiIndex.load(context), recents, recents?.items?.toList().orEmpty())
        }
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<Picker?>(null) }
    val loaded = data
    val cells = remember(loaded) { loaded?.let(::cells).orEmpty() }
    val starts = remember(cells) { cells.indices.filter { cells[it] is Cell.Title } }
    val current by remember(starts) {
        derivedStateOf { starts.indices.lastOrNull { starts[it] <= grid.firstVisibleItemIndex } ?: 0 }
    }
    val send = { emoji: String ->
        onEmoji(emoji)
        if (recordRecents) loaded?.recents?.add(emoji)
    }
    Column(Modifier.fillMaxSize().background(kbColor(R.color.kb_background))) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            EmojiGrid(cells, grid, send) { i, cell ->
                val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i } ?: return@EmojiGrid
                val variants = loaded?.index?.variants(cell.text).orEmpty()
                if (variants.isEmpty()) return@EmojiGrid
                picker = Picker(
                    listOf(cell.text) + variants,
                    info.offset.x + info.size.width / 2,
                    info.offset.y,
                    info.offset.y + info.size.height,
                )
            }
            picker?.let { p ->
                Box(Modifier.matchParentSize().pointerInput(p) { detectTapGestures { picker = null } })
                TonePicker(p, Modifier.matchParentSize()) {
                    picker = null
                    send(it)
                }
            }
        }
        BottomBar(
            loaded?.index?.groups.orEmpty(),
            current,
            onTab = { i ->
                picker = null
                starts.getOrNull(i)?.let { scope.launch { grid.scrollToItem(it) } }
            },
            onBackspace = onBackspace,
            onBack = onBack,
        )
    }
}

/** Lays out the grid's cells: Recents (or its hint), then each group under its title. */
private fun cells(data: PanelData): List<Cell> = buildList {
    add(Cell.Title(R.string.emoji_recent))
    if (data.shownRecents.isEmpty()) add(Cell.Empty)
    data.shownRecents.forEach { add(Cell.Emoji(it, false)) }
    for (g in data.index.groups) {
        add(Cell.Title(groupTitle(g.id)))
        g.emoji.forEach { add(Cell.Emoji(it, data.index.variants(it).isNotEmpty())) }
    }
}

/**
 * Converts a dimension token to a text size: an sp token keeps its font scaling, a dp token (the
 * emoji glyphs) keeps its cell size at every font scale.
 */
@Composable
private fun Dp.asText(): TextUnit = with(LocalDensity.current) { toSp() }

/**
 * Draws the scrolling grid of emoji with full-width group titles.
 * Inputs: [onLongPress] receives the cell's index and emoji, for the tone picker.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiGrid(
    cells: List<Cell>,
    grid: LazyGridState,
    onEmoji: (String) -> Unit,
    onLongPress: (Int, Cell.Emoji) -> Unit,
) {
    val cell = dimensionResource(R.dimen.emoji_cell)
    val glyph = dimensionResource(R.dimen.emoji_glyph).asText()
    val radius = dimensionResource(R.dimen.emoji_radius)
    val mark = dimensionResource(R.dimen.emoji_tone_mark)
    val markColor = kbColor(R.color.kb_ink_secondary)
    val titleColor = kbColor(R.color.kb_ink_secondary)
    val titleSize = dimensionResource(R.dimen.emoji_header_text).asText()
    val titlePad = dimensionResource(R.dimen.emoji_header_pad)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cell),
        state = grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = dimensionResource(R.dimen.emoji_grid_pad_h),
            vertical = dimensionResource(R.dimen.emoji_grid_pad_v),
        ),
    ) {
        items(
            count = cells.size,
            span = { i -> GridItemSpan(if (cells[i] is Cell.Emoji) 1 else maxLineSpan) },
            contentType = { i -> cells[i]::class },
        ) { i ->
            when (val c = cells[i]) {
                is Cell.Title -> Text(
                    stringResource(c.label),
                    fontSize = titleSize,
                    color = titleColor,
                    modifier = Modifier.padding(horizontal = titlePad, vertical = titlePad / 2),
                )
                Cell.Empty -> Text(
                    stringResource(R.string.emoji_recent_empty),
                    fontSize = titleSize,
                    color = titleColor,
                    modifier = Modifier.padding(titlePad),
                )
                is Cell.Emoji -> Box(
                    Modifier
                        .height(cell)
                        .clip(RoundedCornerShape(radius))
                        .combinedClickable(
                            onClick = { onEmoji(c.text) },
                            onLongClick = if (c.toned) ({ onLongPress(i, c) }) else null,
                        )
                        .drawBehind {
                            if (!c.toned) return@drawBehind
                            val m = mark.toPx()
                            val inset = m / 2
                            drawPath(
                                Path().apply {
                                    moveTo(size.width - inset, size.height - inset - m)
                                    lineTo(size.width - inset, size.height - inset)
                                    lineTo(size.width - inset - m, size.height - inset)
                                    close()
                                },
                                markColor,
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(c.text, fontSize = glyph) }
            }
        }
    }
}

/**
 * Draws the skin-tone choices of one emoji in a card above the long-pressed cell (below it when
 * there is no room above), kept inside the panel.
 * Inputs: [modifier] must fill the area the picker may use; [onPick] receives the chosen emoji.
 * Notes: drawn inside the panel rather than in a popup window, so it is never clipped by the
 * input method window.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TonePicker(p: Picker, modifier: Modifier, onPick: (String) -> Unit) {
    val cell = dimensionResource(R.dimen.emoji_cell)
    val glyph = dimensionResource(R.dimen.emoji_glyph).asText()
    val pad = dimensionResource(R.dimen.emoji_picker_pad)
    val columns = minOf(p.choices.size, integerResource(R.integer.emoji_picker_columns))
    val label = stringResource(R.string.emoji_tones)
    Surface(
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val x = (p.centerX - placeable.width / 2).coerceIn(0, maxOf(0, constraints.maxWidth - placeable.width))
                    val above = p.top - placeable.height
                    val y = (if (above >= 0) above else p.bottom).coerceIn(0, maxOf(0, constraints.maxHeight - placeable.height))
                    placeable.place(x, y)
                }
            }
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(dimensionResource(R.dimen.emoji_picker_radius)),
        color = kbColor(R.color.kb_key),
        shadowElevation = dimensionResource(R.dimen.emoji_picker_elevation),
    ) {
        androidx.compose.foundation.layout.FlowRow(
            Modifier.width(cell * columns + pad * 2).padding(pad),
            maxItemsInEachRow = columns,
        ) {
            p.choices.forEach { e ->
                Box(
                    Modifier
                        .size(cell)
                        .clip(RoundedCornerShape(dimensionResource(R.dimen.emoji_radius)))
                        .clickable { onPick(e) },
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = glyph) }
            }
        }
    }
}

/**
 * Draws the bottom row: ABC, a tab per section (Recents, then each group) and backspace.
 * Inputs: [current] is the section at the top of the grid; [onTab] receives a section index.
 */
@Composable
private fun BottomBar(
    groups: List<EmojiIndex.Group>,
    current: Int,
    onTab: (Int) -> Unit,
    onBackspace: () -> Unit,
    onBack: () -> Unit,
) {
    val back = stringResource(R.string.emoji_back)
    val ink = kbColor(R.color.kb_ink)
    val key = dimensionResource(R.dimen.emoji_bar_key)
    val tab = dimensionResource(R.dimen.emoji_tab)
    val tabGlyph = dimensionResource(R.dimen.emoji_tab_glyph).asText()
    Row(
        Modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.emoji_bar_height))
            .padding(horizontal = dimensionResource(R.dimen.kb_side), vertical = dimensionResource(R.dimen.emoji_bar_pad_v)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.kb_key_gap)),
    ) {
        BarKey(Modifier.width(key).semantics { contentDescription = back }, onBack) {
            Text("ABC", fontSize = dimensionResource(R.dimen.kb_function_text).asText(), color = ink)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Tab(stringResource(R.string.emoji_recent), current == 0, tab, { onTab(0) }) {
                Image(
                    painterResource(R.drawable.ic_emoji_recent),
                    contentDescription = null,
                    modifier = Modifier.size(dimensionResource(R.dimen.emoji_tab_icon)),
                    colorFilter = ColorFilter.tint(ink),
                )
            }
            groups.forEachIndexed { i, g ->
                Tab(stringResource(groupTitle(g.id)), current == i + 1, tab, { onTab(i + 1) }) {
                    Text(g.emoji.first(), fontSize = tabGlyph)
                }
            }
        }
        BarKey(Modifier.width(key), onBackspace) {
            Image(
                painterResource(R.drawable.ic_key_backspace),
                contentDescription = null,
                colorFilter = ColorFilter.tint(ink),
            )
        }
    }
}

/** One section tab: highlighted while its section is at the top of the grid. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(
    label: String,
    selected: Boolean,
    size: Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.weight(1f, fill = false), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 2))
                .background(colorResource(if (selected) R.color.kb_accent_container else android.R.color.transparent))
                .clickable(onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** A function key of the panel's bottom row, in the keyboard's colors. */
@Composable
private fun BarKey(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(dimensionResource(R.dimen.kb_key_radius)))
            .background(kbColor(R.color.kb_key_function))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Shows every candidate in the keyboard's place (the candidate bar's expand arrow).
 *
 * Inputs: [candidates] follows the bar; [onPick] commits candidate n; [onBack] returns to the keys.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CandidatesPanel(
    candidates: androidx.compose.runtime.State<List<String>>,
    onPick: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val back = stringResource(R.string.candidates_collapse)
    Column(Modifier.fillMaxSize().background(kbColor(R.color.kb_background))) {
        androidx.compose.foundation.layout.FlowRow(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            candidates.value.forEachIndexed { i, text ->
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorResource(if (i == 0) R.color.kb_accent_container else R.color.kb_key))
                        .clickable { onPick(i) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text,
                        fontSize = 18.sp,
                        color = colorResource(if (i == 0) R.color.kb_on_accent_container else R.color.kb_ink),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp).padding(6.dp)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(kbColor(R.color.kb_key_function))
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = back },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(R.drawable.ic_bar_collapse),
                    contentDescription = null,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(kbColor(R.color.kb_ink)),
                )
            }
        }
    }
}


/**
 * Describes what the segment panel shows: each segment's text and reading, the segment being
 * edited (null for the whole sentence), the candidates listed, and whether it can shrink or grow.
 */
data class SegmentView(
    val texts: List<String>,
    val readings: List<String>,
    val focus: Int?,
    val candidates: List<String>,
    val shorter: Boolean,
    val longer: Boolean,
)

/**
 * Shows a Japanese conversion's segments (bunsetsu) in the keyboard's place (the candidate bar's
 * expand arrow in Japanese mode, PLAN §8.2): the whole sentence and each segment as chips, the
 * candidates of the chosen one, and Shorter, Longer and Back.
 *
 * Inputs: [view] follows the service; [onFocus] chooses a segment (null for the whole sentence);
 * [onPick] chooses candidate n of what is listed; [onResize] shrinks (−1) or grows (+1) the
 * chosen segment; [onBack] returns to the keys.
 * Notes: choosing a segment's candidate pins it and converts the rest again around it; the
 * segments before it keep their boundaries (PLAN §5.6).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SegmentPanel(
    view: androidx.compose.runtime.State<SegmentView?>,
    onFocus: (Int?) -> Unit,
    onPick: (Int) -> Unit,
    onResize: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val v = view.value ?: return
    val back = stringResource(R.string.candidates_collapse)
    Column(Modifier.fillMaxSize().background(kbColor(R.color.kb_background))) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SegmentChip(stringResource(R.string.segment_whole), v.focus == null) { onFocus(null) }
            v.texts.forEachIndexed { i, text -> SegmentChip(text, v.focus == i) { onFocus(i) } }
        }
        v.focus?.let { f ->
            Text(
                stringResource(R.string.segment_position, f + 1, v.texts.size, v.readings[f]),
                Modifier.padding(horizontal = 12.dp),
                fontSize = 13.sp,
                color = kbColor(R.color.kb_ink_secondary),
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            v.candidates.forEachIndexed { i, text ->
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorResource(if (i == 0) R.color.kb_accent_container else R.color.kb_key))
                        .clickable { onPick(i) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text, fontSize = 18.sp, color = colorResource(if (i == 0) R.color.kb_on_accent_container else R.color.kb_ink))
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SegmentButton(stringResource(R.string.segment_shorter), v.shorter, Modifier.weight(1f)) { onResize(-1) }
            SegmentButton(stringResource(R.string.segment_longer), v.longer, Modifier.weight(1f)) { onResize(1) }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(kbColor(R.color.kb_key_function))
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = back },
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_bar_collapse), contentDescription = null, colorFilter = ColorFilter.tint(kbColor(R.color.kb_ink)))
            }
        }
    }
}

/** Draws one segment chip, filled while it is the one listed. */
@Composable
private fun SegmentChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colorResource(if (selected) R.color.kb_accent_container else R.color.kb_key))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.sp, color = colorResource(if (selected) R.color.kb_on_accent_container else R.color.kb_ink))
    }
}

/** Draws Shorter or Longer; a disabled one is dimmed and ignores taps. */
@Composable
private fun SegmentButton(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(kbColor(R.color.kb_key_function))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, color = kbColor(if (enabled) R.color.kb_ink else R.color.kb_ink_secondary))
    }
}
