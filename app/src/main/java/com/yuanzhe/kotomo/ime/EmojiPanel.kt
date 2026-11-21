package com.yuanzhe.kotomo.ime

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuanzhe.kotomo.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the panel needs before it can draw: the emoji data, and the frequently used and recent
 * emoji as the panel opened.
 */
private class PanelData(val index: EmojiIndex, val frequent: List<String>, val shownRecents: List<String>)

/** The person's own parts of the panel (PLAN §10.6): favorites, then the combinations to offer. */
class EmojiLists(val favorites: List<String> = emptyList(), val combos: List<String> = EmojiExtras.COMBOS)

/**
 * One cell of the emoji grid: a section title (a [tab] title starts a section of the bottom bar,
 * drawn with [icon] or [glyph]), an empty section's hint, an emoji, or a wider text (a combination
 * or a kaomoji, [wide] cells to a row).
 */
private sealed interface Cell {
    data class Title(val label: Int, val tab: Boolean = true, val icon: Int? = null, val glyph: String? = null) : Cell
    data class Empty(val hint: Int) : Cell
    data class Emoji(val text: String, val toned: Boolean) : Cell
    data class Wide(val text: String, val perRow: Int) : Cell
}

/**
 * The open long-press card: the skin tones of [base] (or [base] alone), the favorite switch, and
 * the long-pressed cell's place in the grid, in px.
 */
private class Picker(val base: String, val choices: List<String>, val centerX: Int, val top: Int, val bottom: Int)

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
 * in one adaptive grid with a title per group, Frequently used, Recents and Favorites first,
 * Combinations and Kaomoji last (PLAN §10.6), and Search and the section tabs between ABC and
 * backspace at the bottom; long-pressing an emoji opens a card above it with its skin tones (an
 * emoji that has them is marked in its corner) and the favorite switch.
 *
 * Inputs: [onEmoji] commits one emoji (a base emoji or a chosen tone); [onBackspace] deletes
 * before the cursor; [onBack] returns to the keyboard; [recordRecents] adds sent emoji to Recents
 * and must be false in sensitive fields and incognito (passwords, IME_FLAG_NO_PERSONALIZED_LEARNING);
 * [memory] is the keyboard's memory, null before the first unlock (then Recents is neither read
 * nor shown); [onSearch] opens the emoji search over the keys (PLAN §8.6); [lists] holds the
 * favorites and combinations, [onFavorite] adds or removes a favorite (the caller stores it).
 * Side effects: reads the emoji asset on an I/O thread; records sent emoji in [memory] when
 * [recordRecents] is true.
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
    memory: KeyboardMemory? = null,
    onSearch: () -> Unit = {},
    lists: EmojiLists = EmojiLists(),
    onFavorite: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val data by produceState<PanelData?>(null) {
        value = withContext(Dispatchers.IO) {
            // Before the first unlock (Direct Boot) there is no memory: no Recents.
            val history = memory?.current?.emoji
            PanelData(EmojiIndex.load(context), history?.frequent().orEmpty(), history?.recent.orEmpty())
        }
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<Picker?>(null) }
    val loaded = data
    val cells = remember(loaded, lists) { loaded?.let { cells(it, lists) }.orEmpty() }
    val tabs = remember(cells) { cells.filterIsInstance<Cell.Title>().filter { it.tab } }
    val starts = remember(cells) { cells.indices.filter { (cells[it] as? Cell.Title)?.tab == true } }
    val current by remember(starts) {
        derivedStateOf { starts.indices.lastOrNull { starts[it] <= grid.firstVisibleItemIndex } ?: 0 }
    }
    val send = { emoji: String ->
        onEmoji(emoji)
        if (recordRecents) memory?.update { it.copy(emoji = it.emoji.sent(emoji)) }
    }
    Column(Modifier.fillMaxSize().background(kbColor(R.color.kb_background))) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            EmojiGrid(cells, grid, send) { i, cell ->
                val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i } ?: return@EmojiGrid
                val variants = loaded?.index?.variants(cell.text).orEmpty()
                picker = Picker(
                    cell.text,
                    listOf(cell.text) + variants,
                    info.offset.x + info.size.width / 2,
                    info.offset.y,
                    info.offset.y + info.size.height,
                )
            }
            picker?.let { p ->
                Box(Modifier.matchParentSize().pointerInput(p) { detectTapGestures { picker = null } })
                TonePicker(p, p.base in lists.favorites, Modifier.matchParentSize(), onFavorite = {
                    picker = null
                    onFavorite(p.base)
                }) {
                    picker = null
                    send(it)
                }
            }
        }
        BottomBar(
            tabs,
            current,
            onTab = { i ->
                picker = null
                starts.getOrNull(i)?.let { scope.launch { grid.scrollToItem(it) } }
            },
            onBackspace = onBackspace,
            onBack = onBack,
            onSearch = {
                picker = null
                onSearch()
            },
        )
    }
}

