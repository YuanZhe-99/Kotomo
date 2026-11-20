package com.yuanzhe.kotomo.fieldtest

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.core.view.ViewCompat

/**
 * Shows one focused text field whose input type and IME options come from the intent, so the
 * instrumentation tests can type into exactly the field kind they cover (debug builds only).
 *
 * Inputs: the extras [EXTRA_INPUT_TYPE] (default plain text), [EXTRA_IME_OPTIONS] (default 0) and
 * [EXTRA_ACCEPT_IMAGES] (default false; accepts image MIME types via rich content when true).
 * Notes: the field's id is [FIELD_ID]; tests read its text, [receivedMime] and [receivedSize].
 */
class FieldTestActivity : Activity() {
    /** The MIME type of the last image committed into the field, or null. */
    @Volatile var receivedMime: String? = null
    /** The decoded byte count read from the last committed image URI, or 0. */
    @Volatile var receivedSize: Int = 0

    /** Creates the field and asks for the keyboard. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = EditText(this).apply {
            id = FIELD_ID
            inputType = intent.getIntExtra(EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            imeOptions = intent.getIntExtra(EXTRA_IME_OPTIONS, 0)
        }
        if (intent.getBooleanExtra(EXTRA_ACCEPT_IMAGES, false)) {
            ViewCompat.setOnReceiveContentListener(field, arrayOf("image/*")) { _, payload ->
                val clip = payload.clip
                if (clip.itemCount > 0) {
                    val uri = clip.getItemAt(0).uri
                    val mime = clip.description?.takeIf { it.mimeTypeCount > 0 }?.getMimeType(0)
                        ?: uri?.let { contentResolver.getType(it) }
                    val size = uri?.let {
                        runCatching { contentResolver.openInputStream(it)?.use { s -> s.readBytes().size } }.getOrNull()
                    } ?: 0
                    receivedMime = mime
                    receivedSize = size
                }
                null
            }
        }
        setContentView(field)
        field.requestFocus()
    }

    companion object {
        const val EXTRA_INPUT_TYPE = "inputType"
        const val EXTRA_IME_OPTIONS = "imeOptions"
        const val EXTRA_ACCEPT_IMAGES = "acceptImages"
        const val FIELD_ID = 0x4b01
    }
}
