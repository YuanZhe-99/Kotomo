package com.yuanzhe.kotomo.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.settings.ChineseScript
import com.yuanzhe.kotomo.settings.OneHanded
import com.yuanzhe.kotomo.settings.SettingsStore
import com.yuanzhe.kotomo.settings.SplitLayout
import com.yuanzhe.kotomo.settings.label

/**
 * Shows the More panel in the keyboard's place: the tools not on the bar, then the quick settings: one-handed side, split layout,
 * the Chinese script (Mainland, Traditional, Taiwan, Hong Kong), incognito, and a way into the settings app.
 *
 * Inputs: [store] is read and changed directly (the keyboard follows it at once); [script] is the
 * Chinese script remembered for the current app (null: the settings' one) and [onScript] changes
 * it (for the app and the settings); [oneHanded] is the one-handed side on this screen and
 * [onOneHanded] changes it there (PLAN §8.10); [mode] is the mode in use, [pinned] the mode fixed for the
 * current app (null: none) and [onPin] fixes the mode in use there or releases it (PLAN §2.3);
 * [onOpenSettings] opens the settings app; [onBack] returns to the keys; [moreTools] are the
 * toolbar's tools that are not on the bar, shown first as a grid, and [onTool] runs one (PLAN §10.10).
 */
@Composable
fun QuickPanel(
    store: SettingsStore,
    script: ChineseScript?,
    onScript: (ChineseScript) -> Unit,
    mode: State<Mode>,
    oneHanded: State<OneHanded>,
    onOneHanded: (OneHanded) -> Unit,
    pinned: State<Mode?>,
    onPin: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    moreTools: State<List<Tool>>,
    onTool: (Tool) -> Unit,
) {
    val settings by store.flow.collectAsState()
    val back = stringResource(R.string.candidates_collapse)
    Column(Modifier.fillMaxSize().background(kbColor(R.color.kb_background))) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToolGrid(moreTools.value, onTool)
            Choice(
                stringResource(R.string.one_handed_title),
                listOf(OneHanded.OFF to R.string.one_handed_off, OneHanded.LEFT to R.string.one_handed_left, OneHanded.RIGHT to R.string.one_handed_right),
                oneHanded.value,
                onPick = onOneHanded,
            )
            Choice(
                stringResource(R.string.split_title),
                listOf(SplitLayout.AUTO to R.string.split_auto, SplitLayout.ALWAYS to R.string.split_always, SplitLayout.NEVER to R.string.split_never),
                settings.split,
            ) { value -> store.set { it.copy(split = value) } }
            Choice(
                stringResource(R.string.chinese_script),
                ChineseScript.entries.map { it to it.label },
                script ?: settings.chineseScript,
                onPick = onScript,
            )
            val fixed = pinned.value
            Toggle(
                stringResource(R.string.pin_title),
                stringResource(R.string.pin_desc, stringResource((fixed ?: mode.value).label)),
                checked = fixed != null,
                onChange = onPin,
            )
            Toggle(stringResource(R.string.incognito_title), stringResource(R.string.incognito_desc), settings.incognito) { on ->
                store.set { it.copy(incognito = on) }
            }
            Pill(stringResource(R.string.quick_all_settings), selected = false, modifier = Modifier.fillMaxWidth(), onClick = onOpenSettings)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(kbColor(R.color.kb_key_function))
                .clickable(onClick = onBack)
                .semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.drawable.ic_bar_collapse),
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(kbColor(R.color.kb_ink)),
            )
        }
    }
}

/** One switch with its title and description, in the keyboard's colors. */
@Composable
private fun Toggle(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = kbColor(R.color.kb_ink))
            Text(desc, fontSize = 12.sp, color = kbColor(R.color.kb_ink_secondary))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = kbColor(R.color.kb_accent)),
        )
    }
}

/** One setting with a few choices as a row of pills, the current one highlighted. */
@Composable
private fun <T> Choice(title: String, options: List<Pair<T, Int>>, current: T, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 15.sp, color = kbColor(R.color.kb_ink))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                Pill(stringResource(label), selected = value == current, modifier = Modifier.weight(1f)) { onPick(value) }
            }
        }
    }
}

/** A pill-shaped button in the keyboard's colors. */
@Composable
private fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colorResource(if (selected) R.color.kb_accent_container else R.color.kb_key))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 14.sp,
            color = colorResource(if (selected) R.color.kb_on_accent_container else R.color.kb_ink),
        )
    }
}
