package com.redurbabat.feedback.ui

/**
 * Formats a remaining app-lock wait as `mm:ss`.
 *
 * Kept free of `androidx.compose.*` so the rounding rules stay unit testable. Seconds round up, so
 * the countdown shows `00:01` until the wait is really over and never flashes `00:00` while the
 * unlock button is still disabled.
 */
fun formatLockoutCountdown(remainingMillis: Long): String {
    val totalSeconds = ((remainingMillis + 999L) / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