/**
 * Lays out the grid's cells: Frequently used (when any emoji was sent twice) and Recents (or its
 * hint) under the first tab, Favorites (or its hint), each group under its title, then
 * Combinations and Kaomoji (PLAN §10.6).
 */
private fun cells(data: PanelData, lists: EmojiLists): List<Cell> = buildList {
    val recent = R.drawable.ic_emoji_recent
    if (data.frequent.isNotEmpty()) {
        add(Cell.Title(R.string.emoji_frequent, icon = recent))
        data.frequent.forEach { add(Cell.Emoji(it, false)) }
    }
    add(Cell.Title(R.string.emoji_recent, tab = data.frequent.isEmpty(), icon = recent))
    if (data.shownRecents.isEmpty()) add(Cell.Empty(R.string.emoji_recent_empty))
    data.shownRecents.forEach { add(Cell.Emoji(it, false)) }
    add(Cell.Title(R.string.emoji_favorites, glyph = FAVORITE))
    if (lists.favorites.isEmpty()) add(Cell.Empty(R.string.emoji_favorites_empty))
    lists.favorites.forEach { add(Cell.Emoji(it, data.index.variants(it).isNotEmpty())) }
    for (g in data.index.groups) {
        add(Cell.Title(groupTitle(g.id), glyph = g.emoji.first()))
        g.emoji.forEach { add(Cell.Emoji(it, data.index.variants(it).isNotEmpty())) }
    }
    if (lists.combos.isNotEmpty()) {
        add(Cell.Title(R.string.emoji_combos, glyph = lists.combos.first().let { c -> c.substring(0, c.offsetByCodePoints(0, 1)) }))
        lists.combos.forEach { add(Cell.Wide(it, COMBOS_PER_ROW)) }
    }
    add(Cell.Title(R.string.emoji_kaomoji, glyph = KAOMOJI_TAB))
    EmojiExtras.KAOMOJI.forEach { add(Cell.Wide(it, KAOMOJI_PER_ROW)) }
}

/** The Favorites tab's glyph and the star of the long-press card. */
private const val FAVORITE = "⭐"
/** The Kaomoji tab's label, short enough for a tab (not one of the kaomoji, so it never stands for one). */
private const val KAOMOJI_TAB = "(^^)"
/** How many combinations and kaomoji share a row of the grid (at least one). */
private const val COMBOS_PER_ROW = 4
private const val KAOMOJI_PER_ROW = 3

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
    val wideText = dimensionResource(R.dimen.emoji_wide_text).asText()
    val wideColor = kbColor(R.color.kb_ink)
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
            span = { i ->
                GridItemSpan(
                    when (val c = cells[i]) {
                        is Cell.Emoji -> 1
                        is Cell.Wide -> maxOf(1, maxLineSpan / c.perRow)
                        else -> maxLineSpan
                    },
                )
            },
            contentType = { i -> cells[i]::class },
        ) { i ->
            when (val c = cells[i]) {
                is Cell.Title -> Text(
                    stringResource(c.label),
                    fontSize = titleSize,
                    color = titleColor,
                    modifier = Modifier.padding(horizontal = titlePad, vertical = titlePad / 2),
                )
                is Cell.Empty -> Text(
                    stringResource(c.hint),
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
                            onLongClick = { onLongPress(i, c) },
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
                is Cell.Wide -> Box(
                    Modifier
                        .height(cell)
                        .clip(RoundedCornerShape(radius))
                        .clickable { onEmoji(c.text) },
                    contentAlignment = Alignment.Center,
                ) { Text(c.text, fontSize = wideText, color = wideColor, maxLines = 1, softWrap = false) }
            }
        }
    }
}

