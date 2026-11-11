package com.yuanzhe.kotomo.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.dictionary.Dictionaries
import com.yuanzhe.kotomo.dictionary.DictionaryStore
import com.yuanzhe.kotomo.dictionary.Entry
import com.yuanzhe.kotomo.dictionary.Format
import com.yuanzhe.kotomo.dictionary.Formats
import com.yuanzhe.kotomo.dictionary.ImportException
import com.yuanzhe.kotomo.dictionary.ImportPreview
import com.yuanzhe.kotomo.dictionary.Lang
import com.yuanzhe.kotomo.dictionary.DictionaryDownloads
import com.yuanzhe.kotomo.dictionary.DictionarySource
import com.yuanzhe.kotomo.dictionary.DownloadProblem
import com.yuanzhe.kotomo.dictionary.InstalledPack
import com.yuanzhe.kotomo.dictionary.Progress
import com.yuanzhe.kotomo.dictionary.Sources
import com.yuanzhe.kotomo.dictionary.Problem
import com.yuanzhe.kotomo.dictionary.Readings
import com.yuanzhe.kotomo.dictionary.Source
import com.yuanzhe.kotomo.dictionary.Weights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Holds what the dictionaries page is in the middle of: the search, the word being edited, the
 * import waiting for confirmation (with what to do with conflicts), the export format, the source
 * waiting for the download notice and the last message.
 *
 * Notes: one instance per visit of the page, kept in [visit] while the activity is only rebuilt
 * (dark mode, rotation, PLAN §10.5 gap found in Q3) and dropped when the page is left.
 */
private class DictionaryUi {
    var query by mutableStateOf("")
    /** The word in the editor: null when closed; an entry with an empty text to add one. */
    var editing by mutableStateOf<Entry?>(null)
    /** Whether the editor was opened on an existing entry (which it replaces). */
    var editingExisting by mutableStateOf(false)
    var pending by mutableStateOf<PendingImport?>(null)
    /** Whether an import replaces the entries it conflicts with. */
    var replace by mutableStateOf(false)
    var exportFormat by mutableStateOf(Format.TSV)
    /** The source whose download waits for the notice to be accepted. */
    var notice by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    companion object {
        /** The current visit's state; survives the activity being rebuilt, not leaving the page. */
        var visit: DictionaryUi? = null
    }
}

/** A file read and checked, waiting for the person to confirm it. */
private data class PendingImport(
    val bytes: ByteArray,
    val format: Format,
    val lang: Lang,
    val generated: Boolean,
    val preview: ImportPreview?,
    val failure: Problem?,
)

/**
 * Draws the Dictionaries page (PLAN §10.5): the sources and their switches, the person's words
 * (search, add, edit, delete), import with a preview and confirmation, export, rollback, the
 * dictionaries that can be downloaded from their sources, and the LLM prompt.
 * Notes: the state lives in [DictionaryStore]; every change goes through it on the IO dispatcher.
 */
@Composable
internal fun DictionariesPage() {
    val context = LocalContext.current
    val store = remember { DictionaryStore.get(context) }
    val d by store.state.collectAsState()
    val ui = remember { DictionaryUi.visit ?: DictionaryUi().also { DictionaryUi.visit = it } }
    val activity = context as? android.app.Activity
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true) DictionaryUi.visit = null }
    }
    if (d.readOnly) Section(null) { Inset { Note(stringResource(R.string.dict_read_only)) } }
    ui.message?.let { Section(null) { Inset { Text(it, style = MaterialTheme.typography.bodyMedium) } } }
    SourcesSection(d, store)
    WordsSection(d, ui)
    ImportSection(d, store, ui)
    ExportSection(d, ui)
    DownloadsSection(d, store, ui)
    PromptSection()
    ui.editing?.let { EntryDialog(it, ui.editingExisting, store, ui) }
    ui.notice?.let { id -> Sources.of(id)?.let { NoticeDialog(it, store, ui) } }
}

