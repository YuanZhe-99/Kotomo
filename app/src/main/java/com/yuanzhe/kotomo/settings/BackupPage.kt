package com.yuanzhe.kotomo.settings

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.backup.AutoBackup
import com.yuanzhe.kotomo.backup.AutoBackupConfig
import com.yuanzhe.kotomo.backup.AutoBackupFormat
import com.yuanzhe.kotomo.backup.BackupContent
import com.yuanzhe.kotomo.backup.BackupException
import com.yuanzhe.kotomo.backup.BackupFile
import com.yuanzhe.kotomo.backup.BackupFormat
import com.yuanzhe.kotomo.backup.BackupModule
import com.yuanzhe.kotomo.backup.BackupProblem
import com.yuanzhe.kotomo.backup.Backups
import com.yuanzhe.kotomo.backup.ModulePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** A backup file read for restoring: its bytes until it is opened, then its content. */
private sealed interface Opened {
    /** A sealed backup waiting for its password. */
    data class Sealed(val bytes: ByteArray) : Opened
    /** A backup that was read, with the preview of its modules. */
    data class Ready(val content: BackupContent, val preview: List<ModulePreview>) : Opened
}

/**
 * Draws the Backup page (PLAN §11.6): a backup now (modules to include, an optional password),
 * restoring from a file with a preview (what each module holds, when it was made, a warning where
 * this device's data is newer) and the modules to restore, taking a restore back, and automatic
 * backups into a folder the person chose, with how often, how many are kept and the list of them.
 * Side effects: writes only the files the person picks or the folder they granted; a restore
 * replaces the chosen modules after keeping the current data for Undo.
 * Notes: a backup file is untrusted input ([BackupFormat.unpack] checks it); the clipboard is
 * never included.
 */
@Composable
internal fun BackupPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backups = remember { Backups(context) }
    var include by remember { mutableStateOf(backups.available()) }
    var encrypt by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<Opened?>(null) }
    var busy by remember { mutableStateOf(false) }
    var canUndo by remember { mutableStateOf(backups.canUndo()) }
    var config by remember { mutableStateOf(AutoBackup.config(context)) }
    var listed by remember { mutableIntStateOf(0) }
    var folderFiles by remember { mutableStateOf(emptyList<BackupFile>()) }
    val gap = dimensionResource(R.dimen.settings_card_gap)
    fun toast(id: Int) = Toast.makeText(context, id, Toast.LENGTH_SHORT).show()
    fun update(c: AutoBackupConfig) {
        config = c
        scope.launch(Dispatchers.IO) { AutoBackup.save(context, c) }
    }
    LaunchedEffect(config.folder, listed) {
        folderFiles = config.folder?.let { f -> withContext(Dispatchers.IO) { AutoBackup.list(context, Uri.parse(f)) } }.orEmpty()
    }

    suspend fun open(bytes: ByteArray, pass: CharArray?) {
        opened = try {
            val content = withContext(Dispatchers.Default) { BackupFormat.unpack(bytes, pass) }
            Opened.Ready(content, backups.preview(content))
        } catch (e: BackupException) {
            when (e.problem) {
                BackupProblem.NEEDS_PASSWORD -> Opened.Sealed(bytes)
                BackupProblem.WRONG_PASSWORD -> Opened.Sealed(bytes).also { toast(R.string.backup_wrong_password) }
                BackupProblem.NEWER -> null.also { toast(R.string.backup_newer) }
                BackupProblem.TOO_LARGE, BackupProblem.NOT_A_BACKUP -> null.also { toast(R.string.backup_not_a_backup) }
            }
        }
    }
    fun read(uri: Uri) = scope.launch {
        val bytes = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> readCapped(input, BackupFormat.MAX_BYTES) }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
        if (bytes == null) toast(R.string.backup_not_a_backup) else open(bytes, null)
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = BackupFormat.pack(backups.collect(include), if (encrypt) password.toCharArray() else null)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
                }.getOrDefault(false)
            }
            busy = false
            toast(if (ok) R.string.backup_done else R.string.backup_failed)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) read(uri) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        update(config.copy(folder = uri.toString(), intervalDays = config.intervalDays.takeIf { it > 0 } ?: 7))
    }

    Section(stringResource(R.string.backup_now)) {
        Inset { Note(stringResource(R.string.backup_now_note)) }
        BackupModule.entries.forEach { m ->
            val can = m in backups.available()
            CheckRow(stringResource(m.label), if (can) null else stringResource(R.string.backup_dictionary_locked), m in include && can, can) { on ->
                include = if (on) include + m else include - m
            }
        }
        CheckRow(stringResource(R.string.backup_encrypt), stringResource(R.string.backup_encrypt_desc), encrypt, true) { encrypt = it }
        Inset {
            if (encrypt) {
                OutlinedTextField(
                    password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.backup_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Note(stringResource(R.string.backup_password_note))
            }
            OutlinedButton(
                enabled = !busy && include.isNotEmpty() && (!encrypt || password.length >= PASSWORD_MIN),
                onClick = { save.launch(AutoBackupFormat.name(System.currentTimeMillis(), auto = false)) },
            ) { Text(stringResource(if (busy) R.string.backup_working else R.string.backup_save)) }
        }
    }

    Section(stringResource(R.string.backup_restore)) {
        Inset {
            Note(stringResource(R.string.backup_restore_note))
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.backup_restore_pick)) }
                if (canUndo) {
                    TextButton(onClick = {
                        scope.launch {
                            val ok = backups.undo()
                            canUndo = backups.canUndo()
                            toast(if (ok) R.string.backup_undone else R.string.backup_failed)
                        }
                    }) { Text(stringResource(R.string.backup_undo)) }
                }
            }
        }
    }

    Section(stringResource(R.string.backup_auto)) {
        Inset {
            Note(stringResource(R.string.backup_auto_note))
            OutlinedButton(onClick = { folder.launch(null) }) {
                Text(stringResource(if (config.folder == null) R.string.backup_auto_folder else R.string.backup_auto_folder_change))
            }
            if (config.folder != null) {
                Label(stringResource(R.string.backup_auto_interval))
                val labels = AutoBackupConfig.INTERVALS.map { stringResource(intervalLabel(it)) }
                Choice(labels, AutoBackupConfig.INTERVALS.indexOf(config.intervalDays).coerceAtLeast(0)) { i -> update(config.copy(intervalDays = AutoBackupConfig.INTERVALS[i])) }
                SliderRow(stringResource(R.string.backup_auto_keep), config.keep, AutoBackupConfig.KEEP_RANGE, 1) { v -> update(config.copy(keep = v)) }
                if (config.lastUtcMs > 0) Note(stringResource(R.string.backup_auto_last, DateUtils.formatDateTime(context, config.lastUtcMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)))
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { AutoBackup.run(context, force = true) }
                            busy = false
                            config = AutoBackup.config(context)
                            listed++
                            toast(if (ok) R.string.backup_done else R.string.backup_failed)
                        }
                    }) { Text(stringResource(R.string.backup_auto_now)) }
                    TextButton(onClick = { update(AutoBackupConfig(keep = config.keep)) }) { Text(stringResource(R.string.backup_auto_stop)) }
                }
                if (folderFiles.isEmpty()) Note(stringResource(R.string.backup_auto_none))
                folderFiles.forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(DateUtils.formatDateTime(context, f.modifiedUtcMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME), style = MaterialTheme.typography.bodyLarge)
                            Text(Formatter.formatShortFileSize(context, f.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { read(f.uri) }) { Text(stringResource(R.string.backup_restore_this)) }
                    }
                }
            }
        }
    }

    when (val o = opened) {
        null -> Unit
        is Opened.Sealed -> PasswordDialog(onDismiss = { opened = null }) { pass -> scope.launch { open(o.bytes, pass.toCharArray()) } }
        is Opened.Ready -> RestoreDialog(o, onDismiss = { opened = null }) { chosen ->
            opened = null
            busy = true
            scope.launch {
                val done = backups.restore(o.content, chosen)
                busy = false
                canUndo = backups.canUndo()
                toast(if (done == chosen) R.string.backup_restored else R.string.backup_restored_part)
            }
        }
    }
}

