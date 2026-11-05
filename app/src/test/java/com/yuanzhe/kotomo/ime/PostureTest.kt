package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.settings.SplitLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers tablets and foldables (T14, PLAN §8.10): size classes, the split rule beside a hinge,
 * the height limit in tabletop posture and short windows, and no key on the hinge.
 */
class PostureTest {
    private val phone = Posture(411f, 891f)
    private val landscape = Posture(891f, 411f)
    private val tablet = Posture(1280f, 800f)

    @Test
    fun splitFollowsTheWidthAndTheChoice() {
        assertFalse(phone.split(SplitLayout.AUTO))
        assertTrue(tablet.split(SplitLayout.AUTO))
        assertFalse(tablet.split(SplitLayout.NEVER))
        assertTrue(phone.split(SplitLayout.ALWAYS))
        assertTrue(phone.wide.not() && tablet.wide)
    }

    @Test
    fun aDividingHingeAlwaysSplits() {
        val book = Posture(841f, 701f, hingeX = 1050f..1060f)
        assertTrue(book.split(SplitLayout.NEVER))
    }

    @Test
    fun keysEndAboveTheHingeWhenHalfFolded() {
        val tabletop = Posture(841f, 701f, tabletopBottom = 1100f)
        assertEquals(2000 - 1100 - 150, tabletop.heightLimit(2000, 150, 2.5f, 0.45f, tabletop = true))
        // Negative: with the switch off, a tall window has no limit.
        assertEquals(0, tabletop.heightLimit(2000, 150, 2.5f, 0.45f, tabletop = false))
        assertEquals(0, phone.heightLimit(2000, 150, 2.5f, 0.45f, tabletop = true))
    }

    @Test
    fun shortWindowsLimitTheKeys() {
        val limit = landscape.heightLimit(1028, 120, 2.5f, 0.45f, tabletop = true)
        assertEquals((411f * 2.5f * 0.45f - 120).toInt(), limit)
        assertTrue(landscape.short && !phone.short)
    }

    @Test
    fun noKeySpansTheHinge() {
        val width = 2100f
        val hinge = 1045f..1055f
        val half = 900f
        for (layout in listOf(Layout.letters(Mode.MIXED, false, split = true, hinge = true), Layout.symbols(Mode.ENGLISH, split = true, hinge = true))) {
            val keys = layout.place(width, 120f, 10f, 8f, half)
            keys.forEach { k -> assertTrue("${k.key.label} on the hinge", k.right <= hinge.start || k.left >= hinge.endInclusive) }
            assertEquals(2, keys.count { it.key.action == KeyAction.Space })
        }
        // Negative: without a hinge the split layout keeps one space bar across the middle.
        assertEquals(1, Layout.letters(Mode.MIXED, false, split = true).rows.last().keys.count { it.action == KeyAction.Space })
    }
}
