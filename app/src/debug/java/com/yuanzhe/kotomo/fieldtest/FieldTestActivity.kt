package com.yuanzhe.kotomo.fieldtest

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText

/**
 * Shows one focused text field whose input type and IME options come from the intent, so the
 * instrumentation tests can type into exactly the field kind they cover (debug builds only).
 *
 * Inputs: the extras [EXTRA_INPUT_TYPE] (default plain text) and [EXTRA_IME_OPTIONS] (default 0).
 * Notes: the field's id is [FIELD_ID]; tests read its text.
 */
class FieldTestActivity : Activity() {
    /** Creates the field and asks for the keyboard. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = EditText(this).apply {
            id = FIELD_ID
            inputType = intent.getIntExtra(EXTRA_INPUT_TYPE, InputType.TYPE_CLASS_TEXT)
            imeOptions = intent.getIntExtra(EXTRA_IME_OPTIONS, 0)
        }
        setContentView(field)
        field.requestFocus()
    }

    companion object {
        const val EXTRA_INPUT_TYPE = "inputType"
        const val EXTRA_IME_OPTIONS = "imeOptions"
        const val FIELD_ID = 0x4b01
    }
}
