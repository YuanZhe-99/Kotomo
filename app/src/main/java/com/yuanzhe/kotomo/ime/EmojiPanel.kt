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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** A fixed sample of emoji for spike S1; the CLDR-backed emoji system replaces it in P1. */
private val sample = (
    "😀😃😄😁😆😅😂🤣🙂😉😊😇🥰😍🤩😘😋😛🤔🤗🤭😐😴😷🤒🥳😎🤓😢😭😤😡" +
        "👍👎👏🙏💪👋✌️🤝❤️🧡💛💚💙💜🔥✨🎉🌸🌙☀️⭐🍣🍜☕🍰🚃🏠🐱🐶"
    ).let { s ->
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
    out
}

/**
 * Shows the emoji panel in the keyboard's place.
 *
 * Inputs: [onEmoji] commits one emoji; [onBack] returns to the keyboard.
 * Notes: built with Compose inside the input method window to answer S1's question whether
 * Compose panels start fast enough there; the first frame is timed by the service.
 */
@Composable
fun EmojiPanel(onEmoji: (String) -> Unit, onBack: () -> Unit) {
    val back = stringResource(R.string.emoji_back)
    Column(Modifier.fillMaxSize().background(colorResource(R.color.kb_background))) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(6.dp),
        ) {
            items(sample) { emoji ->
                Box(
                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { onEmoji(emoji) },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 26.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp).padding(6.dp)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colorResource(R.color.kb_key_function))
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = back },
                contentAlignment = Alignment.Center,
            ) { Text("ABC", fontSize = 15.sp, color = colorResource(R.color.kb_ink)) }
        }
    }
}
