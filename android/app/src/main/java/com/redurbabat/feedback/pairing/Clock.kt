package com.redurbabat.feedback.pairing

/**
 * Wall clock abstraction so expiry and issuedAt handling can be tested deterministically.
 * `java.time` is unavailable on minSdk 24, so epoch milliseconds are the exchange format.
 */
interface Clock {
    fun nowEpochMillis(): Long
}

/** Production clock. */
object SystemWallClock : Clock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
