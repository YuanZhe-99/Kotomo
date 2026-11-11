package com.yuanzhe.kotomo.ime

import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import com.yuanzhe.kotomo.settings.SplitLayout

/**
 * Holds the window facts the keyboard's size depends on (PLAN §8.10): the window's size in dp and,
 * on foldables, where the hinge is.
 *
 * Notes: [hingeX] is a vertical hinge's horizontal extent in window pixels when it divides the
 * window (book posture, or a hinge that hides content); [tabletopBottom] is the bottom edge of a
 * horizontal hinge in window pixels while half-folded (tabletop posture). Both are null otherwise.
 */
data class Posture(
    val widthDp: Float,
    val heightDp: Float,
    val hingeX: ClosedFloatingPointRange<Float>? = null,
    val tabletopBottom: Float? = null,
) {
    /** The window's size class by the Material breakpoints (compact, medium, expanded). */
    private val sizeClass: WindowSizeClass get() = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp, heightDp)

    /** Whether the window is at least medium width (large phones in landscape, foldables, tablets). */
    val wide: Boolean get() = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    /** Whether the window is short (phones in landscape). */
    val short: Boolean get() = !sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)

    /**
     * Decides whether the keys split into two halves.
     * Returns: always beside a dividing hinge (no key may sit on it); otherwise the person's
     * choice, AUTO splitting from medium width on.
     */
    fun split(choice: SplitLayout): Boolean = hingeX != null || when (choice) {
        SplitLayout.ALWAYS -> true
        SplitLayout.NEVER -> false
        SplitLayout.AUTO -> wide
    }

    /**
     * Returns the most height the keys may take, in pixels, or 0 for no limit.
     * Inputs: windowHeightPx is the window's height; reservedPx what sits below and above the keys
     * (candidate bar, navigation bar); density the display density; shortShare the share of a
     * short window the keyboard may take; tabletop whether the person keeps the keyboard below the
     * hinge when half-folded.
     * Notes: in tabletop posture the keys end above the hinge's bottom edge; a short window
     * limits them to its share.
     */
    fun heightLimit(windowHeightPx: Int, reservedPx: Int, density: Float, shortShare: Float, tabletop: Boolean): Int {
        if (tabletop && tabletopBottom != null) return (windowHeightPx - tabletopBottom - reservedPx).toInt().coerceAtLeast(1)
        if (short) return (heightDp * density * shortShare - reservedPx).toInt().coerceAtLeast(1)
        return 0
    }

    companion object {
        /**
         * Names one screen in one orientation, for settings remembered per device and orientation
         * (one-handed mode, PLAN §8.10): the screen's smallest width in dp tells a foldable's cover
         * screen from its inner one and a phone from a tablet.
         * Returns: for example `sw411-portrait` or `sw673-landscape`.
         */
        fun screenKey(smallestWidthDp: Int, landscape: Boolean): String =
            "sw$smallestWidthDp-${if (landscape) "landscape" else "portrait"}"
    }
}
