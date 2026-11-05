package com.yuanzhe.kotomo.ai

import android.content.Context
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.proofreading.Proofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import com.google.mlkit.genai.rewriting.Rewriter
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The languages Gemini Nano's proofreading and rewriting take that Kotomo offers (PLAN §11.1). */
enum class AiLanguage { ENGLISH, JAPANESE }

/**
 * What the person asked for: proofreading, or one of the rewriting styles (PLAN §11.1 maps the
 * draft's polish/expand/shorten/tone/keigo onto them).
 */
enum class AiAction { PROOFREAD, REPHRASE, PROFESSIONAL, FRIENDLY, ELABORATE, SHORTEN }

/** Where a feature stands on this device, from ML Kit's FeatureStatus. */
enum class AiStatus { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

/** Why a request failed, in terms the person can act on. */
enum class AiFailure { UNAVAILABLE, BACKGROUND, BUSY, QUOTA, TOO_LONG, FILTERED, CANCELLED, FAILED }

/** A failed request; [failure] says why. */
class AiException(val failure: AiFailure, cause: Throwable?) : Exception(failure.name, cause)

/**
 * Runs Gemini Nano's proofreading and rewriting through ML Kit GenAI (AICore), ported from
 * MyApps-AI's GenAiChannel: status asked before every use, downloads only when the person taps,
 * errors mapped to [AiFailure], one request at a time.
 *
 * Notes: GenAI serves only the foreground app, so this runs in Kotomo's AI screen, never in the
 * keyboard's service (PLAN §3.2, §11.1). Nothing is logged: neither the text nor the results.
 */
class GenAi(private val context: Context, private val language: AiLanguage) : AutoCloseable {
    private var proofreader: Proofreader? = null
    private val rewriters = mutableMapOf<AiAction, Rewriter>()

    private fun proofreader() = proofreader ?: Proofreading.getClient(
        ProofreaderOptions.builder(context)
            .setInputType(ProofreaderOptions.InputType.KEYBOARD)
            .setLanguage(if (language == AiLanguage.JAPANESE) ProofreaderOptions.Language.JAPANESE else ProofreaderOptions.Language.ENGLISH)
            .build(),
    ).also { proofreader = it }

    private fun rewriter(action: AiAction) = rewriters.getOrPut(action) {
        val type = when (action) {
            AiAction.REPHRASE -> RewriterOptions.OutputType.REPHRASE
            AiAction.PROFESSIONAL -> RewriterOptions.OutputType.PROFESSIONAL
            AiAction.FRIENDLY -> RewriterOptions.OutputType.FRIENDLY
            AiAction.ELABORATE -> RewriterOptions.OutputType.ELABORATE
            AiAction.SHORTEN -> RewriterOptions.OutputType.SHORTEN
            AiAction.PROOFREAD -> error("proofreading is not a rewriting style")
        }
        Rewriting.getClient(
            RewriterOptions.builder(context)
                .setOutputType(type)
                .setLanguage(if (language == AiLanguage.JAPANESE) RewriterOptions.Language.JAPANESE else RewriterOptions.Language.ENGLISH)
                .build(),
        )
    }

    /** Asks AICore whether [action] can run now. Returns: its status. */
    suspend fun status(action: AiAction): AiStatus = call {
        val code = if (action == AiAction.PROOFREAD) proofreader().checkFeatureStatus().await() else rewriter(action).checkFeatureStatus().await()
        when (code) {
            FeatureStatus.AVAILABLE -> AiStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> AiStatus.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> AiStatus.DOWNLOADING
            else -> AiStatus.UNAVAILABLE
        }
    }

    /**
     * Downloads the model [action] needs, only because the person asked.
     * Inputs: [progress] receives (bytes so far, total or -1).
     * Side effects: AICore downloads through the system.
     */
    suspend fun download(action: AiAction, progress: (Long, Long) -> Unit) = call {
        var total = -1L
        suspendCancellableCoroutine { cont ->
            val callback = object : DownloadCallback {
                override fun onDownloadStarted(bytesToDownload: Long) {
                    total = bytesToDownload
                    progress(0, total)
                }
                override fun onDownloadProgress(totalBytesDownloaded: Long) = progress(totalBytesDownloaded, total)
                override fun onDownloadCompleted() { if (cont.isActive) cont.resume(Unit) }
                override fun onDownloadFailed(e: GenAiException) { if (cont.isActive) cont.resumeWithException(e) }
            }
            val future = if (action == AiAction.PROOFREAD) proofreader().downloadFeature(callback) else rewriter(action).downloadFeature(callback)
            cont.invokeOnCancellation { future.cancel(true) }
        }
    }

