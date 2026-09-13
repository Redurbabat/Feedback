package com.redurbabat.feedback.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockReauthPolicyTest {

    @Test
    fun `disabling the lock always asks for the secret`() {
        assertTrue(
            AppLockReauthPolicy.requiresReauthentication(
                action = SensitiveAction.DISABLE_APP_LOCK,
                lastAuthenticatedAtElapsedMillis = 10_000L,
                nowElapsedMillis = 10_000L,
            ),
        )
        assertTrue(SensitiveAction.DISABLE_APP_LOCK.alwaysRequiresSecret)
    }

    @Test
    fun `a very recent unlock covers the windowed actions`() {
        for (action in SensitiveAction.entries.filterNot { it.alwaysRequiresSecret }) {
            assertFalse(
                "$action should reuse a fresh unlock",
                AppLockReauthPolicy.requiresReauthentication(action, 10_000L, 20_000L),
            )
        }
    }

    @Test
    fun `the window expires exactly at its boundary`() {
        val action = SensitiveAction.FORGET_REGISTRATION
        val window = AppLockReauthPolicy.REAUTH_WINDOW_MILLIS
        assertFalse(
            AppLockReauthPolicy.requiresReauthentication(action, 1_000L, 1_000L + window - 1L),
        )
        assertTrue(
            AppLockReauthPolicy.requiresReauthentication(action, 1_000L, 1_000L + window),
        )
    }

    @Test
    fun `an unknown or rewound authentication time asks again`() {
        val action = SensitiveAction.GRANT_SYSTEM_INFO
        assertTrue(AppLockReauthPolicy.requiresReauthentication(action, null, 5_000L))
        assertTrue(AppLockReauthPolicy.requiresReauthentication(action, 500_000L, 1_000L))
    }
}
