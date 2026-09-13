package com.redurbabat.feedback.security

/**
 * Pure auto-lock rules for the management UI.
 *
 * Idle time is measured with a monotonic source (`SystemClock.elapsedRealtime`), not with the wall
 * clock: the wall clock is user settable, so an auto-lock anchored to it could be skipped by moving
 * the system time forward. The monotonic source restarts at boot, which is safe here because a
 * reboot also kills the process and [shouldLockOnStart] then applies.
 */
object AutoLockPolicy {

    /**
     * An unlocked session is process-local and never persisted, so a configured lock always starts
     * locked again.
     */
    fun shouldLockOnStart(lockConfigured: Boolean): Boolean = lockConfigured

    /**
     * Whether an app that went to the background at [backgroundedAtElapsedMillis] must be locked
     * again at [nowElapsedMillis].
     */
    fun shouldLockAfterBackground(
        timeout: AutoLockTimeout,
        backgroundedAtElapsedMillis: Long,
        nowElapsedMillis: Long,
    ): Boolean {
        val idleMillis = timeout.idleMillis ?: return false
        if (idleMillis == 0L) {
            return true
        }
        val elapsed = nowElapsedMillis - backgroundedAtElapsedMillis
        // A negative delta means the monotonic source is not trustworthy here. Fail closed.
        if (elapsed < 0L) {
            return true
        }
        return elapsed >= idleMillis
    }

    /** Remaining idle time before [shouldLockAfterBackground] turns true, for UI countdowns. */
    fun remainingIdleMillis(
        timeout: AutoLockTimeout,
        backgroundedAtElapsedMillis: Long,
        nowElapsedMillis: Long,
    ): Long? {
        val idleMillis = timeout.idleMillis ?: return null
        val elapsed = nowElapsedMillis - backgroundedAtElapsedMillis
        if (elapsed < 0L) {
            return 0L
        }
        return (idleMillis - elapsed).coerceAtLeast(0L)
    }
}
