package com.yuanzhe.kotomo.settings

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.yuanzhe.kotomo.backup.BackupModule
import com.yuanzhe.kotomo.backup.Sync
import com.yuanzhe.kotomo.backup.SyncConfig
import com.yuanzhe.kotomo.backup.SyncException
import com.yuanzhe.kotomo.backup.SyncKind
import com.yuanzhe.kotomo.backup.SyncProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Draws the sync sections of the Backup page (PLAN §11.7): off by default; a folder the person
 * chose or a WebDAV folder (HTTPS, a notice naming the host before the first connection), what is
 * synced (the clipboard never; history data only when ticked), Sync now, the automatic daily sync,
 * and each module in conflict with the two ways to settle it.
 * Side effects: writes `files/sync/`; connects only after the host was confirmed and only when
 * the person taps Sync now or turned on the automatic sync.
 */
@Composable
internal fun SyncSection(labelOf: (BackupModule) -> Int) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(Sync.config(context)) }
    var state by remember { mutableStateOf(Sync.state(context)) }
    var url by remember { mutableStateOf(config.url.orEmpty()) }
    var user by remember { mutableStateOf(config.user) }
    var password by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val gap = dimensionResource(R.dimen.settings_card_gap)
    fun save(c: SyncConfig) {
        config = c
        scope.launch(Dispatchers.IO) { Sync.saveConfig(context, c) }
    }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun syncNow(resolve: Map<BackupModule, Boolean> = emptyMap()) {
        busy = true
        scope.launch {
            val message = try {
                val r = withContext(Dispatchers.IO) { Sync.run(context, resolve) }
                resources.getString(R.string.sync_done, r.uploaded.size, r.downloaded.size, r.conflicts.size)
            } catch (e: SyncException) {
                resources.getString(e.problem.message)
            }
            busy = false
            state = Sync.state(context)
            toast(message)
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        save(config.copy(kind = SyncKind.FOLDER, folder = uri.toString()))
    }

    Section(stringResource(R.string.sync_title)) {
        Inset {
            Note(stringResource(R.string.sync_note))
            val kinds = listOf(SyncKind.OFF, SyncKind.FOLDER, SyncKind.WEBDAV)
            Choice(kinds.map { stringResource(it.label) }, kinds.indexOf(config.kind)) { i ->
                val k = kinds[i]
                if (k == SyncKind.OFF) Sync.forgetPassword(context)
                save(config.copy(kind = k, auto = if (k == SyncKind.OFF) false else config.auto))
            }
            when (config.kind) {
                SyncKind.OFF -> Unit
                SyncKind.FOLDER -> {
                    Note(stringResource(R.string.sync_folder_note))
                    OutlinedButton(onClick = { folder.launch(null) }) {
                        Text(stringResource(if (config.folder == null) R.string.backup_auto_folder else R.string.backup_auto_folder_change))
                    }
                }
                SyncKind.WEBDAV -> {
                    OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.sync_webdav_url)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.sync_webdav_user)) })
                    OutlinedTextField(
                        password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.sync_webdav_password)) },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Note(stringResource(R.string.sync_webdav_note))
                    OutlinedButton(enabled = url.startsWith("https://") && password.isNotEmpty(), onClick = {
                        confirming = runCatching { URL(url).host }.getOrNull()
                    }) { Text(stringResource(R.string.sync_webdav_save)) }
                    config.confirmedHost?.let { Note(stringResource(R.string.sync_webdav_confirmed, it)) }
                }
            }
        }
        if (config.kind != SyncKind.OFF) {
            Inset { Label(stringResource(R.string.sync_what)) }
            BackupModule.entries.forEach { m ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(m in config.modules, { on -> save(config.copy(modules = if (on) config.modules + m else config.modules - m)) })
                    Text(stringResource(labelOf(m)), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Inset {
                Note(stringResource(R.string.sync_never_clipboard))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(config.auto, { on -> save(config.copy(auto = on)) })
                    Column {
                        Text(stringResource(R.string.sync_auto), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.sync_auto_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(enabled = !busy, onClick = { syncNow() }) { Text(stringResource(if (busy) R.string.backup_working else R.string.sync_now)) }
                if (state.lastUtcMs > 0) Note(stringResource(R.string.sync_last, DateUtils.formatDateTime(context, state.lastUtcMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)))
                state.conflicts.forEach { m ->
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        Text(stringResource(R.string.sync_conflict, stringResource(labelOf(m))), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            TextButton(enabled = !busy, onClick = { syncNow(mapOf(m to true)) }) { Text(stringResource(R.string.sync_keep_device)) }
                            TextButton(enabled = !busy, onClick = { syncNow(mapOf(m to false)) }) { Text(stringResource(R.string.sync_keep_copy)) }
                        }
                    }
                }
            }
        }
    }

    confirming?.let { host ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.sync_confirm_title)) },
            text = { Text(stringResource(R.string.sync_confirm_text, host)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    val pass = password.toCharArray()
                    password = ""
                    scope.launch(Dispatchers.IO) { Sync.savePassword(context, pass) }
                    save(config.copy(kind = SyncKind.WEBDAV, url = url, user = user, confirmedHost = host))
                }) { Text(stringResource(R.string.sync_confirm_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.ai_cancel)) } },
        )
    }
}

/** The label of a sync place. */
private val SyncKind.label: Int
    get() = when (this) {
        SyncKind.OFF -> R.string.sync_off
        SyncKind.FOLDER -> R.string.sync_folder
        SyncKind.WEBDAV -> R.string.sync_webdav
    }

/** What the person sees for each sync problem. */
private val SyncProblem.message: Int
    get() = when (this) {
        SyncProblem.NOT_SET_UP -> R.string.sync_fail_setup
        SyncProblem.UNCONFIRMED_HOST -> R.string.sync_fail_host
        SyncProblem.BUSY -> R.string.sync_fail_busy
        SyncProblem.NETWORK -> R.string.sync_fail_network
        SyncProblem.AUTH -> R.string.sync_fail_auth
        SyncProblem.FOLDER -> R.string.sync_fail_folder
        SyncProblem.DAMAGED -> R.string.sync_fail_damaged
    }
