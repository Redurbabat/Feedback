package com.redurbabat.feedback.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockLockoutTest {

    @Test
    fun `no lockout has no remaining time`() {
        assertFalse(AppLockLockout.NONE.isActive)
        assertEquals(0L, AppLockLockout.NONE.remainingMillis(0L))
        assertEquals(0L, AppLockLockout.NONE.remainingMillis(Long.MAX_VALUE))
    }

    @Test
    fun `remaining time counts down and never goes negative`() {
        val lockout = AppLockLockout(startedAtEpochMillis = 1_000L, durationMillis = 30_000L)
        assertTrue(lockout.isActive)
        assertEquals(30_000L, lockout.remainingMillis(1_000L))
        assertEquals(20_000L, lockout.remainingMillis(11_000L))
        assertEquals(0L, lockout.remainingMillis(31_000L))
        assertEquals(0L, lockout.remainingMillis(99_000L))
    }

    @Test
    fun `a rewound clock does not shorten the wait`() {
        val lockout = AppLockLockout(startedAtEpochMillis = 1_000_000L, durationMillis = 60_000L)
        assertEquals(60_000L, lockout.remainingMillis(1L))

        val reAnchored = lockout.reAnchoredTo(1L)
        assertEquals(1L, reAnchored.startedAtEpochMillis)
        assertEquals(60_000L, reAnchored.durationMillis)
        assertEquals(60_000L, reAnchored.remainingMillis(1L))
        assertEquals(30_000L, reAnchored.remainingMillis(30_001L))
    }

    @Test
    fun `a negative duration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppLockLockout(startedAtEpochMillis = 0L, durationMillis = -1L)
        }
    }
}
