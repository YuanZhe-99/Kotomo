package com.yuanzhe.kotomo.ime

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
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
 * Names the toolbar's tools (PLAN §10.10) with their icon and label.
 * Notes: More always comes first on the bar; every other tool can be moved and hidden in the
 * settings, and a hidden tool (or one the bar has no room for) waits under More. The constant
 * names are stored (`toolbar.order`, `toolbar.hidden`), so renaming one is a format change.
 */
enum class Tool(val icon: Int, val label: Int) {
    MORE(R.drawable.ic_tool_more, R.string.tool_more),
    CLIPBOARD(R.drawable.ic_tool_clipboard, R.string.tool_clipboard),
    AI(R.drawable.ic_tool_ai, R.string.tool_ai),
    TRANSLATE(R.drawable.ic_tool_translate, R.string.tool_translate),
    SETTINGS(R.drawable.ic_tool_settings, R.string.tool_settings),
    EDIT(R.drawable.ic_tool_edit, R.string.tool_edit),
    EMOJI(R.drawable.ic_key_emoji, R.string.tool_emoji),
    LANGUAGE(R.drawable.ic_tool_language, R.string.tool_language),
    DICTIONARIES(R.drawable.ic_tool_dictionaries, R.string.tool_dictionaries),
    THEMES(R.drawable.ic_tool_themes, R.string.tool_themes),
    FLOATING(R.drawable.ic_tool_floating, R.string.tool_floating),
    UNDO(R.drawable.ic_tool_undo, R.string.tool_undo),
    REDO(R.drawable.ic_tool_redo, R.string.tool_redo),
    HANDWRITING(R.drawable.ic_tool_handwriting, R.string.tool_handwriting);

    companion object {
        /** The tools the person can order and hide: all but More, in their default order. */
        val configurable: List<Tool> = entries - MORE
        /** The tools that start under More rather than on the bar (the bar keeps its designed four). */
        val offBar: Set<Tool> = setOf(EDIT, EMOJI, LANGUAGE, DICTIONARIES, THEMES, FLOATING, UNDO, REDO, HANDWRITING)
    }
}

/**
 * Sends the edit panel's keys and actions to the field (PLAN §10.10 "cursor movement, text
 * editing").
 * Notes: main thread. Keys go out as KeyEvent down/up pairs, with Shift held while [select] is on
 * so the arrows extend the selection; the clipboard actions use the field's own context menu
 * actions, which fields that do not support them ignore.
 */
object TextEditing {
    /**
     * Sends [keycode] to [ic] as a down/up pair; when [select] is on, inside a Shift down/up with
     * the Shift meta state, as a hardware keyboard sends it (text fields track Shift from both).
     */
    fun key(ic: InputConnection, keycode: Int, select: Boolean) {
        val meta = if (select) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val now = SystemClock.uptimeMillis()
        if (select) ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keycode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keycode, 0, meta))
        if (select) ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
    }

    /** Runs one of the field's context menu actions ([android.R.id.copy] and the like). */
    fun action(ic: InputConnection, id: Int) {
        ic.performContextMenuAction(id)
    }
}

/**
 * Draws the tools that are not on the bar as a grid of icon buttons with labels (the top of the
 * More panel).
 * Inputs: [tools] in the person's order; [onTool] runs one.
 */
@Composable
fun ToolGrid(tools: List<Tool>, onTool: (Tool) -> Unit) {
    if (tools.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.tools_more_title), fontSize = 15.sp, color = kbColor(R.color.kb_ink))
        tools.chunked(TOOLS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { tool -> ToolButton(tool, Modifier.weight(1f)) { onTool(tool) } }
                repeat(TOOLS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One tool of the grid: its icon over its label. */
@Composable
private fun ToolButton(tool: Tool, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(kbColor(R.color.kb_key))
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(tool.icon), contentDescription = null, colorFilter = ColorFilter.tint(kbColor(R.color.kb_ink)))
        // Shrinks rather than cuts the label off at large font scales.
        Text(
            stringResource(tool.label), color = kbColor(R.color.kb_ink), textAlign = TextAlign.Center, maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 12.sp),
        )
    }
}

