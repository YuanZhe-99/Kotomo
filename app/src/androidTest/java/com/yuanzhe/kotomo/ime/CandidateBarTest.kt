package com.yuanzhe.kotomo.ime

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuanzhe.kotomo.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A7 (device feedback 2026-11-13): a very wide candidate (a long word with its reading) never
 * fills the whole candidate bar; the next candidate stays on screen beside it, and the long one is
 * still there to pick.
 */
@RunWith(AndroidJUnit4::class)
class CandidateBarTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val res = context.resources

    /**
     * Positive: with a long first candidate, a tap just past the most a candidate may take of the
     * row (bar_candidate_max_percent) picks the second one, which is therefore on screen.
     */
    @Test
    fun aLongCandidateLeavesRoomForTheNext() {
        assertEquals(1, pickPastTheCap(listOf(LONG, "二", "三"), listOf(LONG_READING, "", "")))
    }

    /** Negative: the shortened candidate is not dropped; a tap at the row's start picks it. */
    @Test
    fun theLongCandidateStaysPickable() {
        assertEquals(0, pick(listOf(LONG, "二", "三"), listOf(LONG_READING, "", "")) { res.getDimension(R.dimen.bar_pad_start) * 2 })
    }

    /** A language badge (`日`) beside a wide candidate still fits inside `bar_candidate_max_percent`. */
    @Test
    fun longCandidateWithLanguageBadgeLeavesRoomForNext() {
        assertEquals(1, pickPastTheCap(listOf(LONG, "二", "三"), listOf(LONG_READING, "", ""), listOf("日", "中", "EN")))
        assertEquals(0, pick(listOf(LONG, "二", "三"), listOf(LONG_READING, "", ""), listOf("日", "中", "EN")) { res.getDimension(R.dimen.bar_pad_start) * 2 })
    }

    /** Taps one pill padding past the widest a candidate may be, measured from the row's start. */
    private fun pickPastTheCap(
        list: List<String>,
        readings: List<String>,
        tags: List<String> = emptyList(),
    ): Int = pick(list, readings, tags) { width ->
        val start = res.getDimension(R.dimen.bar_pad_start)
        val row = width - res.getDimension(R.dimen.bar_expand) - start
        start + row * res.getInteger(R.integer.bar_candidate_max_percent) / 100f + res.getDimension(R.dimen.bar_pill_pad)
    }

    /** Lays a bar out at the screen's width, shows [list] and returns the index a tap at [at] (of the width) picks (-1: none). */
    private fun pick(
        list: List<String>,
        readings: List<String>,
        tags: List<String> = emptyList(),
        at: (Int) -> Float,
    ): Int {
        var picked = -1
        instrumentation.runOnMainSync {
            val bar = CandidateBar(context, onPick = { picked = it }, onExpand = {}, onTool = {})
            val width = res.displayMetrics.widthPixels
            bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            bar.layout(0, 0, width, bar.measuredHeight)
            bar.show(list, 0, readings, tags)
            val x = at(width)
            val y = bar.measuredHeight / 2f
            val down = SystemClock.uptimeMillis()
            bar.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
            bar.dispatchTouchEvent(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0))
        }
        return picked
    }

    private companion object {
        /** The long Japanese proper noun of the feedback screenshot, and its reading. */
        const val LONG = "スモールヒル都市再生機構"
        const val LONG_READING = "すもーるひるとしさいせいきこう"
    }
}
