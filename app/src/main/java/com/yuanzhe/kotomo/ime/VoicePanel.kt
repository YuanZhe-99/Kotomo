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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuanzhe.kotomo.R

/**
 * Shows the voice typing panel in the keyboard's place (PLAN §12): what is being heard, the
 * microphone button, the languages and Back.
 *
 * Inputs: [start] the language it opens in, [chinese] the Chinese for the person's script;
 * [onAskPermission] opens Kotomo's screen that asks for the microphone; [onText] commits a final
 * result; [onBack] returns to the keys.
 * Notes: main thread. Nothing listens before the person taps the microphone; the recognizer is
 * closed when the panel goes away. A missing speech model is downloaded by the system only after
 * the person taps Download under the notice saying so.
 */
@Composable
fun VoicePanel(start: VoiceLang, chinese: VoiceLang, onAskPermission: () -> Unit, onText: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var lang by remember { mutableStateOf(start) }
    var state by remember { mutableStateOf(if (!Voice.available(context)) null else if (Voice.permitted(context)) VoiceState.IDLE else VoiceState.NO_PERMISSION) }
    var heard by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(VoiceModel.UNKNOWN) }
    val voice = remember {
        Voice(context, onState = { state = it; if (it != VoiceState.LISTENING) heard = "" }, onPartial = { heard = it }, onFinal = { heard = ""; onText(it) })
    }
    DisposableEffect(voice) { onDispose { voice.close() } }
    LaunchedEffect(lang, state == VoiceState.NO_PERMISSION) { if (state != null) model = Voice.model(context, lang) }

    val needsModel = state == VoiceState.NEEDS_MODEL || model == VoiceModel.DOWNLOADABLE || model == VoiceModel.DOWNLOADING
    val message = when {
        state == null -> stringResource(R.string.voice_unavailable)
        state == VoiceState.NO_PERMISSION -> stringResource(R.string.voice_permission)
        model == VoiceModel.UNSUPPORTED -> stringResource(R.string.voice_unsupported)
        model == VoiceModel.DOWNLOADING -> stringResource(R.string.voice_downloading)
        needsModel -> stringResource(R.string.voice_needs_model)
        state == VoiceState.LISTENING -> heard.ifEmpty { stringResource(R.string.voice_listening) }
        state == VoiceState.NO_MATCH -> stringResource(R.string.voice_no_match)
        state == VoiceState.FAILED -> stringResource(R.string.voice_fail)
        else -> stringResource(R.string.voice_tap)
    }
    Column(Modifier.fillMaxSize().background(kbColor(R.color.kb_background)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(message, Modifier.padding(horizontal = 16.dp), fontSize = 15.sp, textAlign = TextAlign.Center, color = kbColor(if (state == VoiceState.LISTENING && heard.isNotEmpty()) R.color.kb_ink else R.color.kb_ink_secondary))
                when {
                    state == null || model == VoiceModel.UNSUPPORTED || model == VoiceModel.DOWNLOADING -> Unit
                    state == VoiceState.NO_PERMISSION -> Action(stringResource(R.string.voice_allow), onAskPermission)
                    needsModel -> Action(stringResource(R.string.ai_download)) {
                        voice.download(lang)
                        model = VoiceModel.DOWNLOADING
                    }
                    else -> {
                        val listening = state == VoiceState.LISTENING
                        val label = stringResource(if (listening) R.string.voice_stop else R.string.voice_start)
                        Box(
                            Modifier.size(72.dp).clip(CircleShape)
                                .background(kbColor(if (listening) R.color.kb_accent else R.color.kb_accent_container))
                                .clickable { if (listening) voice.stop() else voice.start(lang) }
                                .semantics { role = Role.Button; contentDescription = label },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("🎙", fontSize = 30.sp)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(stringResource(R.string.translate_back), on = false, Modifier.width(64.dp), onBack)
            listOf(chinese to R.string.translate_lang_chinese, VoiceLang.ENGLISH to R.string.ai_lang_english, VoiceLang.JAPANESE to R.string.ai_lang_japanese).forEach { (l, label) ->
                Pill(stringResource(label), on = l == lang, Modifier.weight(1f)) {
                    if (l != lang) {
                        voice.close()
                        if (state != null && state != VoiceState.NO_PERMISSION) state = VoiceState.IDLE
                        lang = l
                    }
                }
            }
        }
    }
}

/** The panel's main action under its message. */
@Composable
private fun Action(text: String, onClick: () -> Unit) {
    Box(
        Modifier.width(200.dp).height(42.dp).clip(RoundedCornerShape(12.dp)).background(kbColor(R.color.kb_accent))
            .clickable(onClick = onClick).semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, color = kbColor(R.color.kb_on_accent))
    }
}

/** A button of the bottom row; [on] marks the chosen language. */
@Composable
private fun Pill(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp))
            .background(kbColor(if (on) R.color.kb_accent_container else R.color.kb_key_function))
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; selected = on },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, color = kbColor(if (on) R.color.kb_on_accent_container else R.color.kb_ink))
    }
}
