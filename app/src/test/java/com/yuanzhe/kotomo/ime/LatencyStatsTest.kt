package com.yuanzhe.kotomo.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers the percentile arithmetic behind spike S1's latency report. */
class LatencyStatsTest {
    @Test
    fun emptyHasNoPercentile() {
        assertNull(LatencyStats().percentile(50))
    }

    @Test
    fun nearestRankPercentiles() {
        val stats = LatencyStats()
        (1L..100L).shuffled().forEach(stats::add)
        assertEquals(50L, stats.percentile(50))
        assertEquals(95L, stats.percentile(95))
        assertEquals(100L, stats.percentile(100))
        assertEquals(1L, stats.percentile(0))
    }

    @Test
    fun oldestSamplesAreDroppedWhenFull() {
        val stats = LatencyStats(capacity = 4)
        listOf(100L, 100L, 1L, 2L, 3L, 4L).forEach(stats::add)
        assertEquals(4, stats.count)
        assertEquals(4L, stats.percentile(100))
    }
}
