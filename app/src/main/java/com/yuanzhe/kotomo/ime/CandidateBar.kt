package com.yuanzhe.kotomo.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.yuanzhe.kotomo.R

/**
 * Draws the candidate row and reports which candidate was tapped.
 *
 * Notes: main thread. The first candidate is highlighted as the one space commits. Candidates
 * that do not fit are cut off; scrolling and the expanded candidate page arrive with P1. When
 * [show] carries a touch time, the next onDraw records press → candidates drawn in
 * [Probe.candidateDraw].
 */
@SuppressLint("ViewConstructor")
class CandidateBar(context: Context, private val onPick: (index: Int) -> Unit) : View(context) {

    private val res = context.resources
    private val barHeight = res.getDimensionPixelSize(R.dimen.candidate_height)
    private val padding = res.getDimension(R.dimen.candidate_padding)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = res.getDimension(R.dimen.candidate_text)
        color = context.getColor(R.color.kb_ink)
    }
    private val firstPaint = Paint(textPaint).apply { color = context.getColor(R.color.kb_on_accent_container) }
    private val firstBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.kb_accent_container) }
    private val dividerPaint = Paint().apply { color = context.getColor(R.color.kb_divider) }

    private var candidates = emptyList<String>()
    private var bounds = FloatArray(0)
    private var pendingProbe = 0L

    /** Replaces the candidates; [downTime] is the touch that caused it, or 0. */
    fun show(list: List<String>, downTime: Long) {
        candidates = list
        bounds = FloatArray(list.size + 1)
        list.forEachIndexed { i, text -> bounds[i + 1] = bounds[i] + textPaint.measureText(text) + padding * 2 }
        if (downTime != 0L) pendingProbe = downTime
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val baseline = height / 2 - (textPaint.descent() + textPaint.ascent()) / 2
        candidates.forEachIndexed { i, text ->
            val x = bounds[i]
            val w = bounds[i + 1] - x
            if (i == 0) {
                val inset = height * 0.12f
                canvas.drawRoundRect(x + inset, inset, x + w - inset, height - inset, height / 2f, height / 2f, firstBackground)
            } else {
                canvas.drawRect(x, height * 0.3f, x + 1, height * 0.7f, dividerPaint)
            }
            canvas.drawText(text, x + padding, baseline, if (i == 0) firstPaint else textPaint)
        }
        if (pendingProbe != 0L) {
            Probe.candidateDraw.add(SystemClock.uptimeMillis() - pendingProbe)
            pendingProbe = 0L
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val index = (0 until candidates.size).firstOrNull { event.x >= bounds[it] && event.x < bounds[it + 1] }
            if (index != null) onPick(index)
        }
        return true
    }
}
