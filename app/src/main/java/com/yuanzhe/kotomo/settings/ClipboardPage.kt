package com.yuanzhe.kotomo.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.ime.clipboard.Ago
import com.yuanzhe.kotomo.ime.clipboard.Clip
import com.yuanzhe.kotomo.ime.clipboard.ClipKind
import com.yuanzhe.kotomo.ime.clipboard.ClipboardHistory

/** One category the clipboard page filters by, and which items it keeps. */
internal enum class ClipFilter(@StringRes val label: Int, val keeps: (Clip) -> Boolean) {
    ALL(R.string.clip_filter_all, { true }),
    PINNED(R.string.clip_filter_pinned, { it.pinned }),
    PHRASES(R.string.clip_filter_phrases, { it.phrase }),
    CODES(R.string.clip_filter_codes, { it.kind == ClipKind.CODE }),
    LINKS(R.string.clip_filter_links, { it.kind == ClipKind.LINK }),
    PHONES(R.string.clip_filter_phones, { it.kind == ClipKind.PHONE }),
    ADDRESSES(R.string.clip_filter_addresses, { it.kind == ClipKind.ADDRESS }),
    TEXT(R.string.clip_filter_text, { it.kind == ClipKind.TEXT }),
    ;

    companion object {
        /**
         * Narrows [items] to the category and the search.
         * Returns: the items of [filter] whose text holds [query] (ignoring case and surrounding
         * spaces), in the history's order. Pure, unit-tested.
         */
        fun apply(items: List<Clip>, filter: ClipFilter, query: String): List<Clip> {
            val q = query.trim()
            return items.filter { filter.keeps(it) && (q.isEmpty() || it.text.contains(q, ignoreCase = true)) }
        }
    }
}

/**
 * Draws the clipboard history page (PLAN §10.7), reached from Privacy: search, the categories,
 * the items with their tag and age (tap to edit: text, pin, quick phrase, delete), selecting
 * several to delete them at once, adding a quick phrase, and the apps where nothing is kept.
 *
 * Notes: the items are the person's own clipboard history, read from the same encrypted history
 * the keyboard keeps ([ClipboardHistory]); every change goes through it. The search, category,
 * selection and open editor are saved with the activity, so they survive dark mode and rotation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClipboardPage(settings: Settings, store: SettingsStore) {
    val context = LocalContext.current
    val history = remember { ClipboardHistory.get(context) }
    val items by history.items.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ClipFilter.ALL) }
    var selected by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    val shown = ClipFilter.apply(items, filter, query)
    Section(null) {
        Inset {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.clip_search)) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_group_gap))) {
                ClipFilter.entries.forEach { f -> FilterChip(f == filter, { filter = f }, label = { Text(stringResource(f.label)) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_group_gap))) {
                if (selected.isEmpty()) {
                    OutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.clip_add_phrase)) }
                    OutlinedButton(enabled = shown.isNotEmpty(), onClick = { selected = shown.map { it.id } }) { Text(stringResource(R.string.clip_select_all)) }
                } else {
                    OutlinedButton(onClick = { history.delete(selected); selected = emptyList() }) {
                        Text(pluralStringResource(R.plurals.clip_delete_selected, selected.size, selected.size))
                    }
                    OutlinedButton(onClick = { selected = emptyList() }) { Text(stringResource(R.string.clip_cancel)) }
                }
            }
        }
        if (shown.isEmpty()) Inset { Note(stringResource(if (items.isEmpty()) R.string.clip_empty_all else R.string.clip_none_found)) }
        shown.forEach { clip ->
            ClipRow(clip, now, clip.id in selected, selecting = selected.isNotEmpty(),
                toggle = { on -> selected = if (on) selected + clip.id else selected - clip.id },
                open = { editing = clip.id })
        }
    }
    AppsSection(settings, store)
    editing?.let { id -> items.firstOrNull { it.id == id }?.let { ClipDialog(it, history) { editing = null } } ?: run { editing = null } }
    if (adding) PhraseDialog(history) { adding = false }
}

/** Draws one item: selection box, up to three lines of text, its tag, age and pin. */
@Composable
private fun ClipRow(clip: Clip, now: Long, checked: Boolean, selecting: Boolean, toggle: (Boolean) -> Unit, open: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = dimensionResource(R.dimen.settings_touch))
            .clickable { if (selecting) toggle(!checked) else open() }
            .padding(horizontal = dimensionResource(R.dimen.settings_card_pad), vertical = dimensionResource(R.dimen.settings_row_pad) / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, toggle)
        Column(Modifier.weight(1f)) {
            Text(clip.text, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Note(listOfNotNull(tagOf(clip)?.let { stringResource(it) }, age(clip, now), if (clip.pinned) stringResource(R.string.clip_pinned) else null).joinToString(" · "))
        }
    }
}

