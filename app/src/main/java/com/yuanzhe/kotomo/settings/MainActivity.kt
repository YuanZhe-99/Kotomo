package com.yuanzhe.kotomo.settings

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.ime.Probe

/**
 * Hosts the Kotomo settings app: keyboard setup, a field to try typing, and spike S1's
 * diagnostics.
 *
 * Notes: the setup state and the diagnostics are re-read whenever the screen resumes, since both
 * change in other windows (system settings, the keyboard).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { KotomoTheme { SettingsScreen() } }
    }

    @Composable
    private fun SettingsScreen() {
        var enabled by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf(false) }
        var summary by remember { mutableStateOf(Probe.load(this)) }
        LifecycleResumeEffect(Unit) {
            val imm = getSystemService(InputMethodManager::class.java)
            enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
            selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.startsWith("$packageName/") == true
            summary = Probe.load(this@MainActivity)
            onPauseOrDispose {}
        }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            Section(stringResource(R.string.setup_title)) {
                Step(stringResource(R.string.setup_enable), enabled, stringResource(R.string.setup_enable_action)) {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
                Step(stringResource(R.string.setup_select), selected, stringResource(R.string.setup_select_action)) {
                    getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                }
            }

            Section(stringResource(R.string.try_title)) {
                var text by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.try_hint)) })
                OutlinedTextField(
                    password, { password = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.try_password_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                )
            }

            Section(stringResource(R.string.diag_title)) {
                Text(stringResource(R.string.diag_note), style = MaterialTheme.typography.bodySmall)
                val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                Text(
                    stringResource(
                        R.string.diag_device, Build.MODEL, Build.VERSION.RELEASE,
                        stringResource(if (debuggable) R.string.build_debug else R.string.build_release),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Measure(stringResource(R.string.diag_key), summary.keyDraw?.let { stringResource(R.string.diag_series, it.p50, it.p95, it.max, it.count) })
                Measure(stringResource(R.string.diag_candidates), summary.candidateDraw?.let { stringResource(R.string.diag_series, it.p50, it.p95, it.max, it.count) })
                Measure(stringResource(R.string.diag_cold), summary.coldStartMs?.let { stringResource(R.string.diag_ms, it.toInt()) })
                Measure(stringResource(R.string.diag_compose), summary.composeFirstFrameMs?.let { stringResource(R.string.diag_ms, it.toInt()) })
                Measure(stringResource(R.string.diag_memory), summary.pssKb?.let { stringResource(R.string.diag_mb, it / 1024.0) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { summary = Probe.load(this@MainActivity) }) { Text(stringResource(R.string.diag_refresh)) }
                    OutlinedButton(onClick = {
                        Probe.clear(this@MainActivity)
                        summary = Probe.load(this@MainActivity)
                    }) { Text(stringResource(R.string.diag_clear)) }
                }
            }

            val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }

    @Composable
    private fun Step(text: String, done: Boolean, action: String, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            if (done) {
                Text(stringResource(R.string.setup_done), color = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = onClick) { Text(action) }
            }
        }
    }

    @Composable
    private fun Measure(label: String, value: String?) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value ?: stringResource(R.string.diag_none), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Applies Kotomo's colors (the keyboard's accent tokens) to Material 3, light or dark. */
@Composable
private fun KotomoTheme(content: @Composable () -> Unit) {
    val accent = colorResource(R.color.kb_accent)
    val onAccent = colorResource(R.color.kb_on_accent)
    val container = colorResource(R.color.kb_accent_container)
    val onContainer = colorResource(R.color.kb_on_accent_container)
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = accent, onPrimary = onAccent, primaryContainer = container, onPrimaryContainer = onContainer)
    } else {
        lightColorScheme(primary = accent, onPrimary = onAccent, primaryContainer = container, onPrimaryContainer = onContainer)
    }
    MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize(), content = content) }
}
