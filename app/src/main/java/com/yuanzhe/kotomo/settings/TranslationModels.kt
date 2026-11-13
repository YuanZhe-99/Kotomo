package com.yuanzhe.kotomo.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.ai.TranslateException
import com.yuanzhe.kotomo.ai.TranslateLang
import com.yuanzhe.kotomo.ai.Translations
import kotlinx.coroutines.launch

/**
 * Draws the AI page's list of translation language models on the device, each with Delete
 * (PLAN §11.1: the person controls what was downloaded).
 * Side effects: Delete removes that model through ML Kit.
 * Notes: shown only while AI is on, so nothing asks ML Kit while it is off; the list is read when
 * the section appears and after each deletion.
 */
@Composable
internal fun TranslationModels() {
    val scope = rememberCoroutineScope()
    val translations = remember { Translations() }
    DisposableEffect(translations) { onDispose { translations.close() } }
    var models by remember { mutableStateOf<Set<TranslateLang>?>(null) }
    var reads by remember { mutableIntStateOf(0) }
    LaunchedEffect(reads) { models = try { translations.downloaded() } catch (_: TranslateException) { emptySet() } }
    Section(stringResource(R.string.translate_models)) {
        val shown = models ?: return@Section
        Inset {
            if (shown.isEmpty()) Text(stringResource(R.string.translate_models_none), style = MaterialTheme.typography.bodyMedium)
            shown.sortedBy { it.ordinal }.forEach { lang ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.translate_models_desc, stringResource(lang.label)), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { translations.delete(lang) }
                            reads++
                        }
                    }) { Text(stringResource(R.string.translate_models_delete)) }
                }
            }
        }
    }
}

/** The name of a translation language as the keyboard's panel shows it. */
private val TranslateLang.label: Int
    get() = when (this) {
        TranslateLang.CHINESE -> R.string.translate_lang_chinese
        TranslateLang.ENGLISH -> R.string.ai_lang_english
        TranslateLang.JAPANESE -> R.string.ai_lang_japanese
    }
