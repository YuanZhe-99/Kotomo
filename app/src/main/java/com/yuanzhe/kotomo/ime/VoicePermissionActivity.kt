package com.yuanzhe.kotomo.ime

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asks for the microphone for voice typing (PLAN §12): an input method cannot ask by itself, so
 * the voice panel opens this screen, which shows only the system's permission dialog and closes.
 *
 * Side effects: when the person allows it, [reopen] is set so the keyboard opens the voice panel
 * again when it comes back.
 */
class VoicePermissionActivity : ComponentActivity() {
    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) reopen.set(true)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) ask.launch(Manifest.permission.RECORD_AUDIO)
    }

    companion object {
        /** Set after the person allowed the microphone; the keyboard takes it once. */
        val reopen = AtomicBoolean(false)
    }
}
