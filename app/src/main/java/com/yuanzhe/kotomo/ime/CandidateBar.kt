package com.yuanzhe.kotomo.ime

import androidx.core.content.res.ResourcesCompat
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
    /** Receives a long press on candidate n (show its details). */
    private val onLongPick: (index: Int) -> Unit = {},
    /** Receives a tap on the details: the Forget button (its index), or elsewhere (null: close). */
    private val onDetails: (forget: Int?) -> Unit = {},
    /** Receives a tap on the emoji search's query (leave the search). */
    private val onSearchTap: () -> Unit = {},
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

    private fun icon(id: Int) = ResourcesCompat.getDrawable(context.resources, id, context.theme)!!.mutate().apply { setTint(inkColor) }
    private val expandIcon = icon(R.drawable.ic_bar_expand)
    private val searchIcon = icon(R.drawable.ic_key_search)
    private val searchPrompt = res.getString(R.string.emoji_search_prompt)
    private val toolIcons = HashMap<Tool, android.graphics.drawable.Drawable>()

    /**
     * The tools the person put on the toolbar, in order, after More (PLAN §10.10); the bar shows
     * as many as fit ([shownTools]) and the rest wait under More.
     */
    var tools: List<Tool> = Tool.configurable.filter { it !in Tool.offBar }
        set(value) {
            field = value
            invalidate()
        }

    /** The tools the bar has room for: More, then the person's tools while they fit. */
    val shownTools: List<Tool>
        get() {
            val room = if (width == 0) Int.MAX_VALUE else ((width - toolbarPad * 2) / toolButton).toInt().coerceAtLeast(1)
            return (listOf(Tool.MORE) + tools).take(room)
        }

    private var candidates = emptyList<String>()
    /** A short note drawn after a candidate (a Japanese reading), by index; "" or missing for none. */
    private var notes = emptyList<String>()
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

    /**
     * The details of one candidate (its reading and where it comes from), shown in place of the
     * row after a long press, or null; [CandidateDetails.forget] labels the Forget button.
     */
    var details: CandidateDetails? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * The emoji search's query while the keys type into it (PLAN §8.6): drawn with a magnifier
     * before the results ("" shows the prompt); null when not searching. A tap on it leaves the
     * search ([onSearchTap]).
     */
    var search: String? = null
        set(value) {
            field = value
            measure()
            invalidate()
        }

    /** The width the search query takes before the candidates, or 0 when not searching. */
    private fun searchWidth(): Float {
        val q = search ?: return 0f
        return iconSize + pad + (if (q.isEmpty()) hintPaint else textPaint).measureText(q.ifEmpty { searchPrompt }) + pad * 2
    }

    /**
     * Replaces the candidates; [downTime] is the touch that caused it, or 0. [readings] holds, by
     * index, a reading to draw small after a candidate ("" for none; PLAN §9.3).
     */
    fun show(list: List<String>, downTime: Long, readings: List<String> = emptyList()) {
        if (list != candidates) scroll = 0f
        candidates = list
        notes = readings
        measure()
        if (downTime != 0L) pendingProbe = downTime
        invalidate()
    }

    /** Computes the candidates' left edges after the search query, if any. */
    private fun measure() {
        bounds = FloatArray(candidates.size + 1)
        bounds[0] = padStart + searchWidth()
        candidates.forEachIndexed { i, text ->
            bounds[i + 1] = bounds[i] + textPaint.measureText(text) + noteWidth(i) + (if (i == 0) padFirst else pad) * 2
        }
    }

    /** The width candidate [i]'s reading takes after its text, with the gap before it; 0 without one. */
    private fun noteWidth(i: Int): Float =
        notes.getOrNull(i).orEmpty().let { if (it.isEmpty()) 0f else pad / 2 + hintPaint.measureText(it) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barHeight)
    }

    private val listEnd get() = width - expandWidth

    override fun onDraw(canvas: Canvas) {
        val shownDetails = details
        when {
            shownDetails != null -> drawDetails(canvas, shownDetails)
            search != null -> {
                drawSearch(canvas)
                if (candidates.isNotEmpty()) drawCandidates(canvas)
            }
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

    /** The width of the details' Forget button, or 0 without one. */
    private fun forgetWidth(d: CandidateDetails) = d.forget?.let { firstPaint.measureText(it) + pad * 2 } ?: 0f

    /** Draws a candidate's details: the text at the start, the Forget button at the end. */
    private fun drawDetails(canvas: Canvas, d: CandidateDetails) {
        val baseline = height / 2 - (hintPaint.descent() + hintPaint.ascent()) / 2
        val forget = forgetWidth(d)
        val shown = android.text.TextUtils.ellipsize(d.text, hintPaint, width - (padStart + pad) * 2 - forget, android.text.TextUtils.TruncateAt.END)
        canvas.drawText(shown, 0, shown.length, padStart + pad, baseline, hintPaint)
        if (d.forget != null) {
            val top = (height - pillHeight) / 2
            box.set(width - forget - padStart, top, width - padStart, top + pillHeight)
            canvas.drawRoundRect(box, pillHeight / 2, pillHeight / 2, pillPaint)
            canvas.drawText(d.forget, box.left + pad, height / 2 - (firstPaint.descent() + firstPaint.ascent()) / 2, firstPaint)
        }
    }

    /** Draws the emoji search's magnifier and query (or its prompt) at the start of the row. */
    private fun drawSearch(canvas: Canvas) {
        val q = search ?: return
        drawIcon(canvas, searchIcon, padStart + pad + iconSize / 2f)
        val baseline = height / 2 - (hintPaint.descent() + hintPaint.ascent()) / 2
        val paint = if (q.isEmpty()) hintPaint else textPaint
        canvas.drawText(q.ifEmpty { searchPrompt }, padStart + pad + iconSize + pad, baseline, paint)
    }

    private fun drawCandidates(canvas: Canvas) {
        val baseline = height / 2 - (textPaint.descent() + textPaint.ascent()) / 2
        // While searching, scrolled results stay clear of the query.
        canvas.withClip(if (search != null) bounds[0] - pad else 0f, 0f, listEnd, height.toFloat()) {
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
                val note = notes.getOrNull(i).orEmpty()
                if (note.isNotEmpty()) {
                    drawText(note, x + (if (i == 0) padFirst else pad) + textPaint.measureText(text) + pad / 2, baseline, hintPaint)
                }
            }
        }
        drawIcon(canvas, expandIcon, listEnd + expandWidth / 2)
    }

    /** Spreads the shown tools from one end of the bar to the other (one tool sits at the start). */
    private fun toolCenters(count: Int): List<Float> {
        val start = toolbarPad + toolButton / 2
        val end = width - toolbarPad - toolButton / 2
        return (0 until count).map { if (count == 1) start else start + (end - start) * it / (count - 1) }
    }

    private fun drawToolbar(canvas: Canvas) {
        val shown = shownTools
        toolCenters(shown.size).forEachIndexed { i, x -> drawIcon(canvas, toolIcons.getOrPut(shown[i]) { icon(shown[i].icon) }, x) }
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
        if (dragging || details != null || candidates.isEmpty() || downX >= listEnd) return@Runnable
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
        details?.let { d ->
            onDetails(if (d.forget != null && x >= width - padStart - forgetWidth(d)) d.index else null)
            return
        }
        if (search != null && x < padStart + searchWidth()) return onSearchTap()
        when {
            search != null && candidates.isEmpty() -> Unit
            candidates.isNotEmpty() -> if (x >= listEnd) {
                onExpand()
            } else {
                val cx = x + scroll
                val index = candidates.indices.firstOrNull { cx >= bounds[it] && cx < bounds[it + 1] }
                if (index != null) onPick(index)
            }
            hint == null -> {
                val shown = shownTools
                val centers = toolCenters(shown.size)
                val i = centers.indices.minByOrNull { kotlin.math.abs(centers[it] - x) } ?: return
                if (kotlin.math.abs(centers[i] - x) <= toolButton / 2) onTool(shown[i])
            }
        }
    }
}

/**
 * Holds what the candidate bar shows after a long press on candidate [index]: [text] (its reading
 * and source) and, for a word librime learned, the Forget button's label [forget].
 */
data class CandidateDetails(val index: Int, val text: String, val forget: String?)