/**
 * Draws the skin-tone choices of one emoji and the favorite switch in a card above the
 * long-pressed cell (below it when there is no room above), kept inside the panel.
 * Inputs: [favorite] whether the emoji is a favorite now; [modifier] must fill the area the
 * picker may use; [onPick] receives the chosen emoji; [onFavorite] switches the favorite.
 * Notes: drawn inside the panel rather than in a popup window, so it is never clipped by the
 * input method window.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TonePicker(p: Picker, favorite: Boolean, modifier: Modifier, onFavorite: () -> Unit, onPick: (String) -> Unit) {
    val cell = dimensionResource(R.dimen.emoji_cell)
    val glyph = dimensionResource(R.dimen.emoji_glyph).asText()
    val pad = dimensionResource(R.dimen.emoji_picker_pad)
    val columns = minOf(p.choices.size + 1, integerResource(R.integer.emoji_picker_columns))
    val star = stringResource(if (favorite) R.string.emoji_favorite_remove else R.string.emoji_favorite_add)
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
            Box(
                Modifier
                    .size(cell)
                    .clip(RoundedCornerShape(dimensionResource(R.dimen.emoji_radius)))
                    .background(colorResource(if (favorite) R.color.kb_accent_container else android.R.color.transparent))
                    .clickable(onClick = onFavorite)
                    .semantics { contentDescription = star },
                contentAlignment = Alignment.Center,
            ) { Text(if (favorite) FAVORITE else "☆", fontSize = glyph, color = kbColor(R.color.kb_ink)) }
        }
    }
}

/**
 * Draws the bottom row: ABC, Search, a tab per section (Recents, Favorites, each group,
 * Combinations, Kaomoji) and backspace; the tabs scroll when they do not fit.
 * Inputs: [tabs] the sections' titles; [current] is the section at the top of the grid; [onTab]
 * receives a section index.
 */
