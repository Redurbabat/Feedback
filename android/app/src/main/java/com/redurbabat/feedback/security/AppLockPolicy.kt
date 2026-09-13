package com.redurbabat.feedback.security

/** Pure policy for the local Feedback UI lock. */
object AppLockPolicy {
    const val MIN_PIN_DIGITS = 6
    const val MIN_PASSPHRASE_LENGTH = 8
    const val MAX_SECRET_LENGTH = 128

    fun isAcceptableSecret(secret: CharArray): Boolean {
        if (secret.isEmpty() || secret.size > MAX_SECRET_LENGTH) {
            return false
        }
        val allDigits = secret.all { it in '0'..'9' }
        return if (allDigits) {
            secret.size >= MIN_PIN_DIGITS
        } else {
            secret.size >= MIN_PASSPHRASE_LENGTH
        }
    }

    /** Persistent rate limit after failed unlock attempts. First four failures have no delay. */
    fun lockoutDelayMillis(failedAttempts: Int): Long = when {
        failedAttempts < 5 -> 0L
        failedAttempts == 5 -> 30_000L
        failedAttempts == 6 -> 60_000L
        failedAttempts == 7 -> 120_000L
        failedAttempts == 8 -> 300_000L
        else -> 900_000L
    }
}