/**
 * Shows the cursor and text-editing keys in the keyboard's place (the Edit tool, PLAN §10.10):
 * arrows, line start and end, a Select switch that makes the arrows extend the selection, and
 * Select all, Cut, Copy, Paste and Delete, and Undo and Redo (PLAN §10.8; the field's own history,
 * which fields without one ignore).
 * Inputs: [onKey] sends a keycode (with Shift while selecting); [onAction] runs a context menu
 * action id; [onBack] returns to the keys.
 */
@Composable
fun EditPanel(onKey: (keycode: Int, select: Boolean) -> Unit, onAction: (Int) -> Unit, onBack: () -> Unit) {
    var select by remember { mutableStateOf(false) }
    val key = { code: Int -> onKey(code, select) }
    Column(Modifier.fillMaxSize().background(kbColor(R.color.kb_background)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EditRow {
                    EditKey(stringResource(R.string.edit_home), "⇤") { key(KeyEvent.KEYCODE_MOVE_HOME) }
                    EditKey(stringResource(R.string.edit_up), "↑") { key(KeyEvent.KEYCODE_DPAD_UP) }
                    EditKey(stringResource(R.string.edit_end), "⇥") { key(KeyEvent.KEYCODE_MOVE_END) }
                }
                EditRow {
                    EditKey(stringResource(R.string.edit_left), "←") { key(KeyEvent.KEYCODE_DPAD_LEFT) }
                    EditKey(stringResource(R.string.edit_select), stringResource(R.string.edit_select), on = select) { select = !select }
                    EditKey(stringResource(R.string.edit_right), "→") { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
                }
                EditRow {
                    Spacer(Modifier.weight(1f))
                    EditKey(stringResource(R.string.edit_down), "↓") { key(KeyEvent.KEYCODE_DPAD_DOWN) }
                    Spacer(Modifier.weight(1f))
                }
            }
            Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EditRow { EditKey(stringResource(R.string.edit_select_all)) { onAction(android.R.id.selectAll) } }
                EditRow {
                    EditKey(stringResource(R.string.edit_cut)) { select = false; onAction(android.R.id.cut) }
                    EditKey(stringResource(R.string.edit_copy)) { select = false; onAction(android.R.id.copy) }
                }
                EditRow {
                    EditKey(stringResource(R.string.edit_paste)) { select = false; onAction(android.R.id.paste) }
                    EditKey(stringResource(R.string.edit_delete), "⌫") { onKey(KeyEvent.KEYCODE_DEL, false) }
                }
                EditRow {
                    EditKey(stringResource(R.string.tool_undo), "↶") { select = false; onAction(android.R.id.undo) }
                    EditKey(stringResource(R.string.tool_redo), "↷") { select = false; onAction(android.R.id.redo) }
                }
            }
        }
        val back = stringResource(R.string.candidates_collapse)
        Box(
            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(kbColor(R.color.kb_key_function))
                .clickable(onClick = onBack).semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_bar_collapse), contentDescription = null, colorFilter = ColorFilter.tint(kbColor(R.color.kb_ink)))
        }
    }
}

/** One row of edit keys sharing the column's height. */
@Composable
private fun ColumnScope.EditRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

/**
 * One edit key: [shown] drawn (the [label] by default), [label] read out; [on] highlights the
 * Select switch while it is on.
 */
@Composable
private fun RowScope.EditKey(label: String, shown: String = label, on: Boolean? = null, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
            .background(kbColor(if (on == true) R.color.kb_accent_container else R.color.kb_key))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                if (on != null) {
                    role = Role.Switch
                    selected = on
                } else {
                    role = Role.Button
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(shown, fontSize = if (shown == label) 14.sp else 20.sp, color = kbColor(if (on == true) R.color.kb_on_accent_container else R.color.kb_ink), maxLines = 1)
    }
}

/** How many tools the More panel's grid puts in one row. */
private const val TOOLS_PER_ROW = 4
