package com.yuanzhe.kotomo.ime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuanzhe.kotomo.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Whether the handwriting panel can recognize yet. */
private enum class InkState { CHECKING, NEEDS_DOWNLOAD, DOWNLOADING, READY, DOWNLOAD_FAILED, FAILED }

/**
 * Shows the handwriting panel in the keyboard's place (PLAN §12): the candidates for what is
 * written, the writing area, and Back, the languages, Space and Delete.
 *
 * Inputs: [start] the language it opens in, [chinese] the Chinese model for the person's script;
 * [before] reads the text before the cursor; [onText] commits a candidate; [onSpace] and
 * [onBackspace] act as the keys do when nothing is written; [onBack] returns to the keys.
 * Notes: main thread. Writing is recognized shortly after each stroke, on ML Kit's threads; a
 * missing model is downloaded only after the person taps Download under the notice naming Google.
 * The strokes are kept only while the panel is open and nothing written is learned.
 */
@Composable
fun HandwritingPanel(
    start: InkLang, chinese: InkLang, before: () -> String,
    onText: (String) -> Unit, onSpace: () -> Unit, onBackspace: () -> Unit, onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val recognizer = remember { InkRecognizer() }
    DisposableEffect(recognizer) { onDispose { recognizer.close() } }
    var lang by remember { mutableStateOf(start) }
    var state by remember { mutableStateOf(InkState.CHECKING) }
    val strokes = remember { mutableStateListOf<List<InkPoint>>() }
    var current by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var area by remember { mutableStateOf(0f to 0f) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun clear() {
        job?.cancel()
        strokes.clear()
        candidates = emptyList()
    }

    fun recognize() {
        job?.cancel()
        if (strokes.isEmpty() || state != InkState.READY) {
            candidates = emptyList()
            return
        }
        val written = strokes.toList()
        job = scope.launch {
            delay(RECOGNIZE_DELAY_MS)
            candidates = try {
                recognizer.recognize(lang, written, area.first, area.second, before())
            } catch (_: InkException) {
                emptyList()
            }
        }
    }

    fun commit(text: String) {
        clear()
        onText(text)
    }

    fun check(download: Boolean) {
        job?.cancel()
        job = scope.launch {
            state = InkState.CHECKING
            state = try {
                if (recognizer.downloaded(lang)) {
                    InkState.READY
                } else if (download) {
                    state = InkState.DOWNLOADING
                    recognizer.download(lang)
                    InkState.READY
                } else {
                    InkState.NEEDS_DOWNLOAD
                }
            } catch (e: InkException) {
                if (e.download) InkState.DOWNLOAD_FAILED else InkState.FAILED
            }
            recognize()
        }
    }
    LaunchedEffect(lang) { check(download = false) }

    Column(Modifier.fillMaxSize().background(kbColor(R.color.kb_background)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            candidates.forEachIndexed { i, c ->
                Box(
                    Modifier.fillMaxHeight().clip(RoundedCornerShape(20.dp))
                        .background(kbColor(if (i == 0) R.color.kb_accent_container else R.color.kb_key))
                        .clickable { commit(c) }
                        .semantics { role = Role.Button }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(c, fontSize = 18.sp, color = kbColor(if (i == 0) R.color.kb_on_accent_container else R.color.kb_ink))
                }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(kbColor(R.color.kb_key)),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                InkState.READY -> if (strokes.isEmpty() && current.isEmpty()) Note(stringResource(R.string.hw_hint))
                InkState.CHECKING -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp), color = kbColor(R.color.kb_accent))
                InkState.NEEDS_DOWNLOAD -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Note(stringResource(R.string.hw_download_notice))
                    Button(stringResource(R.string.ai_download), accent = true, Modifier.width(160.dp).height(40.dp)) { check(download = true) }
                }
                InkState.DOWNLOADING -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Note(stringResource(R.string.hw_downloading))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = kbColor(R.color.kb_accent))
                }
                InkState.DOWNLOAD_FAILED, InkState.FAILED -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Note(stringResource(if (state == InkState.DOWNLOAD_FAILED) R.string.hw_fail_download else R.string.hw_fail))
                    Button(stringResource(R.string.ai_retry), accent = true, Modifier.width(160.dp).height(40.dp)) { check(download = state == InkState.DOWNLOAD_FAILED) }
                }
            }
            if (state == InkState.READY) {
                val ink = kbColor(R.color.kb_ink)
                val areaName = stringResource(R.string.hw_area)
                Canvas(
                    Modifier.fillMaxSize()
                        .onSizeChanged { area = it.width.toFloat() to it.height.toFloat() }
                        .semantics { contentDescription = areaName }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                job?.cancel()
                                down.consume()
                                val points = mutableListOf(InkPoint(down.position.x, down.position.y, down.uptimeMillis))
                                current = points.toList()
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    points += InkPoint(change.position.x, change.position.y, change.uptimeMillis)
                                    current = points.toList()
                                    change.consume()
                                }
                                strokes += points.toList()
                                current = emptyList()
                                recognize()
                            }
                        },
                ) {
                    val style = Stroke(width = STROKE_DP.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    (strokes + listOf(current)).forEach { s ->
                        if (s.isEmpty()) return@forEach
                        if (s.size == 1) {
                            drawCircle(ink, STROKE_DP.dp.toPx() / 2, Offset(s[0].x, s[0].y))
                        } else {
                            val path = Path().apply {
                                moveTo(s[0].x, s[0].y)
                                for (p in s.drop(1)) lineTo(p.x, p.y)
                            }
                            drawPath(path, ink, style = style)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(stringResource(R.string.translate_back), accent = false, Modifier.width(64.dp), onClick = onBack)
            listOf(chinese to R.string.translate_lang_chinese, InkLang.JAPANESE to R.string.ai_lang_japanese).forEach { (l, label) ->
                val on = l == lang
                Box(
                    Modifier.width(64.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                        .background(kbColor(if (on) R.color.kb_accent_container else R.color.kb_key_function))
                        .clickable { if (!on) { clear(); lang = l } }
                        .semantics { role = Role.RadioButton; selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(label), fontSize = 14.sp, color = kbColor(if (on) R.color.kb_on_accent_container else R.color.kb_ink))
                }
            }
            Button(stringResource(R.string.hw_space), accent = false, Modifier.weight(1f)) {
                // Space takes the best candidate when something is written, as on the keys.
                val first = candidates.firstOrNull()
                if (strokes.isNotEmpty()) first?.let(::commit) else onSpace()
            }
            Button("⌫", accent = false, Modifier.width(56.dp), description = stringResource(R.string.key_backspace)) {
                if (strokes.isNotEmpty()) {
                    strokes.removeAt(strokes.lastIndex)
                    recognize()
                } else {
                    onBackspace()
                }
            }
        }
    }
}

/** A short explanation in the secondary ink. */
@Composable
private fun Note(text: String) {
    Text(text, fontSize = 13.sp, color = kbColor(R.color.kb_ink_secondary))
}

/** A button in the keyboard's colors; [accent] for the main action, [description] for a symbol label. */
@Composable
private fun Button(text: String, accent: Boolean, modifier: Modifier, description: String? = null, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp))
            .background(kbColor(if (accent) R.color.kb_accent else R.color.kb_key_function))
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; description?.let { contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, color = kbColor(if (accent) R.color.kb_on_accent else R.color.kb_ink))
    }
}

/** How long after a stroke the writing is recognized, so a quick next stroke is waited for. */
private const val RECOGNIZE_DELAY_MS = 250L
/** The ink's width. */
private const val STROKE_DP = 4f