/** Draws the three sources: built-in (always on), the person's own words, the downloaded packs. */
@Composable
private fun SourcesSection(d: Dictionaries, store: DictionaryStore) {
    val scope = rememberCoroutineScope()
    Section(stringResource(R.string.dict_sources)) {
        Column(Modifier.padding(horizontal = dimensionResource(R.dimen.settings_card_pad), vertical = dimensionResource(R.dimen.settings_row_pad))) {
            Text(stringResource(R.string.dict_builtin), style = MaterialTheme.typography.bodyLarge)
            Note(stringResource(R.string.dict_builtin_desc))
        }
        SwitchRow(R.string.dict_custom, R.string.dict_custom_desc, d.customEnabled) { on -> scope.launch { store.setSources(custom = on) } }
        SwitchRow(R.string.dict_downloads, R.string.dict_downloads_desc, d.downloadsEnabled) { on -> scope.launch { store.setSources(downloads = on) } }
    }
}

/** Draws the person's words: the count, the search, the matching words and Add. */
@Composable
private fun WordsSection(d: Dictionaries, ui: DictionaryUi) {
    Section(stringResource(R.string.dict_words)) {
        Inset {
            Note(pluralStringResource(R.plurals.dict_count, d.entries.size, d.entries.size))
            OutlinedTextField(ui.query, { ui.query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.dict_search)) })
        }
        val q = ui.query.trim()
        val matches = d.entries.filter { q.isEmpty() || q in it.text || (it.reading?.replace(" ", "")?.contains(q.replace(" ", "")) == true) }
        if (d.entries.isEmpty()) Inset { Note(stringResource(R.string.dict_none)) }
        matches.take(SHOWN).forEach { e -> EntryRow(e) { ui.editing = e; ui.editingExisting = true } }
        if (matches.size > SHOWN) Inset { Note(pluralStringResource(R.plurals.dict_more, matches.size - SHOWN, matches.size - SHOWN)) }
        Inset {
            OutlinedButton(enabled = !d.readOnly, onClick = {
                ui.editing = Entry(Lang.ZH, "", null, Weights.DEFAULT, Source.MANUAL)
                ui.editingExisting = false
            }) { Text(stringResource(R.string.dict_add)) }
        }
    }
}

/** Draws one word: the word and its reading, its language and source, "not used yet" when so. */
@Composable
private fun EntryRow(e: Entry, open: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = dimensionResource(R.dimen.settings_touch)).clickable(onClick = open)
            .padding(horizontal = dimensionResource(R.dimen.settings_card_pad), vertical = dimensionResource(R.dimen.settings_row_pad) / 2),
    ) {
        Text(if (e.reading == null) e.text else "${e.text}  ${e.reading}", style = MaterialTheme.typography.bodyLarge)
        val details = listOfNotNull(stringResource(e.lang.label), stringResource(e.source.label), if (e.used) null else stringResource(R.string.dict_not_used))
        Note(details.joinToString(" · "))
    }
}

/**
 * Edits one word: language, word, reading and priority, checked as they are typed.
 * Side effects: Save stores it (replacing the one opened), Delete removes it.
 */
