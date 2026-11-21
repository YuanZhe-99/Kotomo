package com.yuanzhe.kotomo.ime

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.yuanzhe.kotomo.R

/**
 * One action pill shown on [CandidateDetailsBar] (for example switching CJK script `→ 日 天気`,
 * forgetting a learned word, or opening Japanese forms).
 */
data class CandidateAction(val label: String, val onClick: () -> Unit)

/**
 * What [CandidateDetailsBar] shows after a candidate is long-pressed: [info] (reading, source,
 * POS/base form) and zero or more independent [actions] that never crowd each other.
 */
data class CandidateDetailsState(val info: String, val actions: List<CandidateAction> = emptyList())

/**
 * Two-row candidate action strip shown in place of the candidate bar after long-pressing a
 * candidate in the bar or expanded panels.
 *
 * Notes: main thread. Row 1 displays the full [CandidateDetailsState.info] (also set as
 * `contentDescription` for TalkBack and UI tests) and a close button; Row 2 appears only when
 * [CandidateDetailsState.actions] is non-empty and scrolls horizontally so multiple actions
 * (`→ 日/中`, `Forget`, `Forms`) coexist without truncating each other.
 */
class CandidateDetailsBar(context: Context, private val onClose: () -> Unit) : LinearLayout(context) {
    private val res = context.resources
    private val rowHeight = res.getDimensionPixelSize(R.dimen.details_row_height)
    private val padH = res.getDimensionPixelSize(R.dimen.details_pad_h)
    private val actionHeight = res.getDimensionPixelSize(R.dimen.details_action_height)
    private val actionPadH = res.getDimensionPixelSize(R.dimen.details_action_pad_h)
    private val actionGap = res.getDimensionPixelSize(R.dimen.details_action_gap)
    private val density = res.displayMetrics.density

    private val infoView: TextView
    private val closeButton: ImageView
    private val actionsRow: LinearLayout
    private val actionsScroll: HorizontalScrollView

    /** Current state, or null when hidden. */
    var current: CandidateDetailsState? = null
        private set

    init {
        orientation = VERTICAL
        visibility = GONE
        setBackgroundColor(KeyboardColors.color(context, R.color.kb_background))

        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = rowHeight
            setPadding(padH, 0, padH / 2, 0)
        }
        infoView = TextView(context).apply {
            textSize = res.getDimension(R.dimen.bar_hint_text) / density
            setTextColor(KeyboardColors.color(context, R.color.kb_ink_secondary))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH / 2, (4 * density).toInt(), padH / 2, (4 * density).toInt())
        }
        closeButton = ImageView(context).apply {
            setImageResource(R.drawable.ic_clip_close)
            scaleType = ImageView.ScaleType.CENTER
            setColorFilter(KeyboardColors.color(context, R.color.kb_ink))
            contentDescription = context.getString(R.string.candidate_details_close)
            setOnClickListener {
                show(null)
                onClose()
            }
        }
        topRow.addView(infoView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(closeButton, LayoutParams(rowHeight, rowHeight))
        addView(topRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        actionsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, 0, padH, (6 * density).toInt())
        }
        actionsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = GONE
            addView(actionsRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(actionsScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Shows [state], or hides the bar when null. */
    fun show(state: CandidateDetailsState?) {
        current = state
        if (state == null) {
            infoView.text = ""
            infoView.contentDescription = null
            actionsRow.removeAllViews()
            actionsScroll.visibility = GONE
            visibility = GONE
            return
        }
        infoView.text = state.info
        infoView.contentDescription = state.info
        actionsRow.removeAllViews()
        if (state.actions.isEmpty()) {
            actionsScroll.visibility = GONE
        } else {
            val pillBg = KeyboardColors.color(context, R.color.kb_accent_container)
            val pillInk = KeyboardColors.color(context, R.color.kb_on_accent_container)
            val textSp = res.getDimension(R.dimen.bar_hint_text) / density
            val radius = actionHeight / 2f
            state.actions.forEachIndexed { i, action ->
                val pill = TextView(context).apply {
                    text = action.label
                    textSize = textSp
                    setTextColor(pillInk)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    minHeight = actionHeight
                    setPadding(actionPadH, 0, actionPadH, 0)
                    background = GradientDrawable().apply {
                        cornerRadius = radius
                        setColor(pillBg)
                    }
                    setOnClickListener {
                        show(null)
                        onClose()
                        action.onClick()
                    }
                }
                val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, actionHeight).apply {
                    if (i < state.actions.lastIndex) marginEnd = actionGap
                }
                actionsRow.addView(pill, lp)
            }
            actionsScroll.scrollTo(0, 0)
            actionsScroll.visibility = VISIBLE
        }
        visibility = VISIBLE
    }
}
