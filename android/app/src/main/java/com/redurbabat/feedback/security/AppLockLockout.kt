package com.redurbabat.feedback.security

/**
 * A persisted unlock lockout, stored as start plus duration instead of an absolute deadline.
 *
 * Storing the deadline alone would let a rewound system clock turn into a *shorter* wait once the
 * clock is corrected. With start plus duration a rewind is detectable ([remainingMillis] then
 * reports the full duration again) and the store re-anchors the window.
 *
 * Moving the wall clock forward still ends a lockout early. That is an accepted, documented limit:
 * a lockout has to survive process death and reboots, and the device offers no trusted time source
 * for this. The lockout raises the cost of guessing; it is not an offline-attack defence. Offline
 * resistance comes from PBKDF2 plus the Keystore-sealed verifier.
 */
data class AppLockLockout(
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
) {
    init {
        require(durationMillis >= 0L) { "Lockout duration must not be negative" }
    }

    val isActive: Boolean get() = durationMillis > 0L

    /** Remaining wait in milliseconds, or 0 when the lockout has elapsed or is not set. */
    fun remainingMillis(nowEpochMillis: Long): Long {
        if (durationMillis <= 0L) {
            return 0L
        }
        val elapsed = nowEpochMillis - startedAtEpochMillis
        if (elapsed < 0L) {
            // The clock moved backwards; do not shorten the wait.
            return durationMillis
        }
        return (durationMillis - elapsed).coerceAtLeast(0L)
    }

    /** Same window, re-anchored to [nowEpochMillis] after a detected backwards clock jump. */
    fun reAnchoredTo(nowEpochMillis: Long): AppLockLockout =
        copy(startedAtEpochMillis = nowEpochMillis)

    companion object {
        val NONE = AppLockLockout(startedAtEpochMillis = 0L, durationMillis = 0L)
    }
}
