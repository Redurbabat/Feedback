package com.redurbabat.feedback.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LockoutCountdownTest {

    @Test
    fun `formats the ladder steps as minutes and seconds`() {
        assertEquals("00:30", formatLockoutCountdown(30_000L))
        assertEquals("01:00", formatLockoutCountdown(60_000L))
        assertEquals("02:00", formatLockoutCountdown(120_000L))
        assertEquals("05:00", formatLockoutCountdown(300_000L))
        assertEquals("15:00", formatLockoutCountdown(900_000L))
    }

    @Test
    fun `rounds up so the last second stays visible`() {
        assertEquals("00:01", formatLockoutCountdown(1L))
        assertEquals("00:01", formatLockoutCountdown(999L))
        assertEquals("00:01", formatLockoutCountdown(1_000L))
        assertEquals("00:02", formatLockoutCountdown(1_001L))
        assertEquals("00:24", formatLockoutCountdown(23_500L))
    }

    @Test
    fun `zero and negative remainders read as zero`() {
        assertEquals("00:00", formatLockoutCountdown(0L))
        assertEquals("00:00", formatLockoutCountdown(-5_000L))
    }
}
