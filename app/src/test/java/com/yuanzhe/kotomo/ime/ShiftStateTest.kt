package com.yuanzhe.kotomo.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ShiftState] Gboard-style double-tap Caps Lock transitions.
 */
class ShiftStateTest {
    @Test
    fun singleTapLatchesAndConsumeOnceClears() {
        val s = ShiftState()
        assertEquals(Shift.ONCE, s.tap(100))
        s.consumeOnce()
        assertEquals(Shift.OFF, s.state)
        // Negative: consumeOnce disarms the pending double-tap window, so an immediate Shift goes to ONCE.
        assertEquals(Shift.ONCE, s.tap(150))
    }

    @Test
    fun quickDoubleTapFromOffLocksUntilTappedAgain() {
        val s = ShiftState()
        assertEquals(Shift.ONCE, s.tap(100))
        assertEquals(Shift.LOCKED, s.tap(100 + ShiftState.DOUBLE_TAP_MS - 1))
        s.consumeOnce()
        assertEquals(Shift.LOCKED, s.state)
        assertEquals(Shift.OFF, s.tap(1000))
        // Rapid tap right after unlocking must latch ONCE, not bounce back to LOCKED.
        assertEquals(Shift.ONCE, s.tap(1100))
    }

    @Test
    fun slowSecondTapCancelsShift() {
        val s = ShiftState()
        assertEquals(Shift.ONCE, s.tap(100))
        // Exact threshold boundary: must cancel rather than lock.
        assertEquals(Shift.OFF, s.tap(100 + ShiftState.DOUBLE_TAP_MS))
    }

    @Test
    fun autoCapsSingleTapTurnsOffAndQuickDoubleTapLocks() {
        val s = ShiftState()
        s.applyAutoCaps(true)
        assertEquals(Shift.ONCE, s.state)
        // Negative: single tap cancels auto-caps instead of locking.
        assertEquals(Shift.OFF, s.tap(500))
        assertEquals(Shift.LOCKED, s.tap(650))
        // Auto-caps updates never clear Caps Lock.
        s.applyAutoCaps(false)
        assertEquals(Shift.LOCKED, s.state)
    }

    @Test
    fun staleManualOnceQuickDoubleTapLocks() {
        val s = ShiftState()
        assertEquals(Shift.ONCE, s.tap(100))
        assertEquals(Shift.OFF, s.tap(2000))
        assertEquals(Shift.LOCKED, s.tap(2150))
    }

    @Test
    fun otherKeyBetweenQuickShiftTapsPreventsLock() {
        val s = ShiftState()
        assertEquals(Shift.ONCE, s.tap(100))
        s.cancelDoubleTap()
        assertEquals(Shift.OFF, s.tap(200))

        s.reset()
        s.applyAutoCaps(true)
        assertEquals(Shift.OFF, s.tap(100))
        s.cancelDoubleTap()
        assertEquals(Shift.ONCE, s.tap(200))
    }

    @Test
    fun nonMonotonicTimestampDoesNotLock() {
        val s = ShiftState()
        assertEquals(Shift.ONCE, s.tap(500))
        assertEquals(Shift.OFF, s.tap(400))
    }

    @Test
    fun resetClearsLockAndPendingTap() {
        val s = ShiftState()
        assertEquals(Shift.ONCE, s.tap(100))
        assertEquals(Shift.LOCKED, s.tap(200))
        s.reset()
        assertEquals(Shift.OFF, s.state)
        assertEquals(Shift.ONCE, s.tap(250))
    }
}
