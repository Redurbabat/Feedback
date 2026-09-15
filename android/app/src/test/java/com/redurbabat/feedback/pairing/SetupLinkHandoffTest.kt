package com.redurbabat.feedback.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SetupLinkHandoffTest {

    /** The handoff is process wide, so a leftover from another test would be another test's link. */
    @Before
    fun drain() {
        SetupLinkHandoff.take()
    }

    @Test
    fun `nothing was handed over when no link arrived`() {
        assertNull(SetupLinkHandoff.take())
    }

    @Test
    fun `an arrival is read once and then gone`() {
        SetupLinkHandoff.offer(SetupLinkArrival("https://feedback.example.com"))

        assertEquals("https://feedback.example.com", SetupLinkHandoff.take()?.offeredOrigin)
        // A rotation or a theme change recreates the activity; the link must not be offered again.
        assertNull(SetupLinkHandoff.take())
    }

    /**
     * The distinction the whole type exists for. A link that the parser could not read is still a
     * link that arrived, and the difference between "no origin" and "no link" has to survive the
     * handoff - otherwise the app opens with an empty field and says nothing, which is what used
     * to happen to every forwarded link carrying a tracking parameter.
     */
    @Test
    fun `an unreadable link is an arrival, not an absence`() {
        SetupLinkHandoff.offer(SetupLinkArrival(offeredOrigin = null))

        val arrival = SetupLinkHandoff.take()
        assertNotNull("an unreadable link must still arrive", arrival)
        assertNull(arrival?.offeredOrigin)
    }

    /** Two taps before the UI picks either up: the second is the one the owner is looking at. */
    @Test
    fun `the newest arrival replaces one that was not picked up`() {
        SetupLinkHandoff.offer(SetupLinkArrival("https://feedback.example.com"))
        SetupLinkHandoff.offer(SetupLinkArrival("https://feedback.example.com:8443"))

        assertEquals("https://feedback.example.com:8443", SetupLinkHandoff.take()?.offeredOrigin)
    }
}
