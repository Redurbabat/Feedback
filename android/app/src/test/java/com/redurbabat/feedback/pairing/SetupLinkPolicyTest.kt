package com.redurbabat.feedback.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupLinkPolicyTest {

    private val build = "https://feedback.example.com"

    private fun decide(
        link: String,
        buildDefault: String = build,
        registered: String? = null,
    ): SetupLinkDecision = SetupLinkPolicy.decide(
        offeredOrigin = SetupLink.parseServerOrNull(link)?.baseUrl,
        buildDefaultOrigin = buildDefault,
        registeredOrigin = registered,
    )

    private fun assertConfirmed(expectedOrigin: String, decision: SetupLinkDecision) {
        assertTrue("expected Confirmed, got $decision", decision is SetupLinkDecision.Confirmed)
        assertEquals(expectedOrigin, (decision as SetupLinkDecision.Confirmed).server.baseUrl)
    }

    @Test
    fun `confirms the origin the app was built for`() {
        assertConfirmed(build, decide("https://feedback.example.com/pair"))
        assertConfirmed(build, decide("https://feedback.example.com/pair/"))
        assertConfirmed(build, decide("https://FEEDBACK.EXAMPLE.COM/pair"))
    }

    /**
     * The whole point of the feature's design. Every one of these is a link that looks right to a
     * human and points somewhere else, and every one of them fails the same single test.
     */
    @Test
    fun `refuses every origin the app does not already know`() {
        val refused = listOf(
            "https://evil.example/pair",
            "https://feedback.example.com.evil.example/pair",
            "https://feedback-example.com/pair",
            "https://feedback.example.com.br/pair",
            "https://xn--feedback-example.com/pair",
        )
        for (link in refused) {
            assertTrue("must refuse: $link", decide(link) is SetupLinkDecision.Refused)
        }
    }

    /**
     * Compared over the whole origin, never over the host alone: a different port is a different
     * server, and a link that changes only the port would otherwise pass.
     */
    @Test
    fun `compares scheme host and port, not the host alone`() {
        assertTrue(decide("https://feedback.example.com:8443/pair") is SetupLinkDecision.Refused)
        assertTrue(
            decide(
                link = "https://feedback.example.com/pair",
                buildDefault = "https://feedback.example.com:8443",
            ) is SetupLinkDecision.Refused,
        )
        assertConfirmed(
            "https://feedback.example.com:8443",
            decide(
                link = "https://feedback.example.com:8443/pair",
                buildDefault = "https://feedback.example.com:8443",
            ),
        )
    }

    /** No anchor, nothing to be equal to, so nothing is accepted. */
    @Test
    fun `refuses everything when the build carries no default and nothing is paired`() {
        val decision = decide("https://feedback.example.com/pair", buildDefault = "")
        assertTrue(decision is SetupLinkDecision.Refused)
        assertTrue((decision as SetupLinkDecision.Refused).trusted.isEmpty())
    }

    @Test
    fun `accepts the origin of an existing registration as the second anchor`() {
        assertConfirmed(
            "https://paired.example.com",
            decide(
                link = "https://paired.example.com/pair",
                buildDefault = "",
                registered = "https://paired.example.com",
            ),
        )
        assertTrue(
            decide(
                link = "https://evil.example/pair",
                buildDefault = "",
                registered = "https://paired.example.com",
            ) is SetupLinkDecision.Refused,
        )
    }

    @Test
    fun `a refusal names both anchors so the owner can compare them`() {
        val decision = decide(
            link = "https://evil.example/pair",
            registered = "https://paired.example.com",
        )
        assertTrue(decision is SetupLinkDecision.Refused)
        val refusal = decision as SetupLinkDecision.Refused
        assertEquals("evil.example", refusal.offered.authority)
        assertEquals(
            listOf("feedback.example.com", "paired.example.com"),
            refusal.trusted.map { it.authority },
        )
    }

    /** Otherwise the refusal would read "trusts feedback.example.com or feedback.example.com". */
    @Test
    fun `names one anchor once when the build default and the registration agree`() {
        val decision = decide("https://evil.example/pair", registered = build)
        assertEquals(
            listOf("feedback.example.com"),
            (decision as SetupLinkDecision.Refused).trusted.map { it.authority },
        )
    }

    @Test
    fun `treats an unusable offer as unusable rather than as a match`() {
        assertEquals(SetupLinkDecision.Unusable, SetupLinkPolicy.decide(null, build, null))
        assertEquals(SetupLinkDecision.Unusable, SetupLinkPolicy.decide("", build, null))
        assertEquals(
            SetupLinkDecision.Unusable,
            SetupLinkPolicy.decide("https://192.168.1.10", build, null),
        )
        // Empty anchors must not make an empty offer "equal" to them.
        assertEquals(SetupLinkDecision.Unusable, SetupLinkPolicy.decide("", "", null))
    }

    @Test
    fun `an unreadable stored origin is not an anchor`() {
        val decision = decide(
            link = "https://evil.example/pair",
            buildDefault = "",
            registered = "not an origin",
        )
        assertTrue((decision as SetupLinkDecision.Refused).trusted.isEmpty())
    }
}
