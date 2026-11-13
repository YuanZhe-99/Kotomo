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
import com.yuanzhe.kotomo.ime.InkException
import com.yuanzhe.kotomo.ime.InkLang
import com.yuanzhe.kotomo.ime.InkRecognizer
import kotlinx.coroutines.launch

/**
 * Draws the Keys page's list of handwriting models on the device, each with Delete (PLAN §12: the
 * person controls what was downloaded).
 * Side effects: Delete removes that model through ML Kit.
 * Notes: the list is read when the section appears and after each deletion; it asks ML Kit only
 * which models are stored, nothing goes over the network.
 */
@Composable
internal fun HandwritingModels() {
    val scope = rememberCoroutineScope()
    val recognizer = remember { InkRecognizer() }
    DisposableEffect(recognizer) { onDispose { recognizer.close() } }
    var models by remember { mutableStateOf<Set<InkLang>?>(null) }
    var reads by remember { mutableIntStateOf(0) }
    LaunchedEffect(reads) { models = try { recognizer.downloaded() } catch (_: InkException) { emptySet() } }
    Section(stringResource(R.string.hw_models)) {
        val shown = models ?: return@Section
        Inset {
            if (shown.isEmpty()) Text(stringResource(R.string.hw_models_none), style = MaterialTheme.typography.bodyMedium)
            shown.sortedBy { it.ordinal }.forEach { lang ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.translate_models_desc, stringResource(lang.label)), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { recognizer.delete(lang) }
                            reads++
                        }
                    }) { Text(stringResource(R.string.translate_models_delete)) }
                }
            }
        }
    }
}

/** The name of a handwriting language in the settings. */
private val InkLang.label: Int
    get() = when (this) {
        InkLang.CHINESE_CN -> R.string.hw_lang_cn
        InkLang.CHINESE_TW -> R.string.hw_lang_tw
        InkLang.CHINESE_HK -> R.string.hw_lang_hk
        InkLang.JAPANESE -> R.string.hw_lang_ja
    }
