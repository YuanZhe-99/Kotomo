package com.yuanzhe.kotomo.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuanzhe.kotomo.R

/** Splits a string into its user-perceived characters (one emoji each). */
private fun graphemes(s: String): List<String> {
    val out = mutableListOf<String>()
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(s)
    var start = it.first()
    var end = it.next()
    while (end != java.text.BreakIterator.DONE) {
        out += s.substring(start, end)
        start = end
        end = it.next()
    }
    return out
}

/**
 * A fixed sample of emoji in six groups for the P0 keyboard; the CLDR-backed emoji system with
 * search and every emoji replaces it in P1. Each group is shown under the tab of its first emoji.
 */
private val groups = listOf(
    "😀😃😄😁😆😅😂🤣🙂🙃😉😊😇🥰😍🤩😘😗😚😋😛😜🤪😝🤑🤗🤭🤫🤔🤐🤨😐😑😶😏😒🙄😬😌😔😪🤤😴😷🤒🤕🤢🤮🥵🥶🥴😵🤯🤠🥳😎🤓🧐😕😟🙁😮😯😲😳🥺😦😧😨😰😥😢😭😱😖😣😞😓😩😫🥱😤😡😠🤬",
    "👍👎👌🤌🤏✌️🤞🤟🤘🤙👈👉👆👇☝️✋🤚🖐️🖖👋👏🙌👐🤲🤝🙏✍️💪🦾👀👂👃🧠👶🧒👦👧🧑👨👩🧓👴👵",
    "❤️🧡💛💚💙💜🖤🤍🤎💔❣️💕💞💓💗💖💘💝💟💯💢💥💫💦💨🕳️💬💭💤✨🎉🎊🎈🎁🏆🥇",
    "🌸🌷🌹🌺🌻🌼🌱🌲🌳🌴🌵🍀🍁🍂🍃🌍🌙⭐🌟☀️⛅☁️🌧️⛈️❄️☃️🔥🌈🐶🐱🐭🐹🐰🦊🐻🐼🐨🐯🦁🐮🐷🐸🐵🐔🐧🐦🐤🦆🦉🐴🦄🐝🦋🐌🐞🐢🐍🐙🐠🐬🐳",
    "🍎🍊🍋🍌🍉🍇🍓🍒🍑🥭🍍🥝🍅🥑🥦🥕🌽🍞🥐🧀🍳🥓🍔🍟🍕🌭🥪🌮🍣🍱🍙🍚🍛🍜🍝🍢🍡🍧🍨🍦🍰🎂🍮🍭🍬🍫🍿🍩🍪☕🍵🧋🍺🍷🥤",
    "🚃🚄🚅🚆🚇🚉🚌🚕🚗🚲🛵✈️🚀🛳️⛵🏠🏡🏢🏫🏥🏦🏨⛩️🗼🗻🏯🎡🎢⚽🏀⚾🎾🏐🎮🎵🎶🎤🎧📱💻⌚📷💡📚✏️📝📌📎🔑🔒🎌🇨🇳🇯🇵🇺🇸",
).map(::graphemes)

/**
 * Shows the emoji panel in the keyboard's place: a grid that fits the width, the group tabs
 * between ABC and backspace at the bottom (as on Gboard).
 *
 * Inputs: [onEmoji] commits one emoji; [onBackspace] deletes before the cursor; [onBack] returns
 * to the keyboard.
 * Notes: built with Compose inside the input method window (S1's Compose check); the first frame
 * is timed by the service.
 */
@Composable
fun EmojiPanel(onEmoji: (String) -> Unit, onBackspace: () -> Unit, onBack: () -> Unit) {
    val back = stringResource(R.string.emoji_back)
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val starts = remember { groups.runningFold(0) { acc, g -> acc + g.size }.dropLast(1) }
    val all = remember { groups.flatten() }
    val current = groups.indices.lastOrNull { starts[it] <= grid.firstVisibleItemIndex } ?: 0
    Column(Modifier.fillMaxSize().background(colorResource(R.color.kb_background))) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(48.dp),
            state = grid,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        ) {
            items(all) { emoji ->
                Box(
                    Modifier.height(48.dp).clip(RoundedCornerShape(10.dp)).clickable { onEmoji(emoji) },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 28.sp) }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BarKey(Modifier.width(64.dp).semantics { contentDescription = back }, onBack) {
                Text("ABC", fontSize = 15.sp, color = colorResource(R.color.kb_ink))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                groups.forEachIndexed { i, g ->
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(colorResource(if (i == current) R.color.kb_accent_container else android.R.color.transparent))
                            .clickable { scope.launch { grid.scrollToItem(starts[i]) } },
                        contentAlignment = Alignment.Center,
                    ) { Text(g.first(), fontSize = 20.sp) }
                }
            }
            BarKey(Modifier.width(64.dp), onBackspace) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(R.drawable.ic_key_backspace),
                    contentDescription = null,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(colorResource(R.color.kb_ink)),
                )
            }
        }
    }
}

/** A function key of the panel's bottom row, in the keyboard's colors. */
@Composable
private fun BarKey(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(colorResource(R.color.kb_key_function))
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
    Column(Modifier.fillMaxSize().background(colorResource(R.color.kb_background))) {
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
                    .background(colorResource(R.color.kb_key_function))
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = back },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(R.drawable.ic_bar_collapse),
                    contentDescription = null,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(colorResource(R.color.kb_ink)),
                )
            }
        }
    }
}

