package com.yuanzhe.kotomo.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.dictionary.DownloadException
import com.yuanzhe.kotomo.ime.EmojiFont
import com.yuanzhe.kotomo.ime.EmojiStyle
import com.yuanzhe.kotomo.ime.EmojiStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Draws the Themes page's emoji style section (PLAN §11.5): the system's emoji, Noto Color Emoji
 * (downloaded only after the notice naming its host), or a font the person imports (after a
 * notice about the font's rights), with a sample drawn in the chosen style.
 * Side effects: downloads or imports a font only on the person's tap; the keyboard uses the style
 * the next time its emoji panel opens.
 * Notes: kept apart from the colors above, as the plan asks; the page says that only the keyboard's
 * drawing changes, never the emoji sent to the app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmojiStyleSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val styles = remember { EmojiStyles.get(context) }
    val config by styles.config.collectAsState()
    var notice by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var files by remember { mutableIntStateOf(0) }
    val gap = dimensionResource(R.dimen.settings_card_gap)
    fun toast(id: Int) = Toast.makeText(context, id, Toast.LENGTH_SHORT).show()
    val hasNoto = remember(files) { styles.noto.isFile }
    val hasCustom = remember(files) { styles.custom.isFile }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty()
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { styles.importCustom(it, name) } ?: false }.getOrDefault(false)
            }
            files++
            if (!ok) toast(R.string.emoji_custom_bad)
        }
    }

    Section(stringResource(R.string.emoji_style_title)) {
        Inset {
            Note(stringResource(R.string.emoji_style_note))
            val options = listOf(EmojiStyle.SYSTEM to R.string.emoji_style_system, EmojiStyle.NOTO to R.string.emoji_style_noto, EmojiStyle.CUSTOM to R.string.emoji_style_custom)
            Choice(options.map { stringResource(it.second) }, options.indexOfFirst { it.first == config.style }) { i ->
                val style = options[i].first
                when {
                    style == EmojiStyle.NOTO && !hasNoto -> notice = true
                    style == EmojiStyle.CUSTOM && !hasCustom -> importing = true
                    else -> styles.choose(style)
                }
            }
            val typeface = remember(config, files) { styles.typeface() }
            EmojiFont(typeface) { Text(stringResource(R.string.emoji_style_sample), fontSize = SAMPLE_SP.sp) }
            progress?.let { p ->
                Text(stringResource(R.string.emoji_noto_downloading), style = MaterialTheme.typography.bodyMedium)
                if (p < 0f) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.ai_cancel)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                if (hasNoto) TextButton(onClick = { styles.deleteNoto(); files++ }) { Text(stringResource(R.string.emoji_noto_delete)) }
                if (hasCustom) TextButton(onClick = { styles.deleteCustom(); files++ }) { Text(stringResource(R.string.emoji_custom_delete)) }
                if (!hasNoto && progress == null) OutlinedButton(onClick = { notice = true }) { Text(stringResource(R.string.emoji_noto_download)) }
                OutlinedButton(onClick = { importing = true }) { Text(stringResource(R.string.emoji_custom_import)) }
            }
            if (hasCustom && config.customName.isNotEmpty()) Note(stringResource(R.string.emoji_custom_named, config.customName))
        }
    }

    if (notice) {
        AlertDialog(
            onDismissRequest = { notice = false },
            title = { Text(stringResource(R.string.emoji_style_noto)) },
            text = { Text(stringResource(R.string.emoji_noto_notice, EmojiStyles.NOTO_HOST)) },
            confirmButton = {
                TextButton(onClick = {
                    notice = false
                    progress = -1f
                    job = scope.launch {
                        val ok = try {
                            styles.downloadNoto { done, total -> progress = if (total > 0) done.toFloat() / total else -1f }
                            styles.choose(EmojiStyle.NOTO)
                            true
                        } catch (_: DownloadException) {
                            false
                        } finally {
                            progress = null
                            files++
                        }
                        if (!ok) toast(R.string.emoji_noto_failed)
                    }
                }) { Text(stringResource(R.string.ai_download)) }
            },
            dismissButton = { TextButton(onClick = { notice = false }) { Text(stringResource(R.string.ai_cancel)) } },
        )
    }
    if (importing) {
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text(stringResource(R.string.emoji_custom_import)) },
            text = { Text(stringResource(R.string.emoji_custom_notice)) },
            confirmButton = {
                TextButton(onClick = {
                    importing = false
                    pick.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/octet-stream"))
                }) { Text(stringResource(R.string.theme_apply)) }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text(stringResource(R.string.ai_cancel)) } },
        )
    }
}

private const val SAMPLE_SP = 28
