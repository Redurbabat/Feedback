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
    fun `lockout grows after fifth failure and caps at fifteen minutes`() {
        assertEquals(0L, AppLockPolicy.lockoutDelayMillis(4))
        assertEquals(30_000L, AppLockPolicy.lockoutDelayMillis(5))
        assertEquals(60_000L, AppLockPolicy.lockoutDelayMillis(6))
        assertEquals(120_000L, AppLockPolicy.lockoutDelayMillis(7))
        assertEquals(300_000L, AppLockPolicy.lockoutDelayMillis(8))
        assertEquals(900_000L, AppLockPolicy.lockoutDelayMillis(50))
    }
}
