package com.redurbabat.feedback.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    @Test
    fun `accepts six digit pin and stronger passphrase`() {
        assertTrue(AppLockPolicy.isAcceptableSecret("123456".toCharArray()))
        assertTrue(AppLockPolicy.isAcceptableSecret("lange-passphrase".toCharArray()))
    }

    @Test
    fun `rejects short pin and short non numeric secret`() {
        assertFalse(AppLockPolicy.isAcceptableSecret("12345".toCharArray()))
        assertFalse(AppLockPolicy.isAcceptableSecret("abc123".toCharArray()))
        assertFalse(AppLockPolicy.isAcceptableSecret(CharArray(0)))
    }

    @Test
    fun `rejects a secret beyond the maximum length`() {
        val tooLong = CharArray(AppLockPolicy.MAX_SECRET_LENGTH + 1) { '7' }
        assertFalse(AppLockPolicy.isAcceptableSecret(tooLong))
        val atLimit = CharArray(AppLockPolicy.MAX_SECRET_LENGTH) { '7' }
        assertTrue(AppLockPolicy.isAcceptableSecret(atLimit))
    }

    @Test
    fun `lockout grows after fifth failure and caps at fifteen minutes`() {
        assertEquals(0L, AppLockPolicy.lockoutDelayMillis(4))
        assertEquals(30_000L, AppLockPolicy.lockoutDelayMillis(5))
        assertEquals(60_000L, AppLockPolicy.lockoutDelayMillis(6))
        assertEquals(120_000L, AppLockPolicy.lockoutDelayMillis(7))
        assertEquals(300_000L, AppLockPolicy.lockoutDelayMillis(8))
        assertEquals(900_000L, AppLockPolicy.lockoutDelayMillis(50))
    }

    @Test
    fun `free attempts count down to zero`() {
        assertEquals(5, AppLockPolicy.remainingFreeAttempts(0))
        assertEquals(4, AppLockPolicy.remainingFreeAttempts(1))
        assertEquals(1, AppLockPolicy.remainingFreeAttempts(4))
        assertEquals(0, AppLockPolicy.remainingFreeAttempts(5))
        assertEquals(0, AppLockPolicy.remainingFreeAttempts(99))
    }

    @Test
    fun `lockout after failure is anchored to the current time`() {
        assertEquals(AppLockLockout.NONE, AppLockPolicy.lockoutAfterFailure(4, 5_000L))

        val fifth = AppLockPolicy.lockoutAfterFailure(5, 5_000L)
        assertEquals(5_000L, fifth.startedAtEpochMillis)
        assertEquals(30_000L, fifth.durationMillis)
        assertEquals(30_000L, fifth.remainingMillis(5_000L))
        assertEquals(0L, fifth.remainingMillis(35_000L))

        val capped = AppLockPolicy.lockoutAfterFailure(40, 5_000L)
        assertEquals(900_000L, capped.durationMillis)
    }
}
