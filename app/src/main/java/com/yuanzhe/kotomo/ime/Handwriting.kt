package com.yuanzhe.kotomo.ime

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import com.yuanzhe.kotomo.ai.await
import com.yuanzhe.kotomo.settings.ChineseScript

/**
 * Names the languages Kotomo reads handwriting in (PLAN §12), with ML Kit's model tag: Chinese in
 * the mainland, Taiwan and Hong Kong forms, and Japanese.
 */
enum class InkLang(val tag: String) {
    CHINESE_CN("zh-Hani-CN"),
    CHINESE_TW("zh-Hani-TW"),
    CHINESE_HK("zh-Hani-HK"),
    JAPANESE("ja");

    /** True for the three Chinese models. */
    val chinese: Boolean get() = this != JAPANESE

    companion object {
        /** The Chinese model for the person's script: Simplified → mainland, Traditional and Taiwan → Taiwan, Hong Kong → Hong Kong. */
        fun chinese(script: ChineseScript): InkLang = when (script) {
            ChineseScript.SIMPLIFIED -> CHINESE_CN
            ChineseScript.TRADITIONAL, ChineseScript.TAIWAN -> CHINESE_TW
            ChineseScript.HONG_KONG -> CHINESE_HK
        }

        /** The language the panel opens in: Japanese in Japanese mode, Chinese in [script] otherwise. */
        fun forMode(mode: Mode, script: ChineseScript): InkLang = if (mode == Mode.JAPANESE) JAPANESE else chinese(script)
    }
}

/** One point of a stroke in the writing area's pixels, at [t] milliseconds. */
data class InkPoint(val x: Float, val y: Float, val t: Long)

/** A failed model download ([download] true) or recognition. */
class InkException(val download: Boolean, cause: Throwable?) : Exception(if (download) "download" else "recognize", cause)

/**
 * Reads handwriting on the device with ML Kit's digital ink recognition (PLAN §12): whether a
 * language's model is here, downloading it only when the person taps Download, recognizing the
 * strokes written so far, deleting a model.
 *
 * Notes: main thread (ML Kit calls back on it); recognition runs on ML Kit's threads, never on the
 * engine thread or the key path. Models come from Google's servers (about 20 MB per language);
 * the strokes and the text before the cursor given as context stay on the device and are neither
 * logged nor stored.
 */
class InkRecognizer : AutoCloseable {
    private val models = RemoteModelManager.getInstance()
    private val recognizers = mutableMapOf<InkLang, DigitalInkRecognizer>()

    private fun model(lang: InkLang): DigitalInkRecognitionModel =
        DigitalInkRecognitionModel.builder(checkNotNull(DigitalInkRecognitionModelIdentifier.fromLanguageTag(lang.tag))).build()

    /** Returns whether [lang]'s model is on the device. */
    suspend fun downloaded(lang: InkLang): Boolean = try {
        models.isModelDownloaded(model(lang)).await()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        throw InkException(download = false, e)
    }

    /** The languages whose model is on the device. */
    suspend fun downloaded(): Set<InkLang> = InkLang.entries.filter { downloaded(it) }.toSet()

    /**
     * Downloads [lang]'s model.
     * Side effects: ML Kit downloads from Google over any network; only because the person tapped
     * Download after the panel named Google and the size.
     */
    suspend fun download(lang: InkLang) {
        try {
            models.download(model(lang), DownloadConditions.Builder().build()).await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw InkException(download = true, e)
        }
    }

    /**
     * Recognizes [strokes] written in a [width] × [height] area as [lang].
     * Inputs: [before] is the text before the cursor; its last 20 characters help the model.
     * Returns: the candidates, best first, without blanks and repeats (at most [MAX]).
     * Notes: the model must be downloaded ([downloaded]).
     */
    suspend fun recognize(lang: InkLang, strokes: List<List<InkPoint>>, width: Float, height: Float, before: String): List<String> {
        val ink = Ink.builder()
        for (s in strokes) {
            if (s.isEmpty()) continue
            val stroke = Ink.Stroke.builder()
            s.forEach { stroke.addPoint(Ink.Point.create(it.x, it.y, it.t)) }
            ink.addStroke(stroke.build())
        }
        val context = RecognitionContext.builder().setPreContext(before.takeLast(PRE_CONTEXT)).setWritingArea(WritingArea(width, height)).build()
        return try {
            recognizer(lang).recognize(ink.build(), context).await().candidates.map { it.text }.filter { it.isNotBlank() }.distinct().take(MAX)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw InkException(download = false, e)
        }
    }

    /** Deletes [lang]'s model from the device (Side effects: frees its storage; writing in it needs it downloaded again). */
    suspend fun delete(lang: InkLang) {
        recognizers.remove(lang)?.close()
        try {
            models.deleteDownloadedModel(model(lang)).await()
        } catch (e: Exception) {
            throw InkException(download = false, e)
        }
    }

    private fun recognizer(lang: InkLang): DigitalInkRecognizer = recognizers.getOrPut(lang) {
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model(lang)).build())
    }

    override fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }

    companion object {
        /** The most candidates shown for one piece of writing. */
        const val MAX = 8
        /** How much text before the cursor the recognizer is given. */
        const val PRE_CONTEXT = 20
    }
}
