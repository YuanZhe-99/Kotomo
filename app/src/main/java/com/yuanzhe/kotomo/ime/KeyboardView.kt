package com.yuanzhe.kotomo.ime

import androidx.core.content.res.ResourcesCompat
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.graphics.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.yuanzhe.kotomo.R

/** Shift states: off, one capital, or locked capitals. */
enum class Shift { OFF, ONCE, LOCKED }

/** Keys never shrink below this share of their height to fit a height limit. */
private const val MIN_FIT = 0.6f

/**
 * Draws one keyboard page as in the design and turns touches into key actions.
 *
 * Notes: main thread. A tap reports the key's action on release. A long press on a key with
 * choices ([Key.more], or the languages on the Emoji key and the space bar) opens a popup on
 * [popupLayer]: sliding picks a choice and releasing types it, as on Gboard. Holding Backspace
 * repeats. A second finger finishes the first key at once, so fast typing never drops a key. Every
 * touch down is timed to the next onDraw for [Probe.keyDraw]. In [split] mode the rows are two
 * halves of fixed width at the edges (the design's thumb zones), clear of a [hinge], and the keys
 * are lower; elsewhere the keys keep within [maxWidth] and shrink to [heightLimit].
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val popupLayer: PopupLayer,
    /** Receives a key action; `longPress` is true for popup choices and repeats; `downTime` is the touch's uptime. */
    private val onKey: (action: KeyAction, longPress: Boolean, downTime: Long) -> Unit,
) : View(context) {

    var layout: Layout = Layout.letters(Mode.MIXED, shifted = false)
        set(value) {
            if (field == value) return
            field = value
            placed = emptyList()
            requestLayout()
            invalidate()
            if (isAttachedToWindow) access.invalidateRoot()
        }

    var split = false
        set(value) {
            if (field == value) return
            field = value
            placed = emptyList()
            requestLayout()
            invalidate()
        }

    /** Key height as a share of the design's (Settings.heightPercent / 100). */
    var heightScale = 1f
        set(value) {
            if (field == value) return
            field = value
            placed = emptyList()
            requestLayout()
        }

    /**
     * The most height the keys may take, in pixels (0: no limit): the space below the hinge in
     * tabletop posture, or a share of a short landscape window. Keys and gaps shrink to fit.
     */
    var heightLimit = 0
        set(value) {
            if (field == value) return
            field = value
            placed = emptyList()
            requestLayout()
        }

    /** The widest the keys may be outside the split layout, in pixels (0: full width); centred. */
    var maxWidth = 0f
        set(value) {
            if (field == value) return
            field = value
            placed = emptyList()
            invalidate()
        }

    /** The hinge's horizontal extent in view pixels when it divides the window, or null; split halves stay clear of it. */
    var hinge: ClosedFloatingPointRange<Float>? = null
        set(value) {
            if (field == value) return
            field = value
            placed = emptyList()
            invalidate()
        }

    /** Width of each split half in pixels (Settings.thumbZoneDp). */
    var thumbHalf = 0f
        set(value) {
            if (field == value) return
            field = value
            placed = emptyList()
            invalidate()
        }

    /** One-handed side; ignored in the split layout, which already keeps the keys at the edges. */
    var oneHanded = com.yuanzhe.kotomo.settings.OneHanded.OFF
        set(value) {
            if (field == value) return
            field = value
            placed = emptyList()
            invalidate()
        }

    /** Corner hints on the keys (the Emoji key's mode badge stays). */
    var hints = true
        set(value) {
            field = value
            invalidate()
        }

    /** Haptic feedback on key presses. */
    var haptics = true

    /** Long press on a letter offers its symbol and accents. */
    var longPressSymbols = true

    /** The system's key click on each press (off by default). */
    var sound = false

    /** Sliding along the space bar moves the cursor. */
    var spaceCursor = true

    /** Sliding left from backspace deletes whole words. */
    var deleteSwipe = true

    /** A long press on the space bar opens the language choice. */
    var spaceLanguage = true

    /** What the Enter key shows: a new line or the field's action (search, send, …). */
    var enterStyle = EnterStyle.NEWLINE
        set(value) {
            field = value
            invalidate()
        }

    var shift = Shift.OFF
        set(value) {
            field = value
            invalidate()
        }

    var mode = Mode.MIXED
        set(value) {
            field = value
            invalidate()
        }

    private val res = context.resources
    private val keyHeightPhone = res.getDimension(R.dimen.kb_key_height)
    private val keyHeightLarge = res.getDimension(R.dimen.kb_key_height_large)
    private val rowGap = res.getDimension(R.dimen.kb_row_gap)
    private val gap = res.getDimension(R.dimen.kb_key_gap)
    private val padTop = res.getDimension(R.dimen.kb_pad_top)
    private val padBottom = res.getDimension(R.dimen.kb_pad_bottom)
    private val sidePhone = res.getDimension(R.dimen.kb_side)
    private val half = res.getDimension(R.dimen.kb_split_half)
    /** Key corner radius in pixels (Settings.keyRadiusDp; the design's 12 dp by default). */
    var radius = res.getDimension(R.dimen.kb_key_radius)
        set(value) {
            field = value
            invalidate()
        }
    private val shadow = res.getDimension(R.dimen.kb_key_shadow)
    private val iconSize = res.getDimensionPixelSize(R.dimen.kb_icon)
    private val hintTop = res.getDimension(R.dimen.kb_hint_top)
    private val hintEnd = res.getDimension(R.dimen.kb_hint_end)

    private val keyColor = KeyboardColors.color(context, R.color.kb_key)
    private val functionColor = KeyboardColors.color(context, R.color.kb_key_function)
    private val pressedColor = KeyboardColors.color(context, R.color.kb_key_pressed)
    private val accentColor = KeyboardColors.color(context, R.color.kb_accent)
    private val onAccentColor = KeyboardColors.color(context, R.color.kb_on_accent)
    private val inkColor = KeyboardColors.color(context, R.color.kb_ink)
    private val secondaryColor = KeyboardColors.color(context, R.color.kb_ink_secondary)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KeyboardColors.color(context, R.color.kb_key_shadow) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = res.getDimension(R.dimen.kb_hint_text)
        color = secondaryColor
    }
    private val keyText = res.getDimension(R.dimen.kb_key_text)
    private val functionText = res.getDimension(R.dimen.kb_function_text)
    private val spaceText = res.getDimension(R.dimen.kb_space_text)

    private fun icon(id: Int) = ResourcesCompat.getDrawable(context.resources, id, context.theme)!!.mutate()
    private val shiftIcon = icon(R.drawable.ic_key_shift)
    private val shiftOnIcon = icon(R.drawable.ic_key_shift_on)
    private val backspaceIcon = icon(R.drawable.ic_key_backspace)
    private val enterIcons = mapOf(
        EnterStyle.NEWLINE to icon(R.drawable.ic_key_enter),
        EnterStyle.SEARCH to icon(R.drawable.ic_key_search),
        EnterStyle.SEND to icon(R.drawable.ic_key_send),
        EnterStyle.GO to icon(R.drawable.ic_key_go),
        EnterStyle.NEXT to icon(R.drawable.ic_key_next),
        EnterStyle.PREVIOUS to icon(R.drawable.ic_key_previous),
        EnterStyle.DONE to icon(R.drawable.ic_key_done),
    )
    private val audio = context.getSystemService(AudioManager::class.java)
    private val emojiIcon = icon(R.drawable.ic_key_emoji)

    private val badges = mapOf(
        Mode.MIXED to context.getString(R.string.mode_badge_mixed),
        Mode.CHINESE to context.getString(R.string.mode_badge_chinese),
        Mode.JAPANESE to context.getString(R.string.mode_badge_japanese),
        Mode.ENGLISH to context.getString(R.string.mode_badge_english),
    )
    private val spaceLabels = mapOf(
        Mode.MIXED to context.getString(R.string.space_mixed),
        Mode.CHINESE to context.getString(R.string.space_chinese),
        Mode.JAPANESE to context.getString(R.string.space_japanese),
        Mode.ENGLISH to context.getString(R.string.space_english),
    )

    private var placed = emptyList<PlacedKey>()
    private var pressed: PlacedKey? = null
    private var pressDownTime = 0L
    private var longPressed = false
    /** Where the pressed key's finger went down, for the space and backspace drags. */
    private var downX = 0f
    private var downY = 0f

    /**
     * Where the last tap that typed fell on its key, as a fraction of the key's size from its
     * centre (−0.5…0.5 each way), or null for a tap with no position (accessibility, popups).
     * Notes: main thread; read by the service right after [onKey] for English key correction.
     */
    var lastTap: Pair<Float, Float>? = null
        private set
    /** The pointer that pressed the current key. */
    private var pointerId = 0
    /** Cursor steps already sent while dragging along the space bar. */
    private var movedSteps = 0
    /** Words marked for deletion while dragging left from backspace. */
    private var deleteWords = 0
    /** A drag took over the pressed key: releasing it types nothing. */
    private var swiping = false
    private val cursorStep = res.getDimension(R.dimen.kb_cursor_step)
    private val wordStep = res.getDimension(R.dimen.kb_word_step)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var pendingDrawProbe = 0L
    private val box = RectF()

    /** The open popup's choices and actions, or null. */
    private var popupActions: List<KeyAction>? = null

    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val repeatInterval = 50L

    /** The height scale in force: the person's choice, shrunk to fit [heightLimit]. */
    private var fit = 1f
    private val scale get() = heightScale * fit
    private val keyHeight get() = (if (split) keyHeightLarge else keyHeightPhone) * scale
    private val oneHandedActive get() = !split && oneHanded != com.yuanzhe.kotomo.settings.OneHanded.OFF
    /** Share of the width the one-handed keyboard keeps. */
    private val oneHandedShare = 0.82f
    private val side get() = when {
        split -> width * 0.05f
        maxWidth > 0f && width - sidePhone * 2 > maxWidth -> (width - maxWidth) / 2
        else -> sidePhone
    }

    private val longPress = Runnable {
        val key = pressed ?: return@Runnable
        when (key.key.action) {
            KeyAction.Backspace -> {
                longPressed = true
                repeatBackspace.run()
            }
            KeyAction.Emoji -> openModes(key)
            KeyAction.Space -> if (spaceLanguage) openModes(key)
            else -> {
                val letter = key.key.label.singleOrNull()?.isLetter() == true
                if (key.key.more.isNotEmpty() && (longPressSymbols || !letter)) {
                    openPopup(key, key.key.more, key.key.more.map(KeyAction::Text), 0)
                }
            }
        }
    }

    private val repeatBackspace: Runnable = object : Runnable {
        override fun run() {
            onKey(KeyAction.Backspace, true, SystemClock.uptimeMillis())
            postDelayed(this, repeatInterval)
        }
    }

    /** Opens the language choice over [key] (the Emoji key's or the space bar's long press). */
    private fun openModes(key: PlacedKey) =
        openPopup(key, Mode.entries.map { badges.getValue(it) }, Mode.entries.map(KeyAction::SetMode), Mode.entries.indexOf(mode))

    private fun openPopup(key: PlacedKey, labels: List<String>, actions: List<KeyAction>, selected: Int) {
        longPressed = true
        popupLayer.hidePreview()
        buzz(HapticFeedbackConstants.LONG_PRESS)
        popupActions = actions
        val here = IntArray(2).also(::getLocationInWindow)
        val there = IntArray(2).also(popupLayer::getLocationInWindow)
        popupLayer.show(
            labels, selected,
            anchorLeft = key.left + here[0] - there[0], anchorRight = key.right + here[0] - there[0],
            anchorTop = key.top + here[1] - there[1],
        )
    }

    /**
     * Exposes every key to TalkBack as a virtual view: explore by touch reads it, double tap types
     * it (PLAN §8.1: accessibility from the start).
     */
    private inner class Access : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            keys().indexOfFirst { x >= it.left && x < it.right && y >= it.top && y < it.bottom }
                .let { if (it < 0) INVALID_ID else it }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            keys().indices.forEach(ids::add)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val k = keys().getOrNull(id)
            if (k == null) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect())
                return
            }
            node.contentDescription = describe(k.key)
            node.setBoundsInParent(Rect(k.left.toInt(), k.top.toInt(), k.right.toInt(), k.bottom.toInt()))
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: android.os.Bundle?): Boolean {
            val k = keys().getOrNull(id) ?: return false
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            onKey(k.key.action, false, SystemClock.uptimeMillis())
            return true
        }
    }

    private val access = Access()

    init {
        ViewCompat.setAccessibilityDelegate(this, access)
    }

    /** Names a key for TalkBack: its label, or what a function key does. */
    private fun describe(key: Key): String = when (key.action) {
        KeyAction.Shift -> context.getString(if (shift == Shift.LOCKED) R.string.key_caps_lock else R.string.key_shift)
        KeyAction.Backspace -> context.getString(R.string.key_backspace)
        KeyAction.Enter -> context.getString(
            when (enterStyle) {
                EnterStyle.NEWLINE -> R.string.key_enter
                EnterStyle.SEARCH -> R.string.key_enter_search
                EnterStyle.SEND -> R.string.key_enter_send
                EnterStyle.GO -> R.string.key_enter_go
                EnterStyle.NEXT -> R.string.key_enter_next
                EnterStyle.PREVIOUS -> R.string.key_enter_previous
                EnterStyle.DONE -> R.string.key_enter_done
            },
        )
        KeyAction.Space -> context.getString(R.string.key_space, spaceLabels.getValue(mode))
        KeyAction.Emoji -> context.getString(R.string.key_emoji)
        is KeyAction.Symbols -> context.getString(R.string.key_symbols)
        KeyAction.Letters -> context.getString(R.string.key_letters)
        else -> key.label
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        access.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = layout.rows.size
        fit = 1f
        val natural = padTop + rows * keyHeight + (rows - 1) * rowGap * scale + padBottom
        if (heightLimit in 1 until natural.toInt()) fit = ((heightLimit - padTop - padBottom) / (natural - padTop - padBottom)).coerceIn(MIN_FIT, 1f)
        val height = padTop + rows * keyHeight + (rows - 1) * rowGap * scale + padBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        placed = emptyList()
    }

    private fun keys(): List<PlacedKey> {
        if (placed.isEmpty() && width > 0) {
            val full = width - side * 2
            val inner = if (oneHandedActive) full * oneHandedShare else full
            val x0 = side + if (oneHandedActive && oneHanded == com.yuanzhe.kotomo.settings.OneHanded.RIGHT) full - inner else 0f
            var splitHalf = if (split) minOf(if (thumbHalf > 0) thumbHalf else half, (inner - gap) / 2) else 0f
            // A hinge between the halves: each half ends before it.
            hinge?.let { h -> if (split) splitHalf = minOf(splitHalf, h.start - x0 - gap, x0 + inner - h.endInclusive - gap).coerceAtLeast(0f) }
            placed = layout.place(inner, keyHeight, rowGap * scale, gap, splitHalf).map {
                it.copy(left = it.left + x0, top = it.top + padTop, right = it.right + x0, bottom = it.bottom + padTop)
            }
        }
        return placed
    }

    override fun onDraw(canvas: Canvas) {
        for (k in keys()) {
            val isPressed = k === pressed
            val enter = k.key.action == KeyAction.Enter
            box.set(k.left, k.top + shadow, k.right, k.bottom + shadow)
            canvas.drawRoundRect(box, radius, radius, shadowPaint)
            keyPaint.color = when {
                isPressed -> pressedColor
                enter -> accentColor
                k.key.function -> functionColor
                else -> keyColor
            }
            box.set(k.left, k.top, k.right, k.bottom)
            canvas.drawRoundRect(box, radius, radius, keyPaint)
            val ink = if (enter && !isPressed) onAccentColor else inkColor
            val icon: Drawable? = when (k.key.action) {
                KeyAction.Shift -> if (shift == Shift.OFF) shiftIcon else shiftOnIcon
                KeyAction.Backspace -> backspaceIcon
                KeyAction.Enter -> enterIcons.getValue(enterStyle)
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
                val space = k.key.action == KeyAction.Space
                val label = if (space) spaceLabels.getValue(mode) else k.key.label
                labelPaint.color = if (space) secondaryColor else ink
                labelPaint.textSize = when {
                    space -> spaceText
                    k.key.function -> functionText
                    else -> keyText
                }
                labelPaint.typeface = Typeface.DEFAULT
                val baseline = k.centerY - (labelPaint.descent() + labelPaint.ascent()) / 2
                canvas.drawText(label, k.centerX, baseline, labelPaint)
            }
            val corner = if (k.key.action == KeyAction.Emoji) badges.getValue(mode) else k.key.hint?.takeIf { hints }
            if (corner != null) {
                canvas.drawText(corner, k.right - hintEnd, k.top + hintTop - hintPaint.ascent(), hintPaint)
            }
        }
        sideButtons()?.let { (swap, full) ->
            val r = keyHeight * 0.45f
            keyPaint.color = functionColor
            canvas.drawCircle(swap.first, swap.second, r, keyPaint)
            canvas.drawCircle(full.first, full.second, r, keyPaint)
            labelPaint.color = inkColor
            labelPaint.textSize = functionText
            for ((c, glyph) in listOf(swap to "⇆", full to "⤢")) {
                canvas.drawText(glyph, c.first, c.second - (labelPaint.descent() + labelPaint.ascent()) / 2, labelPaint)
            }
        }
        if (pendingDrawProbe != 0L) {
            Probe.keyDraw.add(SystemClock.uptimeMillis() - pendingDrawProbe)
            pendingDrawProbe = 0L
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actions = popupActions
        if (actions != null) {
            // While the popup is open only the finger that opened it counts.
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> popupLayer.track(event.getX(0) + popupOffsetX())
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (event.actionIndex == 0) closePopup(commit = true)
                MotionEvent.ACTION_CANCEL -> closePopup(commit = false)
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (pressed != null) release(commit = true)
                val i = event.actionIndex
                sideButtons()?.let { (swap, full) ->
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val r = keyHeight * 0.6f
                    fun near(c: Pair<Float, Float>) = (x - c.first) * (x - c.first) + (y - c.second) * (y - c.second) < r * r
                    if (near(swap)) {
                        val other = if (oneHanded == com.yuanzhe.kotomo.settings.OneHanded.LEFT) com.yuanzhe.kotomo.settings.OneHanded.RIGHT else com.yuanzhe.kotomo.settings.OneHanded.LEFT
                        onKey(KeyAction.SetOneHanded(other), false, event.eventTime)
                        return true
                    }
                    if (near(full)) {
                        onKey(KeyAction.SetOneHanded(com.yuanzhe.kotomo.settings.OneHanded.OFF), false, event.eventTime)
                        return true
                    }
                }
                val key = Layout.hit(keys(), event.getX(i), event.getY(i)) ?: return true
                pressed = key
                pressDownTime = event.eventTime
                longPressed = false
                downX = event.getX(i)
                downY = event.getY(i)
                pointerId = event.getPointerId(i)
                movedSteps = 0
                deleteWords = 0
                swiping = false
                pendingDrawProbe = event.eventTime
                buzz(HapticFeedbackConstants.KEYBOARD_TAP)
                click(key.key.action)
                postDelayed(longPress, longPressTimeout)
                if (key.key.action is KeyAction.Text && !key.key.function) showPreview(key)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                val key = pressed
                if (index >= 0 && key != null) drag(key, event.getX(index))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (pressed != null) release(commit = true)
            MotionEvent.ACTION_CANCEL -> release(commit = false)
        }
        return true
    }

    private fun popupOffsetX(): Float {
        val here = IntArray(2).also(::getLocationInWindow)
        val there = IntArray(2).also(popupLayer::getLocationInWindow)
        return (here[0] - there[0]).toFloat()
    }

    private fun closePopup(commit: Boolean) {
        val actions = popupActions ?: return
        val choice = popupLayer.selected
        popupActions = null
        popupLayer.hide()
        pressed = null
        if (commit && choice in actions.indices) onKey(actions[choice], true, pressDownTime)
        invalidate()
    }

    /**
     * Follows a finger on the pressed key: along the space bar it moves the cursor one character
     * per step; left from backspace it marks one more word per step, deleted on release (Gboard's
     * gestures). Either drag cancels the long press and the tap.
     */
    private fun drag(key: PlacedKey, x: Float) {
        val dx = x - downX
        when (key.key.action) {
            KeyAction.Space -> {
                if (!spaceCursor || !swiping && kotlin.math.abs(dx) < touchSlop) return
                startSwipe()
                val steps = (dx / cursorStep).toInt()
                if (steps != movedSteps) {
                    onKey(KeyAction.MoveCursor(steps - movedSteps), true, SystemClock.uptimeMillis())
                    movedSteps = steps
                    buzz(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            KeyAction.Backspace -> {
                if (!deleteSwipe || !swiping && -dx < touchSlop) return
                startSwipe()
                val words = (-dx / wordStep).toInt().coerceAtLeast(0)
                if (words != deleteWords) {
                    deleteWords = words
                    buzz(HapticFeedbackConstants.CLOCK_TICK)
                    popupLayer.showBadge(if (words > 0) "−$words" else null, key, this)
                }
            }
            else -> if (kotlin.math.abs(dx) > touchSlop * 3) popupLayer.hidePreview()
        }
    }

    /** Plays haptic feedback [type] when the person has it on. */
    private fun buzz(type: Int) {
        if (haptics) performHapticFeedback(type)
    }

    /** Plays the system key click for [action] when the person has key sounds on. */
    private fun click(action: KeyAction) {
        if (!sound) return
        val effect = when (action) {
            KeyAction.Backspace -> AudioManager.FX_KEYPRESS_DELETE
            KeyAction.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyAction.Enter -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        audio.playSoundEffect(effect, -1f)
    }

    /** Centres of the one-handed side buttons (swap side, full width) in the empty strip, or null. */
    private fun sideButtons(): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
        if (!oneHandedActive) return null
        val full = width - side * 2
        val strip = full * (1 - oneHandedShare)
        val cx = if (oneHanded == com.yuanzhe.kotomo.settings.OneHanded.RIGHT) side + strip / 2 else width - side - strip / 2
        val mid = height / 2f
        return (cx to mid - keyHeight * 0.7f) to (cx to mid + keyHeight * 0.7f)
    }

    private fun startSwipe() {
        if (swiping) return
        swiping = true
        removeCallbacks(longPress)
        removeCallbacks(repeatBackspace)
        popupLayer.hidePreview()
    }

    private fun showPreview(key: PlacedKey) = popupLayer.showPreview(key.key.label, key, this)

    private fun release(commit: Boolean) {
        removeCallbacks(longPress)
        removeCallbacks(repeatBackspace)
        popupLayer.hidePreview()
        val key = pressed ?: return
        pressed = null
        when {
            swiping -> if (commit && key.key.action == KeyAction.Backspace && deleteWords > 0) {
                onKey(KeyAction.DeleteWords(deleteWords), true, pressDownTime)
            }
            commit && !longPressed -> {
                lastTap = ((downX - key.centerX) / (key.right - key.left)) to ((downY - key.centerY) / (key.bottom - key.top))
                onKey(key.key.action, false, pressDownTime)
                lastTap = null
            }
        }
        swiping = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(longPress)
        removeCallbacks(repeatBackspace)
        if (popupActions != null) closePopup(commit = false)
        pressed = null
    }
}

/**
 * Draws the long-press popup above the keyboard: a row of choices, one highlighted.
 *
 * Notes: main thread; it covers the whole input view and never takes touches, so it can rise
 * above the top row into the candidate bar. Coordinates are its own.
 */
@SuppressLint("ViewConstructor")
class PopupLayer(context: Context) : View(context) {
    private val res = context.resources
    private val cell = res.getDimension(R.dimen.kb_popup_cell)
    private val cellHeight = res.getDimension(R.dimen.kb_popup_height)
    private val lift = res.getDimension(R.dimen.kb_popup_lift)
    private val radius = res.getDimension(R.dimen.kb_key_radius)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KeyboardColors.color(context, R.color.kb_key) }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KeyboardColors.color(context, R.color.kb_key_shadow) }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KeyboardColors.color(context, R.color.kb_accent) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = res.getDimension(R.dimen.kb_popup_text)
    }
    private val inkColor = KeyboardColors.color(context, R.color.kb_ink)
    private val onAccentColor = KeyboardColors.color(context, R.color.kb_on_accent)
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = res.getDimension(R.dimen.kb_hint_text)
    }
    private val fullWidth = context.getString(R.string.popup_full_width)
    private val halfWidth = context.getString(R.string.popup_half_width)

    private var labels = emptyList<String>()
    private var captions = emptyList<String?>()
    /** The pressed key's preview bubble (label and box in this layer's coordinates), or null. */
    private var preview: String? = null
    private val previewBox = RectF()
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = res.getDimension(R.dimen.kb_preview_text)
    }

    /** Maps a key of [view] to this layer's coordinates. */
    private fun boxOf(key: PlacedKey, view: View): RectF {
        val here = IntArray(2).also(view::getLocationInWindow)
        val there = IntArray(2).also(::getLocationInWindow)
        val dx = (here[0] - there[0]).toFloat()
        val dy = (here[1] - there[1]).toFloat()
        return RectF(key.left + dx, key.top + dy, key.right + dx, key.bottom + dy)
    }

    /**
     * Shows a bubble with [label] over the pressed [key], as Gboard does, so the finger does not
     * hide what it types.
     */
    fun showPreview(label: String, key: PlacedKey, view: View) {
        val k = boxOf(key, view)
        val w = k.width() + lift * 2
        previewBox.set(k.centerX() - w / 2, k.top - cellHeight - lift, k.centerX() + w / 2, k.top - lift)
        if (previewBox.top < 0) previewBox.offset(0f, -previewBox.top)
        preview = label
        invalidate()
    }

    /** Shows a short [badge] (for example "−2" words) over [key], or hides it for null. */
    fun showBadge(badge: String?, key: PlacedKey, view: View) {
        if (badge == null) hidePreview() else showPreview(badge, key, view)
    }

    fun hidePreview() {
        if (preview == null) return
        preview = null
        invalidate()
    }
    private var left = 0f
    private var top = 0f
    private val box = RectF()

    /** Index of the highlighted choice. */
    var selected = 0
        private set

    init {
        isClickable = false
        isFocusable = false
    }

    /** Opens the popup above the key spanning [anchorLeft]..[anchorRight] with its top at [anchorTop]. */
    fun show(labels: List<String>, selected: Int, anchorLeft: Float, anchorRight: Float, anchorTop: Float) {
        this.labels = labels
        this.captions = Layout.widthMarks(labels).map { full -> full?.let { if (it) fullWidth else halfWidth } }
        this.selected = selected
        val w = labels.size * cell
        val center = (anchorLeft + anchorRight) / 2
        // The preselected choice sits over the key; the row shifts to stay on screen.
        left = (center - cell * (selected + 0.5f)).coerceIn(0f, (width - w).coerceAtLeast(0f))
        top = (anchorTop - cellHeight - lift).coerceAtLeast(0f)
        invalidate()
    }

    /** Highlights the choice under [x] (clamped to the ends). */
    fun track(x: Float) {
        if (labels.isEmpty()) return
        val index = ((x - left) / cell).toInt().coerceIn(0, labels.lastIndex)
        if (index != selected) {
            selected = index
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }
    }

    fun hide() {
        labels = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        preview?.let { label ->
            box.set(previewBox.left, previewBox.top + 2, previewBox.right, previewBox.bottom + 2)
            canvas.drawRoundRect(box, radius, radius, shadowPaint)
            canvas.drawRoundRect(previewBox, radius, radius, panelPaint)
            previewPaint.color = inkColor
            val baseline = previewBox.centerY() - (previewPaint.descent() + previewPaint.ascent()) / 2
            canvas.drawText(label, previewBox.centerX(), baseline, previewPaint)
        }
        if (labels.isEmpty()) return
        val w = labels.size * cell
        box.set(left, top + 2, left + w, top + cellHeight + 2)
        canvas.drawRoundRect(box, radius, radius, shadowPaint)
        box.set(left, top, left + w, top + cellHeight)
        canvas.drawRoundRect(box, radius, radius, panelPaint)
        labels.forEachIndexed { i, label ->
            val x = left + i * cell
            if (i == selected) {
                box.set(x + 3, top + 3, x + cell - 3, top + cellHeight - 3)
                canvas.drawRoundRect(box, radius - 3, radius - 3, selectedPaint)
            }
            textPaint.color = if (i == selected) onAccentColor else inkColor
            val caption = captions.getOrNull(i)
            // With a full/half-width caption the glyph moves up to make room below it.
            val center = if (caption == null) cellHeight / 2 else cellHeight * 0.4f
            val baseline = top + center - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, x + cell / 2, baseline, textPaint)
            if (caption != null) {
                captionPaint.color = textPaint.color
                canvas.drawText(caption, x + cell / 2, top + cellHeight - captionPaint.descent() - 4, captionPaint)
            }
        }
    }
}

/**
 * Holds the input view: [content] (candidate bar and keys) decides the size, and [popups] covers
 * exactly that area on top.
 *
 * Notes: a plain FrameLayout would let the full-size popup layer stretch the input view to the
 * whole window height.
 */
@SuppressLint("ViewConstructor")
class InputRoot(context: Context, private val content: View, private val popups: View) : android.widget.FrameLayout(context) {
    init {
        addView(content)
        addView(popups)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        content.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val w = content.measuredWidth
        val h = content.measuredHeight
        popups.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }
}