/** Names an item's category, or null for plain text. */
@StringRes
private fun tagOf(clip: Clip): Int? = when {
    clip.phrase -> R.string.clip_tag_phrase
    else -> when (clip.kind) {
        ClipKind.TEXT -> null
        ClipKind.LINK -> R.string.clip_tag_link
        ClipKind.ADDRESS -> R.string.clip_tag_address
        ClipKind.PHONE -> R.string.clip_tag_phone
        ClipKind.CODE -> R.string.clip_tag_code
    }
}

/** Words how long ago an item was copied (as the keyboard's panel does). */
@Composable
private fun age(clip: Clip, now: Long): String = when (val ago = Ago.of(now, clip.timeUtcMs)) {
    Ago.JustNow -> stringResource(R.string.clip_time_now)
    is Ago.Minutes -> pluralStringResource(R.plurals.clip_time_minutes, ago.n, ago.n)
    is Ago.Hours -> pluralStringResource(R.plurals.clip_time_hours, ago.n, ago.n)
    Ago.Yesterday -> stringResource(R.string.clip_time_yesterday)
    is Ago.Days -> pluralStringResource(R.plurals.clip_time_days, ago.n, ago.n)
}

/**
 * Edits one item: its text, its pin and whether it is a quick phrase.
 * Side effects: Save applies the changes, Delete removes the item; both close the dialog.
 */
@Composable
private fun ClipDialog(clip: Clip, history: ClipboardHistory, close: () -> Unit) {
    var text by rememberSaveable(clip.id) { mutableStateOf(clip.text) }
    var pinned by rememberSaveable(clip.id) { mutableStateOf(clip.pinned) }
    var phrase by rememberSaveable(clip.id) { mutableStateOf(clip.phrase) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.clip_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_card_gap))) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), maxLines = 8, label = { Text(stringResource(R.string.clip_edit_text)) })
                DialogSwitch(R.string.clip_edit_pinned, pinned) { pinned = it }
                DialogSwitch(R.string.clip_edit_phrase, phrase) { phrase = it }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && text.length <= ClipboardHistory.MAX_TEXT, onClick = {
                if (text != clip.text) history.edit(clip.id, text)
                if (pinned != clip.pinned) history.pin(clip.id, pinned)
                if (phrase != clip.phrase) history.setPhrase(clip.id, phrase)
                close()
            }) { Text(stringResource(R.string.clip_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { history.delete(clip.id); close() }) { Text(stringResource(R.string.clip_delete)) }
                TextButton(onClick = close) { Text(stringResource(R.string.clip_cancel)) }
            }
        },
    )
}

/** Adds a quick phrase, kept until the person deletes it. */
@Composable
private fun PhraseDialog(history: ClipboardHistory, close: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.clip_add_phrase)) },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), maxLines = 8, label = { Text(stringResource(R.string.clip_edit_text)) }) },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && text.length <= ClipboardHistory.MAX_TEXT, onClick = { history.addPhrase(text); close() }) {
                Text(stringResource(R.string.clip_save))
            }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.clip_cancel)) } },
    )
}

/** Draws a labelled switch inside a dialog. */
@Composable
private fun DialogSwitch(@StringRes label: Int, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, change)
    }
}

/**
 * Draws the apps in whose fields nothing is kept (`clipboard.excluded_apps`), each with Remove,
 * and how to add one (the keyboard's clipboard panel, while typing in that app).
 */
@Composable
private fun AppsSection(settings: Settings, store: SettingsStore) {
    val context = LocalContext.current
    val apps = settings.clipboard.excludedApps.sorted()
    Section(stringResource(R.string.clip_apps_section)) {
        Inset {
            Note(stringResource(R.string.clip_apps_desc))
            if (apps.isEmpty()) Note(stringResource(R.string.clip_apps_none))
        }
        apps.forEach { pkg ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = dimensionResource(R.dimen.settings_card_pad), vertical = dimensionResource(R.dimen.settings_row_pad) / 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val label = remember(pkg) {
                    runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
                }
                Column(Modifier.weight(1f)) {
                    Text(label ?: pkg, style = MaterialTheme.typography.bodyLarge)
                    if (label != null) Note(pkg)
                }
                TextButton(onClick = { store.set { it.copy(clipboard = it.clipboard.copy(excludedApps = it.clipboard.excludedApps - pkg)) } }) {
                    Text(stringResource(R.string.clip_apps_remove))
                }
            }
        }
    }
}
