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

/**
 * Shows the toolbar's quick settings in the keyboard's place: one-handed side, split layout,
 * Simplified or Traditional Chinese, incognito, and a way into the settings app.
 *
 * Inputs: [store] is read and changed directly (the keyboard follows it at once); [onOpenSettings]
 * opens the settings app; [onBack] returns to the keys.
 */
@Composable
fun QuickPanel(store: SettingsStore, onOpenSettings: () -> Unit, onBack: () -> Unit) {
    val settings by store.flow.collectAsState()
    val back = stringResource(R.string.candidates_collapse)
    Column(Modifier.fillMaxSize().background(colorResource(R.color.kb_background))) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Choice(
                stringResource(R.string.one_handed_title),
                listOf(OneHanded.OFF to R.string.one_handed_off, OneHanded.LEFT to R.string.one_handed_left, OneHanded.RIGHT to R.string.one_handed_right),
                settings.oneHanded,
            ) { value -> store.set { it.copy(oneHanded = value) } }
            Choice(
                stringResource(R.string.split_title),
                listOf(SplitLayout.AUTO to R.string.split_auto, SplitLayout.ALWAYS to R.string.split_always, SplitLayout.NEVER to R.string.split_never),
                settings.split,
            ) { value -> store.set { it.copy(split = value) } }
            Choice(
                stringResource(R.string.chinese_script),
                listOf(ChineseScript.SIMPLIFIED to R.string.script_simplified, ChineseScript.TRADITIONAL to R.string.script_traditional),
                settings.chineseScript,
            ) { value -> store.set { it.copy(chineseScript = value) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.incognito_title), fontSize = 15.sp, color = colorResource(R.color.kb_ink))
                    Text(stringResource(R.string.incognito_desc), fontSize = 12.sp, color = colorResource(R.color.kb_ink_secondary))
                }
                Switch(
                    checked = settings.incognito,
                    onCheckedChange = { on -> store.set { it.copy(incognito = on) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = colorResource(R.color.kb_accent)),
                )
            }
            Pill(stringResource(R.string.quick_all_settings), selected = false, modifier = Modifier.fillMaxWidth(), onClick = onOpenSettings)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colorResource(R.color.kb_key_function))
                .clickable(onClick = onBack)
                .semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.drawable.ic_bar_collapse),
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(colorResource(R.color.kb_ink)),
            )
        }
    }
}

/** One setting with a few choices as a row of pills, the current one highlighted. */
@Composable
private fun <T> Choice(title: String, options: List<Pair<T, Int>>, current: T, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 15.sp, color = colorResource(R.color.kb_ink))
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