@Composable
private fun EntryDialog(start: Entry, existing: Boolean, store: DictionaryStore, ui: DictionaryUi) {
    val scope = rememberCoroutineScope()
    // Saved with the activity's state, so an edit in progress survives dark mode and rotation.
    var lang by rememberSaveable(start) { mutableStateOf(start.lang) }
    var text by rememberSaveable(start) { mutableStateOf(start.text) }
    var reading by rememberSaveable(start) { mutableStateOf(start.reading.orEmpty()) }
    var weight by rememberSaveable(start) { mutableFloatStateOf(start.weight.toFloat()) }
    var exists by rememberSaveable(start) { mutableStateOf(false) }
    val (checkedText, textProblem) = Readings.text(text)
    val (checkedReading, readingProblem) = if (checkedText == null) null to null else Readings.reading(lang, checkedText, reading)
    val problem = if (text.isBlank()) null else textProblem ?: readingProblem
    val close = { ui.editing = null }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(if (existing) R.string.dict_edit_title else R.string.dict_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_card_gap))) {
                Choice(Lang.entries.map { stringResource(it.label) }, lang.ordinal) { lang = Lang.entries[it] }
                OutlinedTextField(text, { text = it; exists = false }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.dict_text)) })
                if (lang != Lang.EN) {
                    OutlinedTextField(
                        reading, { reading = it; exists = false }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.dict_reading)) },
                        supportingText = { Text(stringResource(if (lang == Lang.ZH) R.string.dict_reading_hint_zh else R.string.dict_reading_hint_ja)) },
                    )
                }
                Label(stringResource(R.string.dict_weight, weight.roundToInt()))
                Slider(weight, { weight = it }, valueRange = 0f..Weights.MAX.toFloat(), steps = Weights.MAX / WEIGHT_STEP - 1)
                if (lang != Lang.ZH) Note(stringResource(R.string.dict_not_used_note))
                problem?.let { Text(stringResource(it.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (exists) Text(stringResource(R.string.dict_exists), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = checkedText != null && problem == null, onClick = {
                val entry = Entry(lang, checkedText!!, checkedReading, weight.roundToInt(), Source.MANUAL, if (existing) start.extra else emptyMap())
                scope.launch { if (store.put(entry, if (existing) start else null)) close() else exists = true }
            }) { Text(stringResource(R.string.dict_save)) }
        },
        dismissButton = {
            Row {
                if (existing) TextButton(onClick = { scope.launch { store.delete(start); close() } }) { Text(stringResource(R.string.dict_delete)) }
                TextButton(onClick = close) { Text(stringResource(R.string.dict_cancel)) }
            }
        },
    )
}

/**
 * Draws the import: pick a file, then its preview (counts, the first refused lines) with the
 * language for lines that name none, the LLM switch, what to do with words already there, and
 * Add or Cancel. Nothing is stored before Add.
 */
@Composable
private fun ImportSection(d: Dictionaries, store: DictionaryStore, ui: DictionaryUi) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val added = stringResource(R.string.dict_import_done)
    val unknown = stringResource(R.string.dict_unknown_format)
    val tooBig = stringResource(R.string.dict_too_big)
    // Reads the chosen file off the main thread and checks it; the preview waits for confirmation.
    fun check(p: PendingImport) = scope.launch {
        val checked = withContext(Dispatchers.Default) {
            try {
                p.copy(preview = Formats.preview(Formats.read(p.bytes, p.format, p.lang, p.generated), d.entries), failure = null)
            } catch (e: ImportException) {
                p.copy(preview = null, failure = e.problem)
            }
        }
        ui.pending = checked
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (name, bytes) = withContext(Dispatchers.IO) { readDocument(context, uri) }
            val format = Format.of(name)
            when {
                bytes == null -> ui.message = tooBig
                format == null -> ui.message = unknown
                else -> {
                    ui.message = null
                    check(PendingImport(bytes, format, Lang.ZH, generated = format == Format.JSON, preview = null, failure = null))
                }
            }
        }
    }
    Section(stringResource(R.string.dict_import)) {
        Inset {
            Note(stringResource(R.string.dict_import_note))
            OutlinedButton(enabled = !d.readOnly, onClick = { pick.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.dict_import_file)) }
        }
        val p = ui.pending ?: return@Section
        Inset {
            if (p.format != Format.RIME) {
                Label(stringResource(R.string.dict_import_language))
                Choice(Lang.entries.map { stringResource(it.label) }, p.lang.ordinal) { check(p.copy(lang = Lang.entries[it])) }
            }
        }
        SwitchRow(R.string.dict_import_llm, R.string.dict_import_llm_desc, p.generated) { on -> check(p.copy(generated = on)) }
        Inset {
            p.failure?.let { Text(stringResource(R.string.dict_import_failed, stringResource(it.message)), color = MaterialTheme.colorScheme.error) }
            val preview = p.preview
            if (preview != null) {
                Text(pluralStringResource(R.plurals.dict_preview, preview.entries.size, preview.entries.size), style = MaterialTheme.typography.bodyLarge)
                if (preview.refused > 0) Note(pluralStringResource(R.plurals.dict_preview_refused, preview.refused, preview.refused))
                preview.firstErrors.forEach { Note(stringResource(R.string.dict_line_error, it.line, stringResource(it.problem.message))) }
                if (preview.duplicates > 0) Note(pluralStringResource(R.plurals.dict_preview_duplicates, preview.duplicates, preview.duplicates))
                if (preview.conflicts > 0) {
                    Note(pluralStringResource(R.plurals.dict_preview_conflicts, preview.conflicts, preview.conflicts))
                    Choice(listOf(stringResource(R.string.dict_conflict_keep), stringResource(R.string.dict_conflict_replace)), if (ui.replace) 1 else 0) { ui.replace = it == 1 }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_group_gap))) {
                Button(enabled = preview != null && preview.entries.isNotEmpty(), onClick = {
                    scope.launch {
                        if (store.import(preview!!, ui.replace)) ui.message = added
                        ui.pending = null
                        ui.replace = false
                    }
                }) { Text(stringResource(R.string.dict_confirm)) }
                OutlinedButton(onClick = { ui.pending = null }) { Text(stringResource(R.string.dict_cancel)) }
            }
        }
    }
}

