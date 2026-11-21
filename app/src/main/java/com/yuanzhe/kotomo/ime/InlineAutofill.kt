package com.yuanzhe.kotomo.ime

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.yuanzhe.kotomo.R

/**
 * Shows an autofill service's inline suggestions (a password manager's accounts, saved addresses)
 * in the candidate bar's place while nothing is being typed (PLAN §10.10, Android 11 and later).
 *
 * Notes: main thread. The system draws each suggestion inside an `InlineContentView` on behalf of
 * the autofill service: Kotomo never sees the suggested text, and a tap fills the field without
 * passing through the keyboard. [view] lies over the toolbar and is visible only while there are
 * suggestions, the bar is idle ([barIdle]) and the person has not put them aside: its first button
 * shows the toolbar instead ([dismissed]), and the toolbar then offers a key that brings them back
 * ([onWaiting]), so the tools are always one tap away. The service paints it with the theme each
 * time it builds the input view ([paint]). Built before the first input view, since the system
 * asks for the request first.
 */
@RequiresApi(Build.VERSION_CODES.R)
class InlineAutofill(private val context: Context) {
    private val res = context.resources
    private val chipHeight = res.getDimensionPixelSize(R.dimen.bar_pill_height)
    private val gap = res.getDimensionPixelSize(R.dimen.bar_pill_pad)
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(res.getDimensionPixelSize(R.dimen.bar_pad_start), 0, 0, 0)
    }

    /** The button before the suggestions that puts them aside and shows the toolbar. */
    private val toolsButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_tool_more)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = res.getString(R.string.autofill_show_tools)
        setOnClickListener { dismissed = true }
    }

    /** The scrolling chips, clipped after [toolsButton] so a scrolled chip never covers it. */
    private val chips = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** The strip: the toolbar button, then the suggestions; it lies over the candidate bar at its height. */
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
        addView(toolsButton, LinearLayout.LayoutParams(res.getDimensionPixelSize(R.dimen.toolbar_button), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(chips, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    /**
     * Whether the person put the suggestions aside for this field (the toolbar shows instead).
     * Notes: cleared by [newField] and by the toolbar's key ([CandidateBar.autofillWaiting]).
     */
    var dismissed = false
        set(value) {
            field = value
            update()
        }

    /** Receives whether suggestions wait behind the toolbar's key ([waiting]) each time the strip updates. */
    var onWaiting: ((Boolean) -> Unit)? = null

    /** Whether there are suggestions the person put aside. */
    val waiting get() = count > 0 && dismissed

    /** Counts the responses, so a late inflation of an older one is dropped. */
    private var generation = 0
    private var count = 0

    /** Whether a keyboard overlay (long-press popup or key preview) is temporarily covering the bar. */
    var obscured = false
        set(value) {
            if (field == value) return
            field = value
            update()
        }

    /** Whether the candidate bar shows its toolbar (nothing typed, no hint); the strip shows only then. */
    var barIdle = true
        set(value) {
            field = value
            update()
        }

    /**
     * Builds the request the system passes to the autofill service: up to [MAX] chips at the
     * bar's pill height, styled with the keyboard's colors.
     * Inputs: [uiExtras] is what the system handed to `onCreateInlineSuggestionsRequest`.
     * Returns: null when the system's inline UI does not take the style version this builds.
     */
    // The style builders' setters are public on ViewStyle and TextViewStyle but declared on a
    // restricted base class, which lint reports as a restricted call.
    @android.annotation.SuppressLint("RestrictedApi")
    fun request(uiExtras: android.os.Bundle): InlineSuggestionsRequest? {
        if (UiVersions.INLINE_UI_VERSION_1 !in UiVersions.getVersions(uiExtras)) return null
        val ink = KeyboardColors.color(context, R.color.kb_ink)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackground(Icon.createWithResource(context, R.drawable.bg_autofill_chip))
                    .setPadding(gap, 0, gap, 0)
                    .build(),
            )
            .setTitleStyle(TextViewStyle.Builder().setTextColor(ink).setTextSize(TITLE_SP).build())
            .setSubtitleStyle(TextViewStyle.Builder().setTextColor(KeyboardColors.color(context, R.color.kb_ink_secondary)).setTextSize(SUBTITLE_SP).build())
            .build()
        val styles = UiVersions.newStylesBuilder().addStyle(style).build()
        val width = res.displayMetrics.widthPixels
        val spec = InlinePresentationSpec.Builder(Size(chipHeight, chipHeight), Size(width, chipHeight)).setStyle(styles).build()
        return InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(MAX).build()
    }

    /**
     * Replaces the strip's suggestions with [suggestions] (an empty list clears it).
     * Side effects: each one is inflated by the system off the main thread and added in order.
     */
    fun show(suggestions: List<InlineSuggestion>) {
        val gen = ++generation
        row.removeAllViews()
        count = suggestions.size
        val slots = arrayOfNulls<View>(suggestions.size)
        suggestions.forEachIndexed { i, s ->
            s.inflate(context, Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT), context.mainExecutor) { v ->
                if (gen != generation || v == null) return@inflate
                // The chip is a surface the system draws; below the keyboard's window it would be hidden.
                v.isZOrderedOnTop = true
                slots[i] = v
                row.removeAllViews()
                slots.filterNotNull().forEach { chip ->
                    // The system sizes each chip to what the service drew (its layout parameters).
                    val size = chip.layoutParams
                    chip.visibility = if (obscured) View.INVISIBLE else View.VISIBLE
                    row.addView(chip, LinearLayout.LayoutParams(size?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT, size?.height ?: chipHeight).apply { marginEnd = gap })
                }
            }
        }
        update()
    }

    /** Brings back suggestions put aside in the previous field: a new field starts with them shown. */
    fun newField() {
        dismissed = false
    }

    /**
     * Paints the strip with the keyboard's current theme.
     * Side effects: the background and the toolbar button's tint.
     */
    fun paint() {
        view.setBackgroundColor(KeyboardColors.color(context, R.color.kb_background))
        toolsButton.setColorFilter(KeyboardColors.color(context, R.color.kb_ink))
    }

    /**
     * Shows the strip while there are suggestions not put aside and the bar is idle; tells the bar
     * what waits. While [obscured] is set, hides both the container and each `InlineContentView`
     * child (`View.INVISIBLE`) so its top-ordered `SurfaceView` cannot occlude keyboard popups
     * without disturbing [waiting] or flashing the toolbar.
     */
    private fun update() {
        val visible = count > 0 && barIdle && !dismissed
        view.visibility = when {
            !visible -> View.GONE
            obscured -> View.INVISIBLE
            else -> View.VISIBLE
        }
        val childVisibility = if (obscured) View.INVISIBLE else View.VISIBLE
        for (i in 0 until row.childCount) {
            row.getChildAt(i).visibility = childVisibility
        }
        onWaiting?.invoke(waiting)
    }

    private companion object {
        /** The most suggestions asked for. */
        const val MAX = 6
        const val TITLE_SP = 15f
        const val SUBTITLE_SP = 12f
    }
}
