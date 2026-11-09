package com.yuanzhe.kotomo.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.settings.KotomoTheme
import com.yuanzhe.kotomo.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Shows the AI writing help for text the keyboard handed over: the person picks the language and
 * what to do, sees the suggestions, and confirms one with Replace, or copies or cancels.
 *
 * Notes: a foreground activity because GenAI serves only the foreground app (PLAN §11.1). It
 * runs nothing until the person picks an action, closes at once when AI is off or nothing was
 * handed over, and writes nothing: the confirmed text goes back through [AiHandoff] and the
 * keyboard replaces the field's text only if it is unchanged.
 */
class AiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = AiHandoff.request
        if (request == null || !SettingsStore.get(this).current.ai.enabled) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            KotomoTheme {
                AiScreen(
                    request,
                    onReplace = { text ->
                        AiHandoff.confirm(text)
                        finish()
                    },
                    onCancel = {
                        AiHandoff.clear()
                        finish()
                    },
                )
            }
        }
    }

    /**
     * Drops the handed-over text when the screen goes away for good.
     * Side effects: clears [AiHandoff]'s request unless a result was confirmed with Replace.
     * Notes: skipped for a configuration change (rotation, folding), where the screen is
     * recreated and reads the request again; covers the back gesture, finishing, and the task
     * being removed while the process lives.
     */
    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) AiHandoff.dropUnconfirmed()
    }
}

/** What the AI screen shows under the actions. */
private sealed interface AiState {
    /** Nothing asked yet. */
    data object Idle : AiState
    /** Asking AICore whether the feature can run. */
    data object Checking : AiState
    /** The model must be downloaded first; the person decides. */
    data object NeedsDownload : AiState
    /** The model is downloading; [fraction] is null while the size is unknown. */
    data class Downloading(val fraction: Float?) : AiState
    /** The request is running. */
    data object Working : AiState
    /** The suggestions, best first (empty: nothing to change). */
    data class Done(val suggestions: List<String>) : AiState
    /** The request failed. */
    data class Failed(val failure: AiFailure) : AiState
}