/**
 * Reads a picked document.
 * Returns: its display name and bytes, or null bytes when it is larger than [MAX_IMPORT] (the
 * file is another app's data: its size is checked while reading, not trusted).
 */
private fun readDocument(context: Context, uri: Uri): Pair<String?, ByteArray?> {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_IMPORT) return@use null
            }
            out.toByteArray()
        }
    }.getOrNull()
    return name to bytes
}

/** Draws the export buttons (one per format) and the rollback of the last change. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportSection(d: Dictionaries, ui: DictionaryUi) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { DictionaryStore.get(context) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = Formats.write(d.entries, ui.exportFormat)
        scope.launch(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } } }
    }
    Section(stringResource(R.string.dict_export)) {
        Inset {
            Note(stringResource(R.string.dict_export_note))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_group_gap))) {
                Format.entries.forEach { f ->
                    OutlinedButton(enabled = d.entries.isNotEmpty(), onClick = {
                        ui.exportFormat = f
                        save.launch("kotomo-dictionary.${f.extension}")
                    }) { Text(f.extension.uppercase().removeSuffix(".YAML")) }
                }
            }
            OutlinedButton(enabled = d.canRollBack && !d.readOnly, onClick = { scope.launch { store.rollBack() } }) { Text(stringResource(R.string.dict_rollback)) }
            Note(stringResource(R.string.dict_rollback_desc))
        }
    }
}

/**
 * Draws the dictionaries that can be downloaded (PLAN §10.5, ADR-011): for each source its title,
 * license, authors and hosts, whether a published checksum protects it, and Download (or Update,
 * the previous version kept); while it runs, its progress and Cancel; once installed, its switch,
 * the previous version and Remove.
 * Notes: downloads run in [DictionaryDownloads], outside the page, so they survive the page being
 * rebuilt; the first one waits for the notice ([NoticeDialog]).
 */
@Composable
private fun DownloadsSection(d: Dictionaries, store: DictionaryStore, ui: DictionaryUi) {
    val context = LocalContext.current
    val tasks by DictionaryDownloads.tasks.collectAsState()
    Section(stringResource(R.string.dict_online)) {
        Inset { Note(stringResource(R.string.dict_online_note)) }
        Sources.ALL.forEach { source ->
            val installed = d.packs.firstOrNull { it.meta.id == source.id }
            SourceRow(source, installed, tasks[source.id], store) {
                if (d.onlineAccepted >= NOTICE_VERSION) DictionaryDownloads.start(context, source) else ui.notice = source.id
            }
        }
    }
}

