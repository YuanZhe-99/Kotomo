package com.yuanzhe.kotomo.ime

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.yuanzhe.kotomo.R

/** Identifies how a segment or word on [SegmentStrip] was typed. */
enum class SegmentSource { PINYIN, ROMAJI, KANA, ENGLISH }

/** One chip displayed on [SegmentStrip]. */
data class SegmentChipItem(
    val text: String,
    val source: SegmentSource,
    val rawHint: String = "",
    val locked: Boolean = false,
)

/**
 * Live horizontal strip of segment chips placed above the candidate bar while composing in Mixed
 * or Japanese mode (single words and whole sentences alike).
 *
 * Notes: main thread. Reuses child chip views across updates so keystrokes do not inflate layouts;
 * tapping chip `i` opens [SegmentPanel] focused directly on that segment or word ([onChipClick]).
 */
class SegmentStrip(
    context: Context,
    private val onChipClick: (Int) -> Unit,
    private val onFormsClick: () -> Unit = {},
) : LinearLayout(context) {
    private val res = context.resources
    private val stripHeight = res.getDimensionPixelSize(R.dimen.segment_strip_height)
    private val padH = res.getDimensionPixelSize(R.dimen.segment_strip_pad_h)
    private val gap = res.getDimensionPixelSize(R.dimen.segment_strip_gap)
    private val chipHeight = res.getDimensionPixelSize(R.dimen.segment_chip_height)
    private val chipRadius = res.getDimension(R.dimen.segment_chip_radius)
    private val badgeSize = res.getDimensionPixelSize(R.dimen.segment_badge_size)
    private val density = res.displayMetrics.density
    private val badgeSp = res.getDimension(R.dimen.segment_badge_text) / density
    private val textSp = res.getDimension(R.dimen.segment_chip_text) / density
    private val hintSp = res.getDimension(R.dimen.segment_hint_text) / density
    private val strokePx = (2 * density).toInt().coerceAtLeast(1)

    private val keyColor = KeyboardColors.color(context, R.color.kb_key)
    private val accentColor = KeyboardColors.color(context, R.color.kb_accent)
    private val inkColor = KeyboardColors.color(context, R.color.kb_ink)
    private val secondaryColor = KeyboardColors.color(context, R.color.kb_ink_secondary)
    private val pinyinBg = KeyboardColors.color(context, R.color.clip_tag_phrase)
    private val pinyinFg = KeyboardColors.color(context, R.color.clip_on_tag_phrase)
    private val romajiBg = KeyboardColors.color(context, R.color.kb_accent_container)
    private val romajiFg = KeyboardColors.color(context, R.color.kb_on_accent_container)
    private val englishBg = KeyboardColors.color(context, R.color.seg_tag_en)
    private val englishFg = KeyboardColors.color(context, R.color.seg_on_tag_en)

    private val badgePinyin = context.getString(R.string.seg_badge_pinyin)
    private val badgeRomaji = context.getString(R.string.seg_badge_romaji)
    private val badgeKana = context.getString(R.string.seg_badge_kana)
    private val badgeEnglish = context.getString(R.string.seg_badge_english)
    private val formsLabel = context.getString(R.string.candidate_forms)

    private val chipContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val scrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(chipContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val formsChip = TextView(context).apply {
        text = formsLabel
        textSize = textSp
        setTextColor(romajiFg)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isSingleLine = true
        minimumHeight = chipHeight
        setPadding((10 * density).toInt(), (2 * density).toInt(), (10 * density).toInt(), (2 * density).toInt())
        background = GradientDrawable().apply {
            cornerRadius = chipRadius
            setColor(romajiBg)
        }
        visibility = GONE
        setOnClickListener { onFormsClick() }
    }

    private class ChipViews(
        val root: LinearLayout,
        val badge: TextView,
        val text: TextView,
        val hint: TextView,
        val rootBg: GradientDrawable,
        val badgeBg: GradientDrawable,
    )

    private val pool = ArrayList<ChipViews>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        minimumHeight = stripHeight
        setPadding(padH, (4 * density).toInt(), padH, (4 * density).toInt())
        setBackgroundColor(KeyboardColors.color(context, R.color.kb_background))
        // Heights are minimums, not fixed values: the chip text is in sp and grows with the font scale.
        addView(scrollView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(formsChip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = gap
        })
    }

    private var shownItems: List<SegmentChipItem> = emptyList()
    private var shownForms = false

    /**
     * Updates the strip to show [items] and a pinned trailing `Forms` pill ([hasForms]), or
     * hides the strip when [items] is empty.
     */
    fun show(items: List<SegmentChipItem>, hasForms: Boolean = false) {
        if (items.isEmpty()) {
            shownItems = emptyList()
            shownForms = false
            scrollView.scrollTo(0, 0)
            if (formsChip.visibility != GONE) formsChip.visibility = GONE
            if (visibility != GONE) visibility = GONE
            return
        }
        if (items == shownItems && hasForms == shownForms && visibility == VISIBLE) return
        shownItems = items
        shownForms = hasForms
        while (pool.size < items.size) pool += newChip()
        items.forEachIndexed { index, item ->
            val chip = pool[index]
            if (chip.root.visibility != VISIBLE) chip.root.visibility = VISIBLE
            val (label, bg, fg) = when (item.source) {
                SegmentSource.PINYIN -> Triple(badgePinyin, pinyinBg, pinyinFg)
                SegmentSource.ROMAJI -> Triple(badgeRomaji, romajiBg, romajiFg)
                SegmentSource.KANA -> Triple(badgeKana, romajiBg, romajiFg)
                SegmentSource.ENGLISH -> Triple(badgeEnglish, englishBg, englishFg)
            }
            if (chip.badge.text != label) chip.badge.text = label
            chip.badge.setTextColor(fg)
            chip.badgeBg.setColor(bg)
            if (chip.text.text != item.text) chip.text.text = item.text
            val hintText = if (item.rawHint.isNotEmpty() && item.rawHint != item.text) item.rawHint else ""
            if (hintText.isNotEmpty()) {
                if (chip.hint.text != hintText) chip.hint.text = hintText
                if (chip.hint.visibility != VISIBLE) chip.hint.visibility = VISIBLE
            } else {
                if (chip.hint.text.isNotEmpty()) chip.hint.text = ""
                if (chip.hint.visibility != GONE) chip.hint.visibility = GONE
            }
            chip.rootBg.setColor(keyColor)
            chip.rootBg.setStroke(if (item.locked) strokePx else 0, accentColor)
            val lp = chip.root.layoutParams as LayoutParams
            val targetMargin = if (index < items.lastIndex) gap else 0
            if (lp.marginEnd != targetMargin) {
                lp.marginEnd = targetMargin
                chip.root.layoutParams = lp
            }
            chip.root.setOnClickListener { onChipClick(index) }
        }
        for (i in items.size until pool.size) {
            if (pool[i].root.visibility != GONE) pool[i].root.visibility = GONE
        }
        val formsVis = if (hasForms) VISIBLE else GONE
        if (formsChip.visibility != formsVis) formsChip.visibility = formsVis
        if (visibility != VISIBLE) visibility = VISIBLE
    }

    private fun newChip(): ChipViews {
        val rootBg = GradientDrawable().apply {
            cornerRadius = chipRadius
            setColor(keyColor)
        }
        val badgeBg = GradientDrawable().apply {
            cornerRadius = badgeSize / 2f
        }
        val badge = TextView(context).apply {
            textSize = badgeSp
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = badgeSize
            minHeight = badgeSize
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            background = badgeBg
            isSingleLine = true
        }
        val text = TextView(context).apply {
            textSize = textSp
            setTextColor(inkColor)
            isSingleLine = true
        }
        val hint = TextView(context).apply {
            textSize = hintSp
            setTextColor(secondaryColor)
            isSingleLine = true
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rootBg
            minimumHeight = chipHeight
            setPadding((4 * density).toInt(), (2 * density).toInt(), (10 * density).toInt(), (2 * density).toInt())
            addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (6 * density).toInt()
            })
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (4 * density).toInt()
            })
        }
        chipContainer.addView(root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return ChipViews(root, badge, text, hint, rootBg, badgeBg)
    }
}
