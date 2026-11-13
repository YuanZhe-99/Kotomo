package com.yuanzhe.kotomo.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.ai.TranslateException
import com.yuanzhe.kotomo.ai.TranslateFailure
import com.yuanzhe.kotomo.ai.TranslateLang
import com.yuanzhe.kotomo.ai.Translations
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the translate panel shows under the languages. */
private sealed interface TranslateState {
    /** Asking ML Kit which models are on the device. */
    data object Checking : TranslateState
    /** Models are missing; the person decides whether to download them from Google. */
    data object NeedsDownload : TranslateState
    /** The models are downloading. */
    data object Downloading : TranslateState
    /** The translation is running. */
    data object Working : TranslateState
    /** The translation, ready to preview. */
    data class Done(val text: String) : TranslateState
    /** The download or translation failed. */
    data class Failed(val failure: TranslateFailure) : TranslateState
}

/**
 * Shows the translation of [text] in the keyboard's place (PLAN §11.1, §11.2): the languages to
 * translate from and into, the original, the translation as a preview, and Replace or Copy.
 *
 * Inputs: [onReplace] puts the confirmed translation into the field (the keyboard checks the
 * original is still there); [onCopy] copies it; [onBack] returns to the keys.
 * Notes: main thread. Nothing runs before the panel opens from the Translate tool; a missing model
 * is downloaded only after the person taps Download under the notice naming Google. The panel
 * holds the text only while it is open.
 */
@Composable
fun TranslatePanel(text: String, onReplace: (String) -> Unit, onCopy: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val translations = remember { Translations() }
    DisposableEffect(translations) { onDispose { translations.close() } }
    var source by remember { mutableStateOf(TranslateLang.guess(text)) }
    var target by remember { mutableStateOf(TranslateLang.defaultTarget(source)) }
    var state by remember { mutableStateOf<TranslateState>(TranslateState.Checking) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun run(download: Boolean) {
        job?.cancel()
        job = scope.launch {
            state = try {
                state = TranslateState.Checking
                val missing = Translations.needs(source, target) - translations.downloaded()
                if (missing.isNotEmpty() && !download) {
                    TranslateState.NeedsDownload
                } else {
                    if (missing.isNotEmpty()) {
                        state = TranslateState.Downloading
                        translations.download(source, target)
                    }
                    state = TranslateState.Working
                    TranslateState.Done(translations.translate(text, source, target))
                }
            } catch (e: TranslateException) {
                TranslateState.Failed(e.failure)
            }
        }
    }
    LaunchedEffect(source, target) { run(download = false) }

    Column(Modifier.fillMaxSize().background(kbColor(R.color.kb_background)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Langs(R.string.translate_from, source) { picked ->
            if (picked == target) target = source
            source = picked
        }
        Langs(R.string.translate_to, target) { picked ->
            if (picked == source) source = target
            target = picked
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Block(text, secondary = true)
            when (val s = state) {
                TranslateState.Checking, TranslateState.Working -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = kbColor(R.color.kb_accent))
                TranslateState.NeedsDownload -> Note(stringResource(R.string.translate_download_notice))
                TranslateState.Downloading -> {
                    Note(stringResource(R.string.translate_downloading))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = kbColor(R.color.kb_accent))
                }
                is TranslateState.Done -> Block(s.text, secondary = false)
                is TranslateState.Failed -> Note(stringResource(if (s.failure == TranslateFailure.DOWNLOAD) R.string.translate_fail_download else R.string.translate_fail))
            }
        }
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(stringResource(R.string.translate_back), accent = false, Modifier.width(64.dp), onClick = onBack)
            when (val s = state) {
                TranslateState.NeedsDownload -> Button(stringResource(R.string.ai_download), accent = true, Modifier.weight(1f)) { run(download = true) }
                is TranslateState.Failed -> Button(stringResource(R.string.ai_retry), accent = true, Modifier.weight(1f)) { run(download = false) }
                is TranslateState.Done -> {
                    Button(stringResource(R.string.ai_copy), accent = false, Modifier.weight(1f)) { onCopy(s.text) }
                    Button(stringResource(R.string.ai_replace), accent = true, Modifier.weight(1f)) { onReplace(s.text) }
                }
                else -> Box(Modifier.weight(1f))
            }
        }
    }
}

/** One row of language pills: [title], then Chinese, English and Japanese, [current] highlighted. */
@Composable
private fun Langs(title: Int, current: TranslateLang, onPick: (TranslateLang) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title), Modifier.width(56.dp), fontSize = 13.sp, color = kbColor(R.color.kb_ink_secondary))
        listOf(TranslateLang.CHINESE to R.string.translate_lang_chinese, TranslateLang.ENGLISH to R.string.ai_lang_english, TranslateLang.JAPANESE to R.string.ai_lang_japanese).forEach { (lang, label) ->
            val on = lang == current
            Box(
                Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(17.dp))
                    .background(kbColor(if (on) R.color.kb_accent_container else R.color.kb_key))
                    .clickable { onPick(lang) }
                    .semantics { role = Role.RadioButton; selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), fontSize = 14.sp, color = kbColor(if (on) R.color.kb_on_accent_container else R.color.kb_ink))
            }
        }
    }
}

/** A block of text on a key-colored card; the original is drawn in the secondary ink. */
@Composable
private fun Block(text: String, secondary: Boolean) {
    Text(
        text,
        Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).background(kbColor(R.color.kb_key)).padding(10.dp),
        fontSize = 15.sp,
        color = kbColor(if (secondary) R.color.kb_ink_secondary else R.color.kb_ink),
    )
}

/** A short explanation in the secondary ink. */
@Composable
private fun Note(text: String) {
    Text(text, fontSize = 13.sp, color = kbColor(R.color.kb_ink_secondary))
}

/** A button in the keyboard's colors; [accent] for the main action. */
@Composable
private fun Button(text: String, accent: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp))
            .background(kbColor(if (accent) R.color.kb_accent else R.color.kb_key_function))
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, color = kbColor(if (accent) R.color.kb_on_accent else R.color.kb_ink))
    }
}
