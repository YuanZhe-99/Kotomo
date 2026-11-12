package com.yuanzhe.kotomo.fieldtest

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import androidx.annotation.RequiresApi
import androidx.autofill.inline.v1.InlineSuggestionUi

/**
 * Answers every fill request with one inline suggestion that fills the first text field with
 * [VALUE], for the inline autofill instrumentation test (debug builds only; PLAN §10.10).
 * Notes: the test makes it the system's autofill service and restores the previous one after;
 * without an inline request (a keyboard that asks for none), and before Android 13, it answers
 * with nothing.
 */
class FakeAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return callback.onSuccess(null)
        answer(request, callback)
    }

    /** Answers [request] with one inline suggestion, or nothing without an inline request or a text field. */
    // Content.getSlice is how an autofill service hands the content to the platform (the library's
    // own samples do the same), but the library marks it restricted.
    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun answer(request: FillRequest, callback: FillCallback) {
        val inline = request.inlineSuggestionsRequest
        val id = request.fillContexts.lastOrNull()?.structure?.let(::firstText)
        if (inline == null || id == null) return callback.onSuccess(null)
        val spec = inline.inlinePresentationSpecs.first()
        val attribution = PendingIntent.getService(this, 0, Intent(this, FakeAutofillService::class.java), PendingIntent.FLAG_IMMUTABLE)
        val slice = InlineSuggestionUi.newContentBuilder(attribution).setTitle(TITLE).build().slice
        val presentations = Presentations.Builder().setInlinePresentation(InlinePresentation(slice, spec, false)).build()
        val dataset = Dataset.Builder(presentations).setField(id, Field.Builder().setValue(AutofillValue.forText(VALUE)).build()).build()
        callback.onSuccess(FillResponse.Builder().addDataset(dataset).build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()

    /** Finds the first text field in [structure]. */
    private fun firstText(structure: AssistStructure): AutofillId? {
        fun walk(node: AssistStructure.ViewNode): AutofillId? {
            if (node.autofillType == View.AUTOFILL_TYPE_TEXT && node.autofillId != null) return node.autofillId
            for (i in 0 until node.childCount) walk(node.getChildAt(i))?.let { return it }
            return null
        }
        for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode)?.let { return it }
        return null
    }

    companion object {
        /** What the suggestion fills in, and its title. */
        const val VALUE = "filled-by-autofill"
        const val TITLE = "Kotomo test account"
    }
}
