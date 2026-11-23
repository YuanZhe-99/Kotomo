package com.yuanzhe.kotomo.ime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the quick-paste strip's offer rules and chips and the panel's time buckets (PLAN §10.7;
 * tests QUICK-01 to QUICK-05).
 */
class QuickPasteTest {
    private val t0 = 1_800_000_000_000L

    private fun copied(text: String, at: Long = t0) = Copied(text, VerificationCodes.find(text), at)

    @Test
    fun offeredWithinTheWindowOnly() { // QUICK-01
        val q = QuickPaste()
        val c = copied("hello")
        q.offer(c)
        assertSame(c, q.current(t0 + 1_000))
        assertEquals(QuickPaste.WINDOW_MS - 1_000, q.remainingMs(t0 + 1_000))
        assertNull(q.current(t0 + QuickPaste.WINDOW_MS))
        assertNull(q.offer.value)
    }

    @Test
    fun shownOncePerClip() { // QUICK-02 (negative)
        val q = QuickPaste()
        val c = copied("hello")
        q.offer(c)
        q.done()
        q.offer(c)
        assertNull(q.current(t0 + 1))
        // The same text copied again later is a new clip.
        val again = copied("hello", t0 + 5)
        q.offer(again)
        assertSame(again, q.current(t0 + 6))
    }

    @Test
    fun ambiguousCodesComeFirstWithTheText() { // QUICK-03
        val chips = QuickPaste.chips(copied("【某银行】您的验证码为482913，5分钟内有效。尾号7731的账户正在登录"), numberField = false)
        assertEquals(3, chips.size)
        val first = chips[0] as QuickPaste.Chip.Code
        assertEquals("482913", first.code.value)
        assertTrue(first.best)
        assertEquals(false, (chips[1] as QuickPaste.Chip.Code).best)
        assertTrue(chips[2] is QuickPaste.Chip.Text)
    }

    @Test
    fun numberFieldsAndBareCodesShowOnlyCodes() { // QUICK-04
        assertEquals(1, QuickPaste.chips(copied("Your code is 482913"), numberField = true).size)
        assertEquals(1, QuickPaste.chips(copied("482913"), numberField = false).size)
        // Plain text without a code still shows in a number field: there is nothing else to offer.
        assertTrue(QuickPaste.chips(copied("hello"), numberField = true).single() is QuickPaste.Chip.Text)
    }

    @Test
    fun timeBuckets() { // QUICK-05
        val m = 60_000L
        assertEquals(Ago.JustNow, Ago.of(t0, t0 + 5_000))
        assertEquals(Ago.Minutes(3), Ago.of(t0 + 3 * m, t0))
        assertEquals(Ago.Hours(5), Ago.of(t0 + 5 * 60 * m, t0))
        assertEquals(Ago.Yesterday, Ago.of(t0 + 30 * 60 * m, t0))
        assertEquals(Ago.Days(2), Ago.of(t0 + 50 * 60 * m, t0))
    }

    @Test
    fun imageOfferDependsOnFieldSupport() { // QUICK-06
        val image = CopiedImage(byteArrayOf(1, 2, 3), "image/png", 64, 64, "0".repeat(64))
        val offer = Copied("", emptyList(), t0, image)
        val shown = QuickPaste.chips(offer, numberField = false, acceptsImages = true)
        assertEquals(listOf(QuickPaste.Chip.Image(image)), shown)
        assertTrue(QuickPaste.chips(offer, numberField = false, acceptsImages = false).isEmpty())
        assertTrue(QuickPaste.chips(offer, numberField = true, acceptsImages = true).isEmpty())
    }

    @Test
    fun suppressInHidesOnlyInThatFieldUntilNewClip() { // QUICK-07
        val q = QuickPaste()
        val c1 = copied("first clip", t0)
        q.offer(c1)
        q.suppressIn("pkg.a#1")
        assertNull(q.current(t0 + 1_000, "pkg.a#1"))
        assertSame(c1, q.current(t0 + 1_000, "pkg.b#2"))
        assertEquals(QuickPaste.WINDOW_MS - 1_000, q.remainingMs(t0 + 1_000))

        // A newer copy clears prior per-field suppressions so non-empty fields still see it.
        val c2 = copied("second clip", t0 + 2_000)
        q.offer(c2)
        assertSame(c2, q.current(t0 + 2_500, "pkg.a#1"))
    }

    @Test
    fun doneDismissesEverywhereEvenAfterFieldSuppression() { // QUICK-08
        val q = QuickPaste()
        val c1 = copied("dismissed clip", t0)
        q.offer(c1)
        q.suppressIn("pkg.a#1")
        q.done()
        assertNull(q.current(t0 + 1_000, "pkg.a#1"))
        assertNull(q.current(t0 + 1_000, "pkg.b#2"))
        q.offer(c1)
        assertNull(q.current(t0 + 1_500, "pkg.b#2"))
    }
}
