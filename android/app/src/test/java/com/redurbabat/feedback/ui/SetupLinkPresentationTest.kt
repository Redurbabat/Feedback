package com.redurbabat.feedback.ui

import com.redurbabat.feedback.pairing.SetupLink
import com.redurbabat.feedback.pairing.SetupLinkArrival
import com.redurbabat.feedback.pairing.SetupLinkHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The path from a tapped link to a line on the screen.
 *
 * The steps are walked in the order the app walks them - parse, hand over, pick up, put into
 * words - because the defect this pins was not in any one of them. Each step was right on its own;
 * the link fell through the seam between them, where an unparsable link became a null that meant
 * "nothing arrived".
 */
class SetupLinkPresentationTest {

    private val build = "https://feedback.example.com"

    @Before
    fun drain() {
        SetupLinkHandoff.take()
    }

    /** Exactly what SetupLinkActivity writes and what MainActivity reads back. */
    private fun deliver(link: String): SetupLinkArrival {
        SetupLinkHandoff.offer(SetupLinkArrival(SetupLink.parseServerOrNull(link)?.baseUrl))
        return requireNotNull(SetupLinkHandoff.take()) {
            "a link that arrived must not vanish on the way to the UI"
        }
    }

    private fun outcomeFor(
        arrival: SetupLinkArrival,
        registered: String? = null,
        linksIgnored: Boolean = false,
        paired: Boolean = false,
        pairingInProgress: Boolean = false,
    ): SetupLinkOutcome = SetupLinkPresentation.outcome(
        arrival = arrival,
        buildDefaultOrigin = build,
        registeredOrigin = registered,
        linksIgnored = linksIgnored,
        paired = paired,
        pairingInProgress = pairingInProgress,
    )

    private fun announcementFor(outcome: SetupLinkOutcome): SetupLinkOutcome.Announced {
        assertTrue("expected an announcement, got $outcome", outcome is SetupLinkOutcome.Announced)
        return outcome as SetupLinkOutcome.Announced
    }

    /** What the owner actually gets to read, whichever of the two outcomes it was. */
    private fun visibleText(outcome: SetupLinkOutcome): String = when (outcome) {
        is SetupLinkOutcome.Announced -> outcome.message
        is SetupLinkOutcome.FillsAddressField -> outcome.notice
    }

    /**
     * The regression this file exists for. `android:path="/pair"` matches on the path alone, so a
     * forwarded link with a tracking parameter passes the intent filter, opens the app and is then
     * refused by the parser. That is the normal case, not an attack - and it used to end with an
     * empty address field and nothing on screen.
     *
     * Asserting that the parser says null would not have caught it: the parser was always right.
     * What has to hold is that the refusal is visible.
     */
    @Test
    fun `a link the parser cannot read is refused out loud, not dropped`() {
        val link = "https://feedback.example.com/pair?utm_source=mail"
        assertNull("the parser is supposed to refuse this", SetupLink.parseServerOrNull(link))

        val announced = announcementFor(outcomeFor(deliver(link)))

        assertTrue(announced.rejected)
        assertTrue("the refusal must say something", announced.message.isNotBlank())
        assertTrue("must read as a refusal: ${announced.message}", "abgelehnt" in announced.message)
    }

    /**
     * Every arrival ends in something the owner can read. No input reaches an outcome that writes
     * nothing - silence is not one of the cases.
     */
    @Test
    fun `every link that arrives puts something on the screen`() {
        val links = listOf(
            "https://feedback.example.com/pair",
            "https://feedback.example.com/pair/",
            "https://feedback.example.com/pair?utm_source=mail",
            "https://feedback.example.com/pair#anker",
            "https://feedback.example.com/%70air",
            "https://evil.example/pair",
            "http://feedback.example.com/pair",
            "https://feedback.example.com/",
            "feedback://pair",
            "not a link at all",
            "",
        )
        for (link in links) {
            val text = visibleText(outcomeFor(deliver(link)))
            assertTrue("silently swallowed: \"$link\"", text.isNotBlank())
        }
    }

    /** The link the app was built for still does what it is for: it fills in the address field. */
    @Test
    fun `a confirmed link fills in the address field and says where it came from`() {
        val outcome = outcomeFor(deliver("https://feedback.example.com/pair"))

        assertTrue("expected the field to be filled, got $outcome",
            outcome is SetupLinkOutcome.FillsAddressField)
        val filled = outcome as SetupLinkOutcome.FillsAddressField
        assertEquals(build, filled.server.baseUrl)
        assertTrue("feedback.example.com" in filled.notice)
    }

    /** A refused address is named, and so is what the app trusts instead. */
    @Test
    fun `a foreign origin is refused and both sides are named`() {
        val announced = announcementFor(outcomeFor(deliver("https://evil.example/pair")))

        assertTrue(announced.rejected)
        assertTrue("evil.example" in announced.message)
        assertTrue("feedback.example.com" in announced.message)
    }

    /** The switch is an answer too. Turning setup links off must not look like a broken app. */
    @Test
    fun `a link that arrives while setup links are off is answered, not ignored quietly`() {
        val announced = announcementFor(
            outcomeFor(deliver("https://feedback.example.com/pair"), linksIgnored = true),
        )

        assertTrue(announced.rejected)
        assertTrue("abgeschaltet" in announced.message)
    }

    /** Nothing is changed on a paired device, and the owner is told exactly that. */
    @Test
    fun `a confirmed link on a paired device changes nothing`() {
        val announced = announcementFor(
            outcomeFor(
                deliver("https://feedback.example.com/pair"),
                registered = build,
                paired = true,
            ),
        )

        assertTrue(!announced.rejected)
        assertTrue("feedback.example.com" in announced.message)
    }

    /**
     * The message used to name the address from the LINK. Both anchors are trusted, so a link
     * carrying the build default is confirmed on a device paired with a different origin - and
     * the sentence then claimed that origin was the one it is paired with. It says which server
     * the device is actually registered with, or says nothing about it at all.
     */
    @Test
    fun `the paired message names the registered server, not the link`() {
        val announced = announcementFor(
            outcomeFor(
                deliver("https://feedback.example.com/pair"),
                registered = "https://anderer.example.com",
                paired = true,
            ),
        )

        assertTrue(!announced.rejected)
        assertTrue(announced.message, "anderer.example.com" in announced.message)
        assertTrue(announced.message, "feedback.example.com" !in announced.message)
    }

    /** An unreadable registration must not turn into a claim about some other server. */
    @Test
    fun `the paired message names no server when the registration cannot be read`() {
        val announced = announcementFor(
            outcomeFor(
                deliver("https://feedback.example.com/pair"),
                registered = "nicht einmal eine adresse",
                paired = true,
            ),
        )

        assertTrue(!announced.rejected)
        assertTrue(announced.message, "feedback.example.com" !in announced.message)
        assertTrue(announced.message, "bereits" in announced.message)
    }

    /** A running pairing owns the address it started with. */
    @Test
    fun `a confirmed link during a running pairing does not overwrite the field`() {
        val announced = announcementFor(
            outcomeFor(deliver("https://feedback.example.com/pair"), pairingInProgress = true),
        )

        assertTrue(announced.rejected)
        assertTrue("Kopplung" in announced.message)
    }
}
