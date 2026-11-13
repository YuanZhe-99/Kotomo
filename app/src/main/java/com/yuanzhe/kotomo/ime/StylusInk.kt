package com.yuanzhe.kotomo.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/**
 * Collects the strokes of stylus handwriting in any field (PLAN §12, Android 14 and later): the
 * system sends the pen's events to the keyboard while the person writes over a text field.
 * Notes: main thread; pure bookkeeping, recognition is the service's ([InkRecognizer]).
 */
class StylusInk {
    /** The finished strokes, oldest first. */
    val strokes = ArrayList<List<InkPoint>>()
    private var stroke: ArrayList<InkPoint>? = null

    /** The finished strokes and the one being drawn. */
    val drawn: List<List<InkPoint>> get() = strokes + listOfNotNull(stroke)

    /**
     * Adds [event] to the strokes, with its batched history points.
     * Returns: true when it ended a stroke (the pen lifted), so recognition may be scheduled.
     */
    fun add(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> stroke = arrayListOf(InkPoint(event.x, event.y, event.eventTime))
            MotionEvent.ACTION_MOVE -> stroke?.let { s ->
                for (h in 0 until event.historySize) s += InkPoint(event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalEventTime(h))
                s += InkPoint(event.x, event.y, event.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val s = stroke ?: return false
                stroke = null
                if (event.actionMasked == MotionEvent.ACTION_UP) s += InkPoint(event.x, event.y, event.eventTime)
                strokes += s
                return true
            }
        }
        return false
    }

    /** Forgets everything written. */
    fun clear() {
        strokes.clear()
        stroke = null
    }
}

/** Draws the stylus strokes over the app being written in, in the keyboard's accent color. */
class StylusInkView(context: Context, private val ink: StylusInk, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = STROKE_DP * context.resources.displayMetrics.density
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        for (s in ink.drawn) {
            if (s.isEmpty()) continue
            path.reset()
            path.moveTo(s[0].x, s[0].y)
            for (i in 1 until s.size) path.lineTo(s[i].x, s[i].y)
            if (s.size == 1) path.lineTo(s[0].x + 0.1f, s[0].y)
            canvas.drawPath(path, paint)
        }
    }

    private companion object {
        const val STROKE_DP = 3f
    }
}
