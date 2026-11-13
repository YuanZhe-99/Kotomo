package com.yuanzhe.kotomo.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.ime.EmojiExtras

/**
 * Shows the Emoji page (PLAN §10.6): how many favorites there are and Clear, the person's own
 * combinations, and their word → emoji mappings for the candidate bar.
 * Side effects: writes `emoji.favorites`, `emoji.combos` and `emoji.mappings`; a text field is
 * stored cleaned (as it is read back) when it loses focus.
 */
@Composable
internal fun EmojiPage(settings: Settings, store: SettingsStore) {
    val favorites = EmojiExtras.parseFavorites(settings.emojiFavorites)
    Section(stringResource(R.string.emoji_favorites)) {
        Inset {
            Text(stringResource(R.string.emoji_favorites_count, favorites.size) + if (favorites.isEmpty()) "" else "  " + favorites.take(12).joinToString(" "))
            OutlinedButton(onClick = { store.set { it.copy(emojiFavorites = "") } }, enabled = favorites.isNotEmpty()) {
                Text(stringResource(R.string.emoji_favorites_clear))
            }
            Note(stringResource(R.string.emoji_favorites_empty))
        }
    }
    Section(stringResource(R.string.emoji_combos)) {
        Inset {
            TextSetting(settings.emojiCombos, R.string.emoji_combos_field, R.string.emoji_combos_example, R.string.emoji_combos_note) { text ->
                store.set { it.copy(emojiCombos = EmojiExtras.parseCombos(text).joinToString("\n")) }
            }
        }
    }
    Section(stringResource(R.string.emoji_mappings_field)) {
        Inset {
            TextSetting(settings.emojiMappings, R.string.emoji_mappings_field, R.string.emoji_mappings_example, R.string.emoji_mappings_note) { text ->
                store.set { it.copy(emojiMappings = EmojiExtras.formatMappings(EmojiExtras.parseMappings(text))) }
            }
        }
    }
}

/** A multi-line text setting stored by [save] when the field loses focus, with a note below. */
@Composable
private fun TextSetting(value: String, label: Int, example: Int, note: Int, save: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        text, { text = it },
        Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && text != value) save(text) },
        label = { Text(stringResource(label)) },
        placeholder = { Text(stringResource(example)) },
        minLines = 2, maxLines = 8,
    )
    Note(stringResource(note))
}