/**
 * The restore preview: when the backup was made and by which version, each module with its size
 * and a warning where this device's data is newer (such a module starts unticked).
 */
@Composable
private fun RestoreDialog(o: Opened.Ready, onDismiss: () -> Unit, onRestore: (Set<BackupModule>) -> Unit) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(o.preview.filter { !it.deviceIsNewer }.map { it.module }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.settings_card_gap))) {
                val m = o.content.manifest
                Text(stringResource(R.string.backup_preview_made, DateUtils.formatDateTime(context, m.createdUtcMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME), m.appVersion))
                o.preview.forEach { p ->
                    val detail = stringResource(R.string.backup_preview_module, p.inBackup.files, Formatter.formatShortFileSize(context, p.inBackup.bytes)) +
                        if (p.deviceIsNewer) "\n" + stringResource(R.string.backup_preview_newer) else ""
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(p.module in chosen, { on -> chosen = if (on) chosen + p.module else chosen - p.module })
                        Column {
                            Text(stringResource(p.module.label), style = MaterialTheme.typography.bodyLarge)
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (p.deviceIsNewer) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Note(stringResource(R.string.backup_preview_note))
            }
        },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onRestore(chosen) }) { Text(stringResource(R.string.backup_preview_restore)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_cancel)) } },
    )
}

/** Asks for a sealed backup's password. */
@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_password)) },
        text = {
            OutlinedTextField(
                pass, { pass = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = { TextButton(enabled = pass.isNotEmpty(), onClick = { onOpen(pass) }) { Text(stringResource(R.string.backup_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_cancel)) } },
    )
}

/** A row with a checkbox, a title and an optional description. */
@Composable
private fun CheckRow(title: String, desc: String?, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = dimensionResource(R.dimen.settings_card_pad) / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, change, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The label of a backup module. */
private val BackupModule.label: Int
    get() = when (this) {
        BackupModule.SETTINGS -> R.string.backup_module_settings
        BackupModule.THEMES -> R.string.backup_module_themes
        BackupModule.DICTIONARY -> R.string.backup_module_dictionary
        BackupModule.LEARNED -> R.string.backup_module_learned
        BackupModule.MEMORY -> R.string.backup_module_memory
    }

/** The label of an automatic backup interval. */
private fun intervalLabel(days: Int): Int = when (days) {
    0 -> R.string.backup_auto_off
    1 -> R.string.backup_auto_daily
    else -> R.string.backup_auto_weekly
}

/** The shortest password a backup is sealed with. */
private const val PASSWORD_MIN = 8
