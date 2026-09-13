package com.redurbabat.feedback.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLockPolicyTest {

    @Test
    fun `a configured lock always starts locked`() {
        assertTrue(AutoLockPolicy.shouldLockOnStart(lockConfigured = true))
        assertFalse(AutoLockPolicy.shouldLockOnStart(lockConfigured = false))
    }

    @Test
    fun `immediate locks as soon as the app leaves the foreground`() {
        assertTrue(
            AutoLockPolicy.shouldLockAfterBackground(
                timeout = AutoLockTimeout.IMMEDIATE,
                backgroundedAtElapsedMillis = 10_000L,
                nowElapsedMillis = 10_000L,
            ),
        )
    }

    @Test
    fun `never keeps the session unlocked`() {
        assertFalse(
            AutoLockPolicy.shouldLockAfterBackground(
                timeout = AutoLockTimeout.NEVER,
                backgroundedAtElapsedMillis = 0L,
                nowElapsedMillis = Long.MAX_VALUE / 2,
            ),
        )
        assertNull(
            AutoLockPolicy.remainingIdleMillis(
                timeout = AutoLockTimeout.NEVER,
                backgroundedAtElapsedMillis = 0L,
                nowElapsedMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `timed options lock exactly at their idle boundary`() {
        val cases = listOf(
            AutoLockTimeout.SECONDS_30 to 30_000L,
            AutoLockTimeout.MINUTES_1 to 60_000L,
            AutoLockTimeout.MINUTES_5 to 300_000L,
        )
        for ((timeout, idleMillis) in cases) {
            assertEquals(idleMillis, timeout.idleMillis)
            assertFalse(
                "$timeout must stay unlocked one millisecond early",
                AutoLockPolicy.shouldLockAfterBackground(timeout, 1_000L, 1_000L + idleMillis - 1L),
            )
            assertTrue(
                "$timeout must lock at its boundary",
                AutoLockPolicy.shouldLockAfterBackground(timeout, 1_000L, 1_000L + idleMillis),
            )
        }
    }

    @Test
    fun `a backwards monotonic jump fails closed`() {
        assertTrue(
            AutoLockPolicy.shouldLockAfterBackground(
                timeout = AutoLockTimeout.MINUTES_5,
                backgroundedAtElapsedMillis = 500_000L,
                nowElapsedMillis = 1_000L,
            ),
        )
        assertEquals(
            0L,
            AutoLockPolicy.remainingIdleMillis(
                timeout = AutoLockTimeout.MINUTES_5,
                backgroundedAtElapsedMillis = 500_000L,
                nowElapsedMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `remaining idle time counts down and stops at zero`() {
        assertEquals(
            30_000L,
            AutoLockPolicy.remainingIdleMillis(AutoLockTimeout.SECONDS_30, 1_000L, 1_000L),
        )
        assertEquals(
            10_000L,
            AutoLockPolicy.remainingIdleMillis(AutoLockTimeout.SECONDS_30, 1_000L, 21_000L),
        )
        assertEquals(
            0L,
            AutoLockPolicy.remainingIdleMillis(AutoLockTimeout.SECONDS_30, 1_000L, 99_000L),
        )
    }

    @Test
    fun `storage keys round trip and unknown keys are rejected`() {
        for (timeout in AutoLockTimeout.entries) {
            assertEquals(timeout, AutoLockTimeout.fromStorageKey(timeout.storageKey))
        }
        assertNull(AutoLockTimeout.fromStorageKey("forever"))
        assertNull(AutoLockTimeout.fromStorageKey(""))
        assertNull(AutoLockTimeout.fromStorageKey(null))
        assertEquals(AutoLockTimeout.SECONDS_30, AutoLockTimeout.DEFAULT)
    }
}
