package com.yuanzhe.kotomo.ai

/**
 * One request the keyboard hands to the AI screen: the text, where it sits in the field, and the
 * field it came from.
 *
 * Inputs: [start] and [end] are absolute offsets in the field; [app] and [fieldId] come from the
 * field's EditorInfo, so the answer goes back only to the same field.
 */
data class AiRequest(
    val app: String,
    val fieldId: Int,
    val text: String,
    val start: Int,
    val end: Int,
    val language: AiLanguage,
)

/**
 * Carries an AI request from the keyboard to [AiActivity] and the confirmed result back.
 *
 * Notes: main thread; both sides run in the app's main process. Held only in memory: nothing is
 * written to disk, and a process restart drops it.
 */
object AiHandoff {
    /** The request the AI screen works on, or null. */
    var request: AiRequest? = null
        private set

    /** The text the person confirmed with Replace, or null. */
    var result: String? = null
        private set

    /** Starts a new hand-off with [r], dropping any older one. */
    fun begin(r: AiRequest) {
        request = r
        result = null
    }

    /** Records the text the person confirmed. */
    fun confirm(text: String) {
        result = text
    }

    /**
     * Returns the request with its confirmed result and clears both, or null when nothing was
     * confirmed.
     */
    fun take(): Pair<AiRequest, String>? {
        val r = request ?: return null
        val text = result ?: return null
        request = null
        result = null
        return r to text
    }

    /** Drops the hand-off (the person cancelled). */
    fun clear() {
        request = null
        result = null
    }
}
