package com.yuanzhe.kotomo.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.withClip
import com.yuanzhe.kotomo.R

/** The toolbar's buttons, in the design's order. */
enum class Tool { MORE, CLIPBOARD, AI, TRANSLATE, SETTINGS }

/**
 * Draws the row above the keys: candidates while composing, otherwise the toolbar, or a short
 * status [hint].
 *
 * Notes: main thread. As in the design, the first candidate is a highlighted pill (what space
 * commits), the others plain; the row scrolls sideways when dragged, and the arrow at the end
 * opens every candidate ([onExpand]). When [show] carries a touch time, the next onDraw records
 * press → candidates drawn in [Probe.candidateDraw].
 */
@SuppressLint("ViewConstructor")
class CandidateBar(
    context: Context,
    private val onPick: (index: Int) -> Unit,
    private val onExpand: () -> Unit,
    private val onTool: (Tool) -> Unit,
    /** Receives a long press on candidate n (forget what was learned about it). */
    private val onLongPick: (index: Int) -> Unit = {},
) : View(context) {

    private val res = context.resources
    private val barHeight = res.getDimensionPixelSize(R.dimen.bar_height)
    private val padStart = res.getDimension(R.dimen.bar_pad_start)
    private val pillHeight = res.getDimension(R.dimen.bar_pill_height)
    private val padFirst = res.getDimension(R.dimen.bar_pill_pad_first)
    private val pad = res.getDimension(R.dimen.bar_pill_pad)
    private val expandWidth = res.getDimension(R.dimen.bar_expand)
    private val toolbarPad = res.getDimension(R.dimen.toolbar_pad)
    private val toolButton = res.getDimension(R.dimen.toolbar_button)
    private val iconSize = res.getDimensionPixelSize(R.dimen.kb_icon)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = res.getDimension(R.dimen.bar_text)
        color = KeyboardColors.color(context, R.color.kb_ink)
    }
    private val firstPaint = Paint(textPaint).apply {
        color = KeyboardColors.color(context, R.color.kb_on_accent_container)
        isFakeBoldText = true
    }
    private val hintPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = res.getDimension(R.dimen.bar_hint_text)
        color = KeyboardColors.color(context, R.color.kb_ink_secondary)
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KeyboardColors.color(context, R.color.kb_accent_container) }
    private val inkColor = KeyboardColors.color(context, R.color.kb_ink)

    private fun icon(id: Int) = context.getDrawable(id)!!.mutate().apply { setTint(inkColor) }
    private val expandIcon = icon(R.drawable.ic_bar_expand)
    private val tools = listOf(
        Tool.MORE to icon(R.drawable.ic_tool_more),
        Tool.CLIPBOARD to icon(R.drawable.ic_tool_clipboard),
        Tool.AI to icon(R.drawable.ic_tool_ai),
        Tool.TRANSLATE to icon(R.drawable.ic_tool_translate),
        Tool.SETTINGS to icon(R.drawable.ic_tool_settings),
    )

    private var candidates = emptyList<String>()
    /** Left edges of the candidates in content coordinates, plus the end of the last one. */
    private var bounds = FloatArray(1)
    private var scroll = 0f
    private var pendingProbe = 0L
    private val box = RectF()

    /** A short status line shown in place of the toolbar when there are no candidates, or null. */
    var hint: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Replaces the candidates; [downTime] is the touch that caused it, or 0. */
    fun show(list: List<String>, downTime: Long) {
        if (list != candidates) scroll = 0f
        candidates = list
        bounds = FloatArray(list.size + 1)
        bounds[0] = padStart
        list.forEachIndexed { i, text ->
            bounds[i + 1] = bounds[i] + textPaint.measureText(text) + (if (i == 0) padFirst else pad) * 2
        }
        if (downTime != 0L) pendingProbe = downTime
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barHeight)
    }

    private val listEnd get() = width - expandWidth

    override fun onDraw(canvas: Canvas) {
        when {
            candidates.isNotEmpty() -> drawCandidates(canvas)
            hint != null -> {
                val baseline = height / 2 - (hintPaint.descent() + hintPaint.ascent()) / 2
                val shown = android.text.TextUtils.ellipsize(hint, hintPaint, width - (padStart + pad) * 2, android.text.TextUtils.TruncateAt.END)
                canvas.drawText(shown, 0, shown.length, padStart + pad, baseline, hintPaint)
            }
            else -> drawToolbar(canvas)
        }
        if (pendingProbe != 0L) {
            Probe.candidateDraw.add(SystemClock.uptimeMillis() - pendingProbe)
            pendingProbe = 0L
        }
    }

    private fun drawCandidates(canvas: Canvas) {
        val baseline = height / 2 - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.withClip(0f, 0f, listEnd, height.toFloat()) {
            candidates.forEachIndexed { i, text ->
                val x = bounds[i] - scroll
                if (x > listEnd || bounds[i + 1] - scroll < 0) return@forEachIndexed
                if (i == 0) {
                    val top = (height - pillHeight) / 2
                    box.set(x, top, bounds[1] - scroll, top + pillHeight)
                    drawRoundRect(box, pillHeight / 2, pillHeight / 2, pillPaint)
                    drawText(text, x + padFirst, baseline, firstPaint)
                } else {
                    drawText(text, x + pad, baseline, textPaint)
                }
            }
        }
        drawIcon(canvas, expandIcon, listEnd + expandWidth / 2)
    }

    private fun toolCenters(): List<Float> {
        val start = toolbarPad + toolButton / 2
        val end = width - toolbarPad - toolButton / 2
        return tools.indices.map { start + (end - start) * it / (tools.size - 1) }
    }

    private fun drawToolbar(canvas: Canvas) {
        toolCenters().forEachIndexed { i, x -> drawIcon(canvas, tools[i].second, x) }
    }

    private fun drawIcon(canvas: Canvas, icon: android.graphics.drawable.Drawable, centerX: Float) {
        val l = (centerX - iconSize / 2).toInt()
        val t = (height - iconSize) / 2
        icon.setBounds(l, t, l + iconSize, t + iconSize)
        icon.draw(canvas)
    }

    private var downX = 0f
    private var lastX = 0f
    private var dragging = false
    private var longPressed = false
    private val longPress = Runnable {
        if (dragging || candidates.isEmpty() || downX >= listEnd) return@Runnable
        val cx = downX + scroll
        val index = candidates.indices.firstOrNull { cx >= bounds[it] && cx < bounds[it + 1] } ?: return@Runnable
        longPressed = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onLongPick(index)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                lastX = event.x
                dragging = false
                longPressed = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (candidates.isNotEmpty()) {
                if (!dragging && kotlin.math.abs(event.x - downX) > touchSlop) {
                    dragging = true
                    removeCallbacks(longPress)
                }
                if (dragging) {
                    val max = (bounds.last() + pad - listEnd).coerceAtLeast(0f)
                    scroll = (scroll - (event.x - lastX)).coerceIn(0f, max)
                    invalidate()
                }
                lastX = event.x
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!dragging && !longPressed) tap(event.x)
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }

    private fun tap(x: Float) {
        when {
            candidates.isNotEmpty() -> if (x >= listEnd) {
                onExpand()
            } else {
                val cx = x + scroll
                val index = candidates.indices.firstOrNull { cx >= bounds[it] && cx < bounds[it + 1] }
                if (index != null) onPick(index)
            }
            hint == null -> {
                val centers = toolCenters()
                val i = centers.indices.minByOrNull { kotlin.math.abs(centers[it] - x) } ?: return
                if (kotlin.math.abs(centers[i] - x) <= toolButton / 2) onTool(tools[i].first)
            }
        }
    }
}
