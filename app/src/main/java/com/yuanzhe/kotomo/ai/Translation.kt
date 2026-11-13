package com.yuanzhe.kotomo.ai

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Names the languages Kotomo translates between with ML Kit's on-device translation (PLAN §11.1),
 * with ML Kit's language code.
 * Notes: ML Kit pivots every pair without English through English, so Chinese ↔ Japanese is two
 * translations; its Chinese output is Simplified.
 */
enum class TranslateLang(val code: String) {
    CHINESE(TranslateLanguage.CHINESE),
    ENGLISH(TranslateLanguage.ENGLISH),
    JAPANESE(TranslateLanguage.JAPANESE);

    companion object {
        /**
         * Guesses the language [text] is written in.
         * Returns: Japanese when it has kana; Chinese when its Chinese characters outnumber its
         * Latin words (`我用 Android 手机`); English otherwise.
         * Notes: pure; the panel lets the person change it.
         */
        fun guess(text: String): TranslateLang {
            if (text.any { it in '\u3040'..'\u30ff' }) return JAPANESE
            val han = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
            val words = Regex("[A-Za-z]+").findAll(text).count()
            return if (han > words) CHINESE else ENGLISH
        }

        /** The language [source] text is translated into first: English, or Chinese from English. */
        fun defaultTarget(source: TranslateLang): TranslateLang = if (source == ENGLISH) CHINESE else ENGLISH
    }
}

/** Why a translation could not run, in terms the person can act on. */
enum class TranslateFailure { DOWNLOAD, FAILED }

/** A failed download or translation; [failure] says which. */
class TranslateException(val failure: TranslateFailure, cause: Throwable?) : Exception(failure.name, cause)

/**
 * Translates text on the device with ML Kit's translation (PLAN §11.1): which language models are
 * on the device, downloading one only when the person taps Download, translating, deleting a model.
 *
 * Notes: main thread (ML Kit calls back on it); the work itself runs on ML Kit's threads, never on
 * the engine thread or the key path. It is a standard ML Kit API with no foreground rule, so it runs
 * in the keyboard. Models come from Google's servers (about 30 MB per language); nothing typed is
 * sent, and neither the text nor the result is logged or stored.
 */
class Translations : AutoCloseable {
    private val models = RemoteModelManager.getInstance()
    private val translators = mutableMapOf<Pair<TranslateLang, TranslateLang>, Translator>()

    /** Returns the languages whose model is on the device. */
    suspend fun downloaded(): Set<TranslateLang> {
        val codes = try {
            models.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()
        } catch (e: Exception) {
            throw TranslateException(TranslateFailure.FAILED, e)
        }
        return TranslateLang.entries.filter { it.code in codes }.toSet()
    }

    /**
     * Downloads the models a [source] → [target] translation needs that are missing.
     * Side effects: ML Kit downloads from Google over any network; only because the person tapped
     * Download after the panel named Google and the size.
     */
    suspend fun download(source: TranslateLang, target: TranslateLang) {
        try {
            translator(source, target).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw TranslateException(TranslateFailure.DOWNLOAD, e)
        }
    }

    /**
     * Translates [text] from [source] into [target].
     * Returns: the translation. Notes: the models must be downloaded ([downloaded]).
     */
    suspend fun translate(text: String, source: TranslateLang, target: TranslateLang): String = try {
        translator(source, target).translate(text).await()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        throw TranslateException(TranslateFailure.FAILED, e)
    }

    /** Deletes [lang]'s model from the device (Side effects: frees its storage; translating needs it downloaded again). */
    suspend fun delete(lang: TranslateLang) {
        try {
            models.deleteDownloadedModel(TranslateRemoteModel.Builder(lang.code).build()).await()
        } catch (e: Exception) {
            throw TranslateException(TranslateFailure.FAILED, e)
        }
    }

    private fun translator(source: TranslateLang, target: TranslateLang): Translator = translators.getOrPut(source to target) {
        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source.code).setTargetLanguage(target.code).build())
    }

    override fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }

    companion object {
        /** The languages a [source] → [target] translation needs on the device: both, and English as ML Kit's pivot. */
        fun needs(source: TranslateLang, target: TranslateLang): Set<TranslateLang> = setOf(source, target, TranslateLang.ENGLISH)
    }
}

/** Awaits a Play services Task, resuming with its result or its exception (ML Kit's calls). */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        if (!cont.isActive) return@addOnCompleteListener
        val e = task.exception
        when {
            e != null -> cont.resumeWithException(e)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