@Composable
private fun BottomBar(
    tabs: List<Cell.Title>,
    current: Int,
    onTab: (Int) -> Unit,
    onBackspace: () -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
) {
    val back = stringResource(R.string.emoji_back)
    val ink = kbColor(R.color.kb_ink)
    val key = dimensionResource(R.dimen.emoji_bar_key)
    val tab = dimensionResource(R.dimen.emoji_tab)
    val tabGlyph = dimensionResource(R.dimen.emoji_tab_glyph).asText()
    val tabText = dimensionResource(R.dimen.emoji_tab_text).asText()
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
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.emoji_tab_gap)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab(stringResource(R.string.emoji_search), false, tab, onSearch) {
                Image(
                    painterResource(R.drawable.ic_key_search),
                    contentDescription = null,
                    modifier = Modifier.size(dimensionResource(R.dimen.emoji_tab_icon)),
                    colorFilter = ColorFilter.tint(ink),
                )
            }
            tabs.forEachIndexed { i, t ->
                Tab(stringResource(t.label), current == i, tab, { onTab(i) }) {
                    if (t.icon != null) {
                        Image(
                            painterResource(t.icon),
                            contentDescription = null,
                            modifier = Modifier.size(dimensionResource(R.dimen.emoji_tab_icon)),
                            colorFilter = ColorFilter.tint(ink),
                        )
                    } else {
                        Text(t.glyph.orEmpty(), fontSize = if ((t.glyph?.length ?: 0) > 2) tabText else tabGlyph, color = ink, maxLines = 1, softWrap = false)
                    }
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
    Box(contentAlignment = Alignment.Center) {
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

/** Renders a candidate surface together with an optional tiny language badge (`中` / `EN` / `日`). */
@Composable
private fun CandidateLabel(text: String, tag: String, color: Color) {
    val badgeSize = with(LocalDensity.current) { dimensionResource(R.dimen.bar_lang_text).toSp() }
    Row(horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.bar_lang_gap))) {
        Text(text, Modifier.alignByBaseline(), fontSize = 18.sp, color = color)
        if (tag.isNotEmpty()) {
            Text(tag, Modifier.alignByBaseline(), fontSize = badgeSize, color = color.copy(alpha = 0.72f))
        }
    }
}

/**
 * Shows every candidate in the keyboard's place (the candidate bar's expand arrow).
 *
 * Inputs: [candidates] follows the bar; [onPick] commits candidate n; [onLongPick] shows
 * candidate n's details; [onBack] returns to the keys; [tags] supplies optional Mixed-mode
 * language badges (`中` / `EN` / `日`).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CandidatesPanel(
    candidates: androidx.compose.runtime.State<List<String>>,
    onPick: (Int) -> Unit,
    onBack: () -> Unit,
    onLongPick: (Int) -> Unit = {},
    tags: androidx.compose.runtime.State<List<String>>? = null,
) {
    val back = stringResource(R.string.candidates_collapse)
    val view = LocalView.current
    val shownTags = tags?.value.orEmpty()
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
                        .height(androidx.compose.ui.res.dimensionResource(R.dimen.panel_touch))
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorResource(if (i == 0) R.color.kb_accent_container else R.color.kb_key))
                        .combinedClickable(
                            onClick = { onPick(i) },
                            onLongClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                onLongPick(i)
                            },
                        )
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CandidateLabel(
                        text = text,
                        tag = shownTags.getOrNull(i).orEmpty(),
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


/** One rich candidate card shown in the focused section of [SegmentPanel]. */
data class SegmentCandidateCard(
    val text: String,
    val subtitle: String,
    val selected: Boolean = false,
)

/** One kana or original-letters pill shown under "Kana & original" in [SegmentPanel]. */
data class SegmentKanaOption(
    val text: String,
    val reading: String,
    val asTyped: Boolean = false,
)

/**
 * Describes what the segment panel shows: each segment's chip ([SegmentChipItem]), the segment
 * being edited (null for the whole sentence), the whole-sentence candidates or grouped segment
 * cards + kana/original options, and whether its two bottom actions are available: Shorter and
 * Longer for a Japanese conversion; in Mixed mode [shorterLabel] and [longerLabel] name Commit up
 * to here and Restore input instead (PLAN §5.6).
 */
data class SegmentView(
    val chips: List<SegmentChipItem>,
    val focus: Int?,
    val showWholeChip: Boolean,
    val subtitle: String,
    val groupTitle: String,
    val wholeCandidates: List<String>,
    val wholeTags: List<String> = emptyList(),
    val cards: List<SegmentCandidateCard> = emptyList(),
    val kanaRaw: List<SegmentKanaOption> = emptyList(),
    val shorter: Boolean,
    val longer: Boolean,
    val shorterLabel: Int = R.string.segment_shorter,
    val longerLabel: Int = R.string.segment_longer,
    val hasForms: Boolean = false,
)

/**
 * Shows a Japanese or Mixed conversion's segments (or a single word's readings) in the keyboard's
 * place: source-badged chips at the top, whole-sentence candidates when [SegmentView.focus] is
 * null, or grouped 2-column language/reading cards plus "Kana & original" pills when focused.
 *
 * Inputs: [view] follows the service; [onFocus] chooses a segment (null for the whole sentence);
 * [onPick] chooses candidate n of what is listed; [onPickKana] chooses kana/original pill n;
 * [onLongPick] shows candidate n's details; [onResize] runs the first (−1) or second (+1) action
 * of [SegmentView]; [onBack] returns to the keys.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SegmentPanel(
    view: androidx.compose.runtime.State<SegmentView?>,
    onFocus: (Int?) -> Unit,
    onPick: (Int) -> Unit,
    onPickKana: (Int) -> Unit,
    onResize: (Int) -> Unit,
    onBack: () -> Unit,
    onLongPick: (Int) -> Unit = {},
    onForms: () -> Unit = {},
) {
    val v = view.value ?: return
    val back = stringResource(R.string.candidates_collapse)
    val host = LocalView.current
    val bodyScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().background(kbColor(R.color.kb_background))) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (v.showWholeChip) {
                SegmentChip(stringResource(R.string.segment_whole), v.focus == null) { onFocus(null) }
            }
            v.chips.forEachIndexed { i, chip ->
                SegmentSourceChip(chip, v.focus == i) { onFocus(i) }
            }
        }
        if (v.subtitle.isNotEmpty()) {
            Text(
                v.subtitle,
                Modifier.padding(horizontal = 12.dp, vertical = 1.dp),
                fontSize = 11.sp,
                color = kbColor(R.color.kb_ink_secondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (v.focus == null) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(bodyScroll).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                v.wholeCandidates.forEachIndexed { i, text ->
                    Box(
                        Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(kbColor(if (i == 0) R.color.kb_accent_container else R.color.kb_key))
                            .combinedClickable(
                                onClick = { onPick(i) },
                                onLongClick = {
                                    host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    onLongPick(i)
                                },
                            )
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CandidateLabel(
                            text = text,
                            tag = v.wholeTags.getOrNull(i).orEmpty(),
                            color = kbColor(if (i == 0) R.color.kb_on_accent_container else R.color.kb_ink),
                        )
                    }
                }
            }
        } else {
            val indexed = v.cards.withIndex().toList()
            val primary = indexed.take(2)
            val overflow = indexed.drop(2)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(bodyScroll)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            ) {
                if (v.groupTitle.isNotEmpty() && primary.isNotEmpty()) {
                    Text(
                        v.groupTitle,
                        fontSize = 11.sp,
                        color = kbColor(R.color.kb_ink_secondary),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                if (primary.isNotEmpty()) {
                    SegmentCardGrid(primary, host, onPick, onLongPick)
                }
                if (v.kanaRaw.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.segment_group_kana_raw),
                        fontSize = 11.sp,
                        color = kbColor(R.color.kb_ink_secondary),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    val kanaRows = v.kanaRaw.withIndex().chunked(3)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (triple in kanaRows) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                for ((j, opt) in triple) {
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(30.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(kbColor(R.color.kb_key))
                                            .clickable { onPickKana(j) }
                                            .padding(horizontal = 6.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            opt.text,
                                            fontSize = 14.sp,
                                            color = kbColor(R.color.kb_ink),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                repeat(3 - triple.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                if (overflow.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    SegmentCardGrid(overflow, host, onPick, onLongPick)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(40.dp).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SegmentButton(stringResource(v.shorterLabel), v.shorter, Modifier.weight(1f)) { onResize(-1) }
            SegmentButton(stringResource(v.longerLabel), v.longer, Modifier.weight(1f)) { onResize(1) }
            if (v.hasForms) {
                SegmentButton(stringResource(R.string.candidate_forms), true, Modifier.weight(1f), onForms)
            }
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

/** Renders a 2-column grid of [cards] preserving each card's original index for [onPick]/[onLongPick]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentCardGrid(
    cards: List<IndexedValue<SegmentCandidateCard>>,
    host: android.view.View,
    onPick: (Int) -> Unit,
    onLongPick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (pair in cards.chunked(2)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                for ((i, card) in pair) {
                    val bg = kbColor(if (card.selected) R.color.kb_accent_container else R.color.kb_key)
                    val fg = kbColor(if (card.selected) R.color.kb_on_accent_container else R.color.kb_ink)
                    val sub = kbColor(if (card.selected) R.color.kb_on_accent_container else R.color.kb_ink_secondary)
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(bg)
                            .combinedClickable(
                                onClick = { onPick(i) },
                                onLongClick = {
                                    host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    onLongPick(i)
                                },
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            card.text,
                            fontSize = 15.sp,
                            fontWeight = if (card.selected) FontWeight.Medium else FontWeight.Normal,
                            color = fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (card.subtitle.isNotEmpty()) {
                            Text(
                                card.subtitle,
                                fontSize = 10.sp,
                                color = sub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Draws one segment chip, filled while it is the one listed. */
@Composable
private fun SegmentChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(kbColor(if (selected) R.color.kb_accent_container else R.color.kb_key))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = kbColor(if (selected) R.color.kb_on_accent_container else R.color.kb_ink), maxLines = 1)
    }
}