/** Lays out the AI screen for [request]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiScreen(request: AiRequest, onReplace: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var language by remember { mutableStateOf(request.language) }
    var action by remember { mutableStateOf<AiAction?>(null) }
    var state by remember { mutableStateOf<AiState>(AiState.Idle) }
    var chosen by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    val genAi = remember(language) { GenAi(context.applicationContext, language) }
    DisposableEffect(genAi) { onDispose { genAi.close() } }
    BackHandler(onBack = onCancel)

    fun start(a: AiAction, download: Boolean = false) {
        job?.cancel()
        action = a
        chosen = 0
        job = scope.launch {
            state = try {
                state = AiState.Checking
                var status = genAi.status(a)
                if (status == AiStatus.DOWNLOADABLE && !download) {
                    AiState.NeedsDownload
                } else {
                    if (status == AiStatus.DOWNLOADABLE || status == AiStatus.DOWNLOADING) {
                        state = AiState.Downloading(null)
                        genAi.download(a) { done, total ->
                            state = AiState.Downloading(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null)
                        }
                        status = genAi.status(a)
                    }
                    if (status != AiStatus.AVAILABLE) {
                        AiState.Failed(AiFailure.UNAVAILABLE)
                    } else {
                        state = AiState.Working
                        AiState.Done(genAi.run(a, request.text).filter { it != request.text })
                    }
                }
            } catch (e: AiException) {
                AiState.Failed(e.failure)
            }
        }
    }

    val gutter = dimensionResource(R.dimen.settings_gutter)
    val gap = dimensionResource(R.dimen.settings_section_gap)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = gutter, vertical = gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Label(R.string.ai_original)
        Card(request.text, selected = false, onClick = null)

        Label(R.string.ai_language)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
            listOf(AiLanguage.ENGLISH to R.string.ai_lang_english, AiLanguage.JAPANESE to R.string.ai_lang_japanese).forEach { (value, label) ->
                FilterChip(
                    selected = language == value,
                    onClick = {
                        if (language != value) {
                            job?.cancel()
                            language = value
                            action = null
                            state = AiState.Idle
                        }
                    },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        Label(R.string.ai_action)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
            ACTIONS.forEach { (value, label) ->
                FilterChip(selected = action == value, onClick = { start(value) }, label = { Text(stringResource(label)) })
            }
        }

        when (val s = state) {
            AiState.Idle -> Text(stringResource(R.string.ai_pick), style = MaterialTheme.typography.bodyMedium)
            AiState.Checking, AiState.Working -> {
                Text(stringResource(R.string.ai_working), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            AiState.NeedsDownload -> {
                Text(stringResource(R.string.ai_download_desc), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { action?.let { start(it, download = true) } }) { Text(stringResource(R.string.ai_download)) }
            }
            is AiState.Downloading -> {
                Text(stringResource(R.string.ai_downloading), style = MaterialTheme.typography.bodyMedium)
                if (s.fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
            is AiState.Failed -> {
                Text(stringResource(s.failure.message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                if (s.failure != AiFailure.UNAVAILABLE) {
                    OutlinedButton(onClick = { action?.let { start(it) } }) { Text(stringResource(R.string.ai_retry)) }
                }
            }
            is AiState.Done -> if (s.suggestions.isEmpty()) {
                Text(stringResource(R.string.ai_no_change), style = MaterialTheme.typography.bodyMedium)
            } else {
                Label(R.string.ai_suggestions)
                s.suggestions.forEachIndexed { i, text -> Card(text, selected = i == chosen) { chosen = i } }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Button(onClick = { onReplace(s.suggestions[chosen]) }) { Text(stringResource(R.string.ai_replace)) }
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(null, s.suggestions[chosen]))
                        Toast.makeText(context, R.string.ai_copied, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.ai_copy)) }
                }
            }
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.ai_cancel)) }
        Text(stringResource(R.string.ai_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The actions the screen offers, in order, with their labels. */
private val ACTIONS = listOf(
    AiAction.PROOFREAD to R.string.ai_action_proofread,
    AiAction.REPHRASE to R.string.ai_action_rephrase,
    AiAction.PROFESSIONAL to R.string.ai_action_professional,
    AiAction.FRIENDLY to R.string.ai_action_friendly,
    AiAction.ELABORATE to R.string.ai_action_elaborate,
    AiAction.SHORTEN to R.string.ai_action_shorten,
)

/** The message the person sees for each failure. */
private val AiFailure.message: Int
    @StringRes get() = when (this) {
        AiFailure.UNAVAILABLE -> R.string.ai_fail_unavailable
        AiFailure.BACKGROUND -> R.string.ai_fail_background
        AiFailure.BUSY -> R.string.ai_fail_busy
        AiFailure.QUOTA -> R.string.ai_fail_quota
        AiFailure.TOO_LONG -> R.string.ai_fail_too_long
        AiFailure.FILTERED -> R.string.ai_fail_filtered
        AiFailure.CANCELLED, AiFailure.FAILED -> R.string.ai_fail_failed
    }

/** A small heading above a group. */
@Composable
private fun Label(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/** A block of text on a card; selectable when [onClick] is set, outlined when [selected]. */
@Composable
private fun Card(text: String, selected: Boolean, onClick: (() -> Unit)?) {
    val pad = dimensionResource(R.dimen.settings_detail_pad)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(dimensionResource(R.dimen.ai_selected_border), MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().let { m ->
            if (onClick == null) m else m.semantics { role = Role.RadioButton; this.selected = selected }.clickable(onClick = onClick)
        },
    ) {
        Text(text, Modifier.padding(pad), style = MaterialTheme.typography.bodyLarge)
    }
}