/** Draws one downloadable source: what it is, its state, and its actions (wrapping on narrow screens). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceRow(source: DictionarySource, installed: InstalledPack?, task: DictionaryDownloads.Task?, store: DictionaryStore, download: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val language = stringResource(R.string.dict_title_language)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = dimensionResource(R.dimen.settings_card_pad), vertical = dimensionResource(R.dimen.settings_row_pad)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.title(language), style = MaterialTheme.typography.bodyLarge)
            Note(stringResource(R.string.dict_source_from, source.hosts.joinToString(", "), source.license, source.attribution))
            Note(stringResource(if (source.checked) R.string.dict_source_checked else R.string.dict_source_unchecked))
            if (source.lang != Lang.ZH) Note(stringResource(R.string.dict_not_used_note))
            installed?.let { Note(stringResource(R.string.dict_pack_details, it.meta.version, it.count, it.meta.license, it.meta.attribution)) }
            when (task) {
                is DictionaryDownloads.Task.Running -> Note(progress(context, task.progress))
                is DictionaryDownloads.Task.Done -> Note(pluralStringResource(R.plurals.dict_download_done, task.installed.count, task.installed.count))
                is DictionaryDownloads.Task.Failed -> Text(stringResource(R.string.dict_download_failed, stringResource(task.problem.message)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                DictionaryDownloads.Task.Cancelled -> Note(stringResource(R.string.dict_download_cancelled))
                null -> {}
            }
            FlowRow {
                if (task is DictionaryDownloads.Task.Running) {
                    TextButton(onClick = { DictionaryDownloads.cancel(source.id) }) { Text(stringResource(R.string.dict_download_cancel)) }
                } else {
                    TextButton(onClick = { DictionaryDownloads.clear(source.id); download() }) {
                        Text(stringResource(if (installed == null) R.string.dict_download else R.string.dict_update))
                    }
                }
                if (installed != null && task !is DictionaryDownloads.Task.Running) {
                    if (installed.hasPrevious) TextButton(onClick = { scope.launch { store.rollBackPack(source.id) } }) { Text(stringResource(R.string.dict_pack_rollback)) }
                    TextButton(onClick = { scope.launch { store.uninstall(source.id) } }) { Text(stringResource(R.string.dict_pack_remove)) }
                }
            }
        }
        if (installed != null) Switch(installed.enabled, { on -> scope.launch { store.setPack(source.id, on) } })
    }
}

/** Says where a running download is. */
@Composable
private fun progress(context: Context, p: Progress): String = when (p.step) {
    Progress.Step.DOWNLOAD -> {
        val done = android.text.format.Formatter.formatShortFileSize(context, p.done)
        val amount = if (p.total > 0) "$done / ${android.text.format.Formatter.formatShortFileSize(context, p.total)}" else done
        stringResource(R.string.dict_progress_download, maxOf(p.file, 1), maxOf(p.files, 1), amount)
    }
    Progress.Step.CONVERT -> stringResource(R.string.dict_progress_convert)
    Progress.Step.STORE -> stringResource(R.string.dict_progress_store)
}

/**
 * Shows the notice before Kotomo's first download (PLAN §14 "联网"): the hosts every offered
 * source is fetched from, that only dictionary files are downloaded and nothing typed is sent.
 * Side effects: Allow records [NOTICE_VERSION] in the store and starts the waiting download; Not
 * now connects to nothing.
 */
@Composable
private fun NoticeDialog(source: DictionarySource, store: DictionaryStore, ui: DictionaryUi) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hosts = Sources.ALL.flatMap { it.hosts }.distinct().joinToString(", ")
    AlertDialog(
        onDismissRequest = { ui.notice = null },
        title = { Text(stringResource(R.string.dict_notice_title)) },
        text = { Text(stringResource(R.string.dict_notice_body, hosts)) },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    if (store.acceptOnline(NOTICE_VERSION)) DictionaryDownloads.start(context, source)
                    ui.notice = null
                }
            }) { Text(stringResource(R.string.dict_notice_accept)) }
        },
        dismissButton = { TextButton(onClick = { ui.notice = null }) { Text(stringResource(R.string.dict_notice_decline)) } },
    )
}