/** Draws a source-badged compact segment chip inside [SegmentPanel]. */
@Composable
private fun SegmentSourceChip(chip: SegmentChipItem, selected: Boolean, onClick: () -> Unit) {
    val (badgeText, badgeBgId, badgeFgId) = when (chip.source) {
        SegmentSource.PINYIN -> Triple(stringResource(R.string.seg_badge_pinyin), R.color.clip_tag_phrase, R.color.clip_on_tag_phrase)
        SegmentSource.ROMAJI -> Triple(stringResource(R.string.seg_badge_romaji), R.color.kb_accent_container, R.color.kb_on_accent_container)
        SegmentSource.KANA -> Triple(stringResource(R.string.seg_badge_kana), R.color.kb_accent_container, R.color.kb_on_accent_container)
        SegmentSource.ENGLISH -> Triple(stringResource(R.string.seg_badge_english), R.color.seg_tag_en, R.color.seg_on_tag_en)
    }
    val shape = RoundedCornerShape(14.dp)
    val borderMod = if (selected || chip.locked) {
        Modifier.border(BorderStroke(if (selected) 2.dp else 1.dp, kbColor(R.color.kb_accent)), shape)
    } else {
        Modifier
    }
    Row(
        Modifier
            .height(28.dp)
            .clip(shape)
            .background(kbColor(R.color.kb_key))
            .then(borderMod)
            .clickable(onClick = onClick)
            .padding(start = 3.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .height(18.dp)
                .widthIn(min = 18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(kbColor(badgeBgId))
                .padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(badgeText, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = kbColor(badgeFgId), maxLines = 1)
        }
        Text(chip.text, fontSize = 13.sp, color = kbColor(R.color.kb_ink), maxLines = 1)
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
        // Shrinks rather than cuts the label off at large font scales.
        Text(
            label, color = kbColor(if (enabled) R.color.kb_ink else R.color.kb_ink_secondary), maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp),
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 15.sp),
        )
    }
}