    /**
     * Runs [action] on [text].
     * Returns: the suggestions, best first (several for rewriting).
     * Notes: the APIs take under 256 tokens; longer text is split into paragraphs and lines first,
     * each part converted alone and joined back, so only one suggestion results then.
     */
    suspend fun run(action: AiAction, text: String): List<String> = call {
        val parts = split(text)
        if (parts.size == 1) {
            once(action, text)
        } else {
            val sb = StringBuilder()
            for ((part, separator) in parts) sb.append(once(action, part).firstOrNull() ?: part).append(separator)
            listOf(sb.toString())
        }
    }

    private suspend fun once(action: AiAction, text: String): List<String> =
        if (action == AiAction.PROOFREAD) {
            proofreader().runInference(ProofreadingRequest.builder(text).build()).await().results.map { it.text }
        } else {
            rewriter(action).runInference(RewritingRequest.builder(text).build()).await().results.map { it.text }
        }.filter { it.isNotBlank() }.distinct()

    override fun close() {
        proofreader?.close()
        rewriters.values.forEach { it.close() }
        rewriters.clear()
    }

    companion object {
        /** Keeps each part safely under the APIs' 256-token limit. */
        private const val PART_CHARS = 400

        /**
         * Splits [text] into parts of at most [PART_CHARS] characters at line breaks, then at
         * sentence ends, each paired with the separator that followed it.
         */
        internal fun split(text: String): List<Pair<String, String>> {
            if (text.length <= PART_CHARS) return listOf(text to "")
            val out = mutableListOf<Pair<String, String>>()
            val current = StringBuilder()
            fun flush(separator: String) {
                if (current.isNotEmpty()) out += current.toString() to separator
                current.clear()
            }
            val sentences = Regex("(?<=[.!?。！？\\n])").split(text).filter { it.isNotEmpty() }
            for (s in sentences) {
                if (current.length + s.length > PART_CHARS) flush("")
                current.append(s)
            }
            flush("")
            return out
        }

        /** Maps an ML Kit error to what the person can act on. */
        internal fun failureOf(error: Throwable): AiFailure {
            val cause = if (error is ExecutionException) error.cause ?: error else error
            if (cause is GenAiException) {
                return when (cause.errorCode) {
                    GenAiException.ErrorCode.NOT_AVAILABLE, GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE,
                    GenAiException.ErrorCode.AICORE_INCOMPATIBLE, GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
                    -> AiFailure.UNAVAILABLE
                    GenAiException.ErrorCode.REQUEST_TOO_LARGE -> AiFailure.TOO_LONG
                    GenAiException.ErrorCode.BUSY -> AiFailure.BUSY
                    GenAiException.ErrorCode.CANCELLED -> AiFailure.CANCELLED
                    else -> fromMessage(cause.message)
                }
            }
            if (cause is IllegalStateException && cause.message.orEmpty().contains("not available")) return AiFailure.UNAVAILABLE
            return AiFailure.FAILED
        }

        /**
         * Reads what beta1 has no code for from the message: background use, the battery quota and
         * safety refusals.
         */
        private fun fromMessage(message: String?): AiFailure {
            val m = message.orEmpty().lowercase()
            return when {
                "background" in m -> AiFailure.BACKGROUND
                "quota" in m -> AiFailure.QUOTA
                listOf("safety", "filter", "blocked", "guardrail", "harmful").any { it in m } -> AiFailure.FILTERED
                else -> AiFailure.FAILED
            }
        }

        /** Runs [body], turning any ML Kit error into an [AiException]. */
        private suspend fun <T> call(body: suspend () -> T): T = try {
            body()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: AiException) {
            throw e
        } catch (e: Throwable) {
            throw AiException(failureOf(e), e)
        }

        /** Awaits a ListenableFuture, cancelling it with the coroutine. */
        private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
            addListener({
                if (cont.isActive) {
                    try {
                        cont.resume(get())
                    } catch (e: Throwable) {
                        cont.resumeWithException(e)
                    }
                }
            }, Executor { it.run() })
            cont.invokeOnCancellation { cancel(true) }
        }
    }
}
