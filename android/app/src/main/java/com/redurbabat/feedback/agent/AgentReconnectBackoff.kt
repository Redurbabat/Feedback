package com.redurbabat.feedback.agent

import kotlin.math.min

/** Exponential reconnect delay with bounded jitter. Pure logic so it can be JVM-tested. */
class AgentReconnectBackoff(
    private val initialDelayMs: Long = 5_000L,
    private val maxDelayMs: Long = 300_000L,
) {
    private var attempt = 0

    fun reset() {
        attempt = 0
    }

    /**
     * Returns an 80-120% jittered exponential delay. [randomUnit] must be in 0..1 and is injected
     * by tests; production callers can use Math.random().
     */
    fun nextDelayMillis(randomUnit: Double = Math.random()): Long {
        require(randomUnit in 0.0..1.0) { "randomUnit must be between 0 and 1" }
        val exponent = min(attempt, MAX_SHIFT)
        val base = min(saturatingShift(initialDelayMs, exponent), maxDelayMs)
        attempt = min(attempt + 1, MAX_SHIFT)
        val multiplier = 0.8 + (randomUnit * 0.4)
        return (base.toDouble() * multiplier).toLong().coerceAtLeast(1L)
    }

    private fun saturatingShift(value: Long, shift: Int): Long {
        var result = value
        repeat(shift) {
            if (result >= maxDelayMs / 2L) {
                return maxDelayMs
            }
            result *= 2L
        }
        return result
    }

    companion object {
        private const val MAX_SHIFT = 16
    }
}
