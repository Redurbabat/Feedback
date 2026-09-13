package com.redurbabat.feedback.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingQrPayloadTest {

    private val ticket = "iNQmb9TmM40TuEX88olXnSCciXgjuSF9o-Fhk28DFYk"

    @Test
    fun `builds exactly the payload the protocol documents`() {
        assertEquals(
            "feedback://pair?v=1&ticket=$ticket",
            PairingQrPayload.build(ticket),
        )
    }

    @Test
    fun `round trips a ticket`() {
        assertEquals(ticket, PairingQrPayload.parseTicketOrNull(PairingQrPayload.build(ticket)))
    }

    @Test
    fun `never carries the six digit display code`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingQrPayload.build("493821")
        }
        assertNull(PairingQrPayload.parseTicketOrNull("feedback://pair?v=1&ticket=493821"))
    }

    @Test
    fun `rejects a ticket that is too short to be high entropy`() {
        val short = "a".repeat(PairingQrPayload.MIN_TICKET_LENGTH - 1)
        assertThrows(IllegalArgumentException::class.java) { PairingQrPayload.build(short) }
        assertNull(PairingQrPayload.parseTicketOrNull("feedback://pair?v=1&ticket=$short"))

        val long = "a".repeat(PairingQrPayload.MAX_TICKET_LENGTH + 1)
        assertNull(PairingQrPayload.parseTicketOrNull("feedback://pair?v=1&ticket=$long"))
    }

    @Test
    fun `rejects anything that is not exactly a version one payload`() {
        val rejected = listOf(
            "",
            ticket,
            "feedback://pair?ticket=$ticket",
            "feedback://pair?v=2&ticket=$ticket",
            "feedback://pair?v=1&Ticket=$ticket",
            "feedback://pair?ticket=$ticket&v=1",
            "feedback://pairing?v=1&ticket=$ticket",
            "https://pair?v=1&ticket=$ticket",
            " feedback://pair?v=1&ticket=$ticket",
        )
        for (payload in rejected) {
            assertNull("must reject: $payload", PairingQrPayload.parseTicketOrNull(payload))
        }
    }

    @Test
    fun `rejects trailing parameters that could smuggle extra data`() {
        assertNull(
            PairingQrPayload.parseTicketOrNull(
                "feedback://pair?v=1&ticket=$ticket&deviceSecret=abc",
            ),
        )
    }

    @Test
    fun `rejects a ticket with characters outside base64url`() {
        val padded = ticket.dropLast(1) + "="
        assertNull(PairingQrPayload.parseTicketOrNull("feedback://pair?v=1&ticket=$padded"))
        val slashed = ticket.dropLast(1) + "/"
        assertNull(PairingQrPayload.parseTicketOrNull("feedback://pair?v=1&ticket=$slashed"))
    }
}
