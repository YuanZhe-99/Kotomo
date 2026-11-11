package com.yuanzhe.kotomo.ime

import android.content.Context
import androidx.core.content.edit

/**
 * Keeps the most recent [capacity] latency samples in milliseconds and reports percentiles.
 *
 * Notes: main thread only. Spike S1's measurement; samples are durations, never text.
 */
class LatencyStats(private val capacity: Int = 512) {
    private val samples = LongArray(capacity)
    private var next = 0

    /** Number of samples held, at most [capacity]. */
    var count = 0
        private set

    /** Adds one sample, replacing the oldest once full. */
    fun add(ms: Long) {
        samples[next] = ms
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /**
     * Returns the [p]th percentile (0–100, nearest rank) of the held samples, or null when empty.
     */
    fun percentile(p: Int): Long? {
        if (count == 0) return null
        val sorted = samples.copyOf(count).also { it.sort() }
        val rank = ((p / 100.0) * count).let { kotlin.math.ceil(it).toInt() }.coerceIn(1, count)
        return sorted[rank - 1]
    }

    fun clear() {
        count = 0
        next = 0
    }
}

/**
 * Records spike S1's measurements in the input method and stores their summary for the settings
 * app's diagnostics page.
 *
 * Notes: the behavior contract allows timings and sizes only; nothing typed is recorded. Writes
 * use SharedPreferences.apply, which writes on a background thread, never on the key path.
 */
object Probe {
    /** Touch down → the keyboard's next onDraw (the pressed key highlighted). */
    val keyDraw = LatencyStats()
    /**
     * Key typed (the finger lifted, when the key takes effect) → the candidate bar's next onDraw
     * with the engine's result. Until 2026-11-11 this counted from the touch down, so it also held
     * how long the finger rested on the key.
     */
    val candidateDraw = LatencyStats()
    /** One key's work on the engine thread (the engine calls and their JNI crossings), per key that reached an engine. */
    val engineWork = LatencyStats()
    /** Service onCreate → first keyboard onDraw, for the most recent cold start. */
    var coldStartMs: Long? = null
    /** Emoji panel (Compose) attach → first onDraw, for the first opening in this process. */
    var composeFirstFrameMs: Long? = null
    /** Process PSS in kilobytes, sampled when the keyboard was last shown. */
    var pssKb: Long? = null

    private const val PREFS = "probe"

    /** Snapshot of the stored measurements as the diagnostics page shows them. */
    data class Summary(
        val keyDraw: Series?,
        val candidateDraw: Series?,
        val engineWork: Series?,
        val coldStartMs: Long?,
        val composeFirstFrameMs: Long?,
        val pssKb: Long?,
        val rimeDeployMs: Long?,
        val rimeDeployPeakKb: Long?,
    )

    /** Percentiles of one latency series. */
    data class Series(val count: Int, val p50: Long, val p95: Long, val max: Long)

    private fun LatencyStats.series() =
        percentile(50)?.let { Series(count, it, percentile(95)!!, percentile(100)!!) }

    /** Stores the current summary. Side effects: asynchronous SharedPreferences write. */
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            fun put(name: String, s: Series?) {
                if (s == null) return
                putInt("$name.count", s.count)
                putLong("$name.p50", s.p50)
                putLong("$name.p95", s.p95)
                putLong("$name.max", s.max)
            }
            put("key", keyDraw.series())
            put("typed_candidates", candidateDraw.series())
            put("engine", engineWork.series())
            coldStartMs?.let { putLong("cold", it) }
            composeFirstFrameMs?.let { putLong("compose", it) }
            pssKb?.let { putLong("pss", it) }
        }
    }

    /** Reads the stored summary; missing values are null. */
    fun load(context: Context): Summary {
        val deploy = RimeFiles(context).readReport()
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun series(name: String) = if (p.contains("$name.count")) {
            Series(p.getInt("$name.count", 0), p.getLong("$name.p50", 0), p.getLong("$name.p95", 0), p.getLong("$name.max", 0))
        } else {
            null
        }
        fun long(name: String) = if (p.contains(name)) p.getLong(name, 0) else null
        return Summary(series("key"), series("typed_candidates"), series("engine"), long("cold"), long("compose"), long("pss"), deploy?.first, deploy?.second)
    }

    /** Clears stored and in-memory measurements. */
    fun clear(context: Context) {
        keyDraw.clear()
        candidateDraw.clear()
        engineWork.clear()
        coldStartMs = null
        composeFirstFrameMs = null
        pssKb = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
    }
}