/** Draws the LLM prompt (PLAN appendix B, packed from lexicon/ at build time): show, copy, and why its words rank low. */
@Composable
private fun PromptSection() {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(false) }
    val prompt = remember { runCatching { context.assets.open(PROMPT_ASSET).bufferedReader().use { it.readText() } }.getOrNull() }
    val copied = stringResource(R.string.dict_llm_copied)
    var note by remember { mutableStateOf<String?>(null) }
    Section(stringResource(R.string.dict_llm)) {
        Inset {
            Note(stringResource(R.string.dict_llm_note))
            if (prompt != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_group_gap))) {
                    OutlinedButton(onClick = { shown = !shown }) { Text(stringResource(if (shown) R.string.dict_llm_hide else R.string.dict_llm_show)) }
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, prompt))
                        note = copied
                    }) { Text(stringResource(R.string.dict_llm_copy)) }
                }
                note?.let { Note(it) }
                if (shown) Text(prompt, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Names an entry's language in the settings. */
@get:StringRes
private val Lang.label get() = when (this) {
    Lang.ZH -> R.string.mode_chinese
    Lang.JA -> R.string.mode_japanese
    Lang.EN -> R.string.mode_english
}

/** Names where an entry came from. */
@get:StringRes
private val Source.label get() = when (this) {
    Source.MANUAL -> R.string.dict_source_manual
    Source.IMPORT -> R.string.dict_source_import
    Source.GENERATED -> R.string.dict_source_generated
    Source.DOWNLOAD -> R.string.dict_source_download
}

/** Says why a download was not installed. */
@get:StringRes
private val DownloadProblem.message get() = when (this) {
    DownloadProblem.INSECURE -> R.string.dict_dl_insecure
    DownloadProblem.NETWORK -> R.string.dict_dl_network
    DownloadProblem.HTTP -> R.string.dict_dl_http
    DownloadProblem.SIZE -> R.string.dict_dl_size
    DownloadProblem.HASH -> R.string.dict_dl_hash
    DownloadProblem.UNPACK -> R.string.dict_dl_unpack
    DownloadProblem.EMPTY -> R.string.dict_dl_empty
    DownloadProblem.STORAGE -> R.string.dict_dl_storage
}

/** Says why a line or a file was refused. */
@get:StringRes
private val Problem.message get() = when (this) {
    Problem.ENCODING -> R.string.dict_problem_encoding
    Problem.SYNTAX -> R.string.dict_problem_syntax
    Problem.NEWER_FORMAT -> R.string.dict_problem_newer
    Problem.COLUMNS -> R.string.dict_problem_columns
    Problem.LANGUAGE -> R.string.dict_problem_language
    Problem.EMPTY_TEXT -> R.string.dict_problem_empty_text
    Problem.TEXT_TOO_LONG -> R.string.dict_problem_text_long
    Problem.TEXT_CHARACTERS -> R.string.dict_problem_text_chars
    Problem.NO_READING -> R.string.dict_problem_no_reading
    Problem.READING -> R.string.dict_problem_reading
    Problem.READING_COUNT -> R.string.dict_problem_reading_count
    Problem.WEIGHT -> R.string.dict_problem_weight
}

/** The words listed at once; a search narrows longer lists. */
private const val SHOWN = 50
private const val WEIGHT_STEP = 10
/** The largest file the import reads. */
private const val MAX_IMPORT = 16 * 1024 * 1024
/**
 * The version of the download notice; raised when what it says changes (another host, another
 * kind of data), so the person is asked again.
 */
private const val NOTICE_VERSION = 1
/** The prompt's asset name (app/build.gradle.kts, task llmPrompt). */
private const val PROMPT_ASSET = "llm-dictionary-prompt.txt"
