package com.redurbabat.feedback.security

/**
 * How long the management UI may stay unlocked after it left the foreground.
 *
 * [NEVER] is deliberately selectable but never a default: it keeps an unlocked session alive for
 * as long as the process lives, so the owner has to choose it explicitly.
 */
enum class AutoLockTimeout(
    val storageKey: String,
    val idleMillis: Long?,
    val label: String,
) {
    IMMEDIATE("immediate", 0L, "Sofort"),
    SECONDS_30("30s", 30_000L, "30 Sekunden"),
    MINUTES_1("1m", 60_000L, "1 Minute"),
    MINUTES_5("5m", 300_000L, "5 Minuten"),
    NEVER("never", null, "Nie"),
    ;

    companion object {
        val DEFAULT = SECONDS_30

        /** Strict lookup. Returns null for unknown keys so callers can fail closed. */
        fun fromStorageKey(value: String?): AutoLockTimeout? =
            entries.firstOrNull { it.storageKey == value }
    }
}
