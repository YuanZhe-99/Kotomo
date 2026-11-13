package com.yuanzhe.kotomo.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.ime.Inflection.Form

/** Maps a form of the panel to its name in the interface language. */
fun formLabel(form: Form): Int = when (form) {
    Form.PLAIN -> R.string.form_plain
    Form.POLITE -> R.string.form_polite
    Form.MASU -> R.string.form_masu
    Form.MASEN -> R.string.form_masen
    Form.MASHITA -> R.string.form_mashita
    Form.TE -> R.string.form_te
    Form.TA -> R.string.form_ta
    Form.NAI -> R.string.form_nai
    Form.BA -> R.string.form_ba
    Form.POTENTIAL -> R.string.form_potential
    Form.PASSIVE -> R.string.form_passive
    Form.CAUSATIVE -> R.string.form_causative
    Form.VOLITIONAL -> R.string.form_volitional
    Form.IMPERATIVE -> R.string.form_imperative
    Form.HONORIFIC -> R.string.form_honorific
    Form.HUMBLE -> R.string.form_humble
}

/**
 * Shows a Japanese word's forms in the keyboard's place (PLAN §10.1, the Forms button of a
 * candidate's details): each form as a chip with its name above it, and Back.
 * Inputs: [forms] follows the word last asked about; [onPick] commits form n in place of the
 * composition; [onBack] returns to the keys.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormsPanel(forms: State<List<Inflection.Inflected>>, onPick: (Int) -> Unit, onBack: () -> Unit) {
    val gap = dimensionResource(R.dimen.forms_gap)
    val radius = dimensionResource(R.dimen.forms_radius)
    // sp tokens: back to text sizes that keep their font scaling.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val label = with(density) { dimensionResource(R.dimen.forms_label_text).toSp() }
    val text = with(density) { dimensionResource(R.dimen.forms_text).toSp() }
    Column(Modifier.fillMaxSize().background(kbColor(R.color.kb_background))) {
        FlowRow(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(gap),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            forms.value.forEachIndexed { i, f ->
                Column(
                    Modifier
                        .heightIn(min = dimensionResource(R.dimen.forms_chip_height))
                        .clip(RoundedCornerShape(radius))
                        .background(kbColor(R.color.kb_key))
                        .clickable { onPick(i) }
                        .padding(horizontal = dimensionResource(R.dimen.forms_chip_pad)),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(formLabel(f.form)), fontSize = label, color = kbColor(R.color.kb_ink_secondary), maxLines = 1)
                    Text(f.text, fontSize = text, color = kbColor(R.color.kb_ink), maxLines = 1)
                }
            }
        }
        val back = stringResource(R.string.candidates_collapse)
        Row(Modifier.fillMaxWidth().height(dimensionResource(R.dimen.forms_back_height)).padding(gap)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(radius))
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
}
