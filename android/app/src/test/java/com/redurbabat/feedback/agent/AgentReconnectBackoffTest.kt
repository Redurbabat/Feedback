package com.redurbabat.feedback.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReconnectBackoffTest {
    @Test
    fun `delay grows exponentially and stays capped`() {
        val backoff = AgentReconnectBackoff(initialDelayMs = 1_000L, maxDelayMs = 8_000L)

        assertEquals(1_000L, backoff.nextDelayMillis(0.5))
        assertEquals(2_000L, backoff.nextDelayMillis(0.5))
        assertEquals(4_000L, backoff.nextDelayMillis(0.5))
        assertEquals(8_000L, backoff.nextDelayMillis(0.5))
        assertEquals(8_000L, backoff.nextDelayMillis(0.5))
    }

    @Test
    fun `reset returns to initial delay`() {
        val backoff = AgentReconnectBackoff(initialDelayMs = 1_000L, maxDelayMs = 8_000L)
        backoff.nextDelayMillis(0.5)
        backoff.nextDelayMillis(0.5)

        backoff.reset()

        assertEquals(1_000L, backoff.nextDelayMillis(0.5))
    }

    @Test
    fun `jitter remains within eighty to one hundred twenty percent`() {
        val low = AgentReconnectBackoff(initialDelayMs = 10_000L).nextDelayMillis(0.0)
        val high = AgentReconnectBackoff(initialDelayMs = 10_000L).nextDelayMillis(1.0)

        assertEquals(8_000L, low)
        assertEquals(12_000L, high)
        assertTrue(low < high)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid random input`() {
        AgentReconnectBackoff().nextDelayMillis(1.1)
    }
}
