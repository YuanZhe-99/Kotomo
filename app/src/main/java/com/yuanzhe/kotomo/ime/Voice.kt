package com.yuanzhe.kotomo.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.yuanzhe.kotomo.settings.ChineseScript
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Names the languages of voice typing (PLAN §12) with their BCP 47 tag. */
enum class VoiceLang(val tag: String) {
    CHINESE_CN("zh-CN"),
    CHINESE_TW("zh-TW"),
    ENGLISH("en-US"),
    JAPANESE("ja-JP");

    companion object {
        /** The Chinese for the person's script: Taiwan Mandarin for Traditional, Taiwan and Hong Kong, mainland otherwise. */
        fun chinese(script: ChineseScript): VoiceLang = if (script == ChineseScript.SIMPLIFIED) CHINESE_CN else CHINESE_TW

        /** The language voice typing starts in: the mode's language; Chinese in Chinese and Mixed mode. */
        fun forMode(mode: Mode, script: ChineseScript): VoiceLang = when (mode) {
            Mode.JAPANESE -> JAPANESE
            Mode.ENGLISH, Mode.PC -> ENGLISH
            Mode.CHINESE, Mode.MIXED -> chinese(script)
        }
    }
}

/** Whether the device's on-device recognizer has a language's model. */
enum class VoiceModel { INSTALLED, DOWNLOADABLE, DOWNLOADING, UNSUPPORTED, UNKNOWN }

/** What a voice typing session is doing, as the panel shows it. */
enum class VoiceState { IDLE, LISTENING, NO_MATCH, NO_PERMISSION, NEEDS_MODEL, FAILED }

/**
 * Runs voice typing through Android's on-device speech recognition (PLAN §12): one recognizer
 * while the voice panel is open, partial results while the person speaks, the final text.
 *
 * Notes: main thread (SpeechRecognizer calls back on it). Only the on-device recognizer is used
 * (`SpeechRecognizer.createOnDeviceSpeechRecognizer`, Android 12 and later): the system's own
 * recognition service records and recognizes on the device; Kotomo never records audio itself and
 * never uses a recognizer that may send audio over the network. Audio and text are neither logged
 * nor stored. The microphone is in use only between [start] and the final result, [stop] or [close].
 */
class Voice(private val context: Context, private val onState: (VoiceState) -> Unit, private val onPartial: (String) -> Unit, private val onFinal: (String) -> Unit) : AutoCloseable {
    private var recognizer: SpeechRecognizer? = null

    /**
     * Starts listening in [lang].
     * Side effects: the system's on-device recognizer opens the microphone until a final result.
     */
    fun start(lang: VoiceLang) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !available(context)) return onState(VoiceState.FAILED)
        if (!permitted(context)) return onState(VoiceState.NO_PERMISSION)
        val r = recognizer()
        r.cancel()
        onState(VoiceState.LISTENING)
        r.startListening(intent(lang))
    }

    /**
     * Asks the system to download [lang]'s speech model (Android 13 and later).
     * Side effects: the system's speech service downloads the model from its provider; only
     * because the person tapped Download under the notice saying so.
     */
    fun download(lang: VoiceLang) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !available(context)) return
        recognizer().triggerModelDownload(intent(lang))
    }

    /** The panel's one on-device recognizer, created on first use. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun recognizer(): SpeechRecognizer = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
        it.setRecognitionListener(listener)
        recognizer = it
    }

    /** Stops listening; what was heard so far still becomes the final result. */
    fun stop() {
        recognizer?.stopListening()
    }

    override fun close() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle) {
            best(partialResults)?.let(onPartial)
        }

        override fun onResults(results: Bundle) {
            val text = best(results)
            if (text.isNullOrBlank()) return onState(VoiceState.NO_MATCH)
            onFinal(text)
            onState(VoiceState.IDLE)
        }

        override fun onError(error: Int) = onState(stateFor(error))
    }

    companion object {
        /** Returns whether this device has on-device speech recognition (Android 12 and later). */
        fun available(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        /** Returns whether Kotomo may use the microphone. */
        fun permitted(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Maps a SpeechRecognizer error code to what the panel says. */
        fun stateFor(error: Int): VoiceState = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceState.NO_PERMISSION
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceState.NO_MATCH
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceState.NEEDS_MODEL
            else -> VoiceState.FAILED
        }

        /**
         * Asks the on-device recognizer whether it has [lang]'s model (Android 13 and later).
         * Returns: [VoiceModel.UNKNOWN] before Android 13 or when the recognizer does not say.
         */
        suspend fun model(context: Context, lang: VoiceLang): VoiceModel {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !available(context)) return VoiceModel.UNKNOWN
            return support(context, lang)
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private suspend fun support(context: Context, lang: VoiceLang): VoiceModel = suspendCancellableCoroutine { cont ->
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            cont.invokeOnCancellation { r.destroy() }
            r.checkRecognitionSupport(intent(lang), context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    r.destroy()
                    if (cont.isActive) cont.resume(modelOf(lang, support.installedOnDeviceLanguages, support.pendingOnDeviceLanguages, support.supportedOnDeviceLanguages))
                }

                override fun onError(error: Int) {
                    r.destroy()
                    if (cont.isActive) cont.resume(VoiceModel.UNKNOWN)
                }
            })
        }

        /** Places [lang] among the recognizer's installed, pending and supported languages (pure). */
        fun modelOf(lang: VoiceLang, installed: List<String>, pending: List<String>, supported: List<String>): VoiceModel {
            fun has(list: List<String>) = list.any { it.equals(lang.tag, ignoreCase = true) || it.replace('_', '-').equals(lang.tag, ignoreCase = true) }
            return when {
                has(installed) -> VoiceModel.INSTALLED
                has(pending) -> VoiceModel.DOWNLOADING
                has(supported) -> VoiceModel.DOWNLOADABLE
                installed.isEmpty() && pending.isEmpty() && supported.isEmpty() -> VoiceModel.UNKNOWN
                else -> VoiceModel.UNSUPPORTED
            }
        }

        private fun intent(lang: VoiceLang) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

        private fun best(results: Bundle): String? = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }
}
