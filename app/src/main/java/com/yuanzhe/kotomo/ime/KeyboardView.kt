package com.yuanzhe.kotomo.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.yuanzhe.kotomo.R

/** Shift states: off, one capital, or locked capitals. */
enum class Shift { OFF, ONCE, LOCKED }

/**
 * Draws one keyboard page on a Canvas and turns touches into key actions.
 *
 * Notes: main thread. A tap reports the key's action on release; a long press reports the key's
 * hint as text (or the long-press action of Emoji) as soon as the timeout passes; holding
 * Backspace repeats. A second finger finishes the first key at once, so fast typing never drops a
 * key. Every touch down is timed to the next onDraw for [Probe.keyDraw].
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    /** Receives a key action; `longPress` is true for long presses; `downTime` is the touch's uptime. */
    private val onKey: (action: KeyAction, longPress: Boolean, downTime: Long) -> Unit,
) : View(context) {

    var layout: Layout = Layout.letters(Mode.MIXED, shifted = false)
        set(value) {
            field = value
            placed = emptyList()
            invalidate()
        }

    var shift = Shift.OFF
        set(value) {
            field = value
            invalidate()
        }

    /** Short mode badge drawn in the Emoji key's corner (混, 中, 日, EN). */
    var modeBadge = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Text drawn on the space bar (the current mode's name). */
    var spaceLabel = ""
        set(value) {
            field = value
            invalidate()
        }

    private val res = context.resources
    private val rowHeight = res.getDimension(R.dimen.kb_row_height)
    private val gap = res.getDimension(R.dimen.kb_key_gap)
    private val pad = res.getDimension(R.dimen.kb_padding)
    private val radius = res.getDimension(R.dimen.kb_key_radius)
    private val iconSize = res.getDimensionPixelSize(R.dimen.kb_icon)

    private val keyColor = context.getColor(R.color.kb_key)
    private val functionColor = context.getColor(R.color.kb_key_function)
    private val pressedColor = context.getColor(R.color.kb_key_pressed)
    private val accentColor = context.getColor(R.color.kb_accent)
    private val onAccentColor = context.getColor(R.color.kb_on_accent)
    private val inkColor = context.getColor(R.color.kb_ink)
    private val secondaryColor = context.getColor(R.color.kb_ink_secondary)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = res.getDimension(R.dimen.kb_hint_text)
        color = secondaryColor
    }
    private val keyText = res.getDimension(R.dimen.kb_key_text)
    private val functionText = res.getDimension(R.dimen.kb_function_text)

    private fun icon(id: Int) = context.getDrawable(id)!!.mutate()
    private val shiftIcon = icon(R.drawable.ic_key_shift)
    private val shiftOnIcon = icon(R.drawable.ic_key_shift_on)
    private val backspaceIcon = icon(R.drawable.ic_key_backspace)
    private val enterIcon = icon(R.drawable.ic_key_enter)
    private val emojiIcon = icon(R.drawable.ic_key_emoji)

    private var placed = emptyList<PlacedKey>()
    private var pressed: PlacedKey? = null
    private var pressDownTime = 0L
    private var longPressed = false
    private var pendingDrawProbe = 0L

    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val repeatInterval = 50L

    private val longPress = Runnable {
        val key = pressed ?: return@Runnable
        when (val action = key.key.action) {
            KeyAction.Backspace -> {
                longPressed = true
                repeatBackspace.run()
            }
            KeyAction.Emoji -> {
                longPressed = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onKey(action, true, pressDownTime)
            }
            else -> key.key.hint?.let {
                longPressed = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onKey(KeyAction.Text(it), true, pressDownTime)
            }
        }
    }

    private val repeatBackspace: Runnable = object : Runnable {
        override fun run() {
            onKey(KeyAction.Backspace, true, SystemClock.uptimeMillis())
            postDelayed(this, repeatInterval)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (rowHeight * layout.rows.size + pad * 2).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        placed = emptyList()
    }

    private fun keys(): List<PlacedKey> {
        if (placed.isEmpty() && width > 0) {
            placed = layout.place(width - pad * 2, height - pad * 2, gap).map {
                it.copy(left = it.left + pad, top = it.top + pad, right = it.right + pad, bottom = it.bottom + pad)
            }
        }
        return placed
    }

    override fun onDraw(canvas: Canvas) {
        for (k in keys()) {
            val isPressed = k === pressed
            val enter = k.key.action == KeyAction.Enter
            keyPaint.color = when {
                isPressed -> pressedColor
                enter -> accentColor
                k.key.function -> functionColor
                else -> keyColor
            }
            canvas.drawRoundRect(k.left, k.top, k.right, k.bottom, radius, radius, keyPaint)
            val ink = if (enter && !isPressed) onAccentColor else inkColor
            val icon: Drawable? = when (k.key.action) {
                KeyAction.Shift -> if (shift == Shift.OFF) shiftIcon else shiftOnIcon
                KeyAction.Backspace -> backspaceIcon
                KeyAction.Enter -> enterIcon
                KeyAction.Emoji -> emojiIcon
                else -> null
            }
            if (icon != null) {
                val l = (k.centerX - iconSize / 2).toInt()
                val t = (k.centerY - iconSize / 2).toInt()
                icon.setBounds(l, t, l + iconSize, t + iconSize)
                icon.setTint(ink)
                icon.draw(canvas)
            } else {
                val label = if (k.key.action == KeyAction.Space) spaceLabel else k.key.label
                labelPaint.color = if (k.key.action == KeyAction.Space) secondaryColor else ink
                labelPaint.textSize = if (k.key.function || k.key.action == KeyAction.Space) functionText else keyText
                labelPaint.typeface = if (k.key.function) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                val baseline = k.centerY - (labelPaint.descent() + labelPaint.ascent()) / 2
                canvas.drawText(label, k.centerX, baseline, labelPaint)
            }
            val corner = if (k.key.action == KeyAction.Emoji) modeBadge else k.key.hint
            if (corner != null) {
                canvas.drawText(corner, k.right - radius / 2, k.top + hintPaint.textSize + radius / 4, hintPaint)
            }
        }
        if (pendingDrawProbe != 0L) {
            Probe.keyDraw.add(SystemClock.uptimeMillis() - pendingDrawProbe)
            pendingDrawProbe = 0L
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (pressed != null) release(commit = true)
                val i = event.actionIndex
                val key = Layout.hit(keys(), event.getX(i), event.getY(i)) ?: return true
                pressed = key
                pressDownTime = event.eventTime
                longPressed = false
                pendingDrawProbe = event.eventTime
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                postDelayed(longPress, longPressTimeout)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (pressed != null) release(commit = true)
            MotionEvent.ACTION_CANCEL -> release(commit = false)
        }
        return true
    }

    private fun release(commit: Boolean) {
        removeCallbacks(longPress)
        removeCallbacks(repeatBackspace)
        val key = pressed ?: return
        pressed = null
        if (commit && !longPressed) onKey(key.key.action, false, pressDownTime)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release(commit = false)
    }
}
