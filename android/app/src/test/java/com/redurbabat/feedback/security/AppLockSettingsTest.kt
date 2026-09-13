package com.redurbabat.feedback.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockSettingsTest {

    @Test
    fun `defaults lock after thirty seconds without biometrics`() {
        assertEquals(AutoLockTimeout.SECONDS_30, AppLockSettings.DEFAULT.autoLockTimeout)
        assertFalse(AppLockSettings.DEFAULT.biometricUnlockEnabled)
    }

    @Test
    fun `every option round trips`() {
        for (timeout in AutoLockTimeout.entries) {
            for (biometric in listOf(false, true)) {
                val settings = AppLockSettings(timeout, biometric)
                val decoded = AppLockSettings.decodeOrNull(settings.encode())
                assertEquals(settings, decoded)
            }
        }
    }

    @Test
    fun `encoded form carries no plaintext secret fields`() {
        val encoded = AppLockSettings(AutoLockTimeout.MINUTES_5, true).encode()
        assertTrue(encoded.contains("\"autoLockTimeout\":\"5m\""))
        assertFalse(encoded.contains("pin"))
        assertFalse(encoded.contains("passphrase"))
        assertFalse(encoded.contains("verifier"))
    }

    @Test
    fun `malformed or unknown stored values fail closed`() {
        val rejected = listOf(
            null,
            "",
            "   ",
            "not json",
            "[]",
            "{}",
            """{"version":2,"autoLockTimeout":"30s","biometricUnlockEnabled":false}""",
            """{"version":1,"autoLockTimeout":"forever","biometricUnlockEnabled":false}""",
            """{"version":1,"autoLockTimeout":"","biometricUnlockEnabled":false}""",
            """{"version":1,"biometricUnlockEnabled":false}""",
            """{"version":1,"autoLockTimeout":"30s"}""",
            """{"autoLockTimeout":"30s","biometricUnlockEnabled":false}""",
        )
        for (value in rejected) {
            assertNull("must reject: $value", AppLockSettings.decodeOrNull(value))
        }
    }
}
