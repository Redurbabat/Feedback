package com.redurbabat.feedback.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device's reading of `POST /agent/token` (THREAT_MODEL 4.13).
 *
 * Every case here decides whether a credential is kept, replaced or thrown away, and the two
 * expensive mistakes are symmetrical: storing something that is not a token leaves the device
 * unable to connect at all, and treating a finished pairing as a hiccup leaves it knocking
 * forever.
 */
class TokenRotationTest {

    private val token = "a".repeat(43)

    @Test
    fun `not due leaves everything as it is`() {
        val outcome = TokenRotation.parse(200, """{"version":1,"rotated":false}""")

        assertEquals(TokenRotationOutcome.NotDue, outcome)
    }

    @Test
    fun `a rotation carries the new token through`() {
        val outcome = TokenRotation.parse(
            200,
            """{"version":1,"rotated":true,"deviceToken":"$token","expiresAt":"2026-12-01T00:00:00Z"}""",
        )

        assertEquals(TokenRotationOutcome.Rotated(token), outcome)
    }

    /**
     * The one that would be silent. A missing, empty or malformed token stored as if it were one
     * replaces a working credential with a broken one - and the working one is gone by then.
     */
    @Test
    fun `nothing that is not a token is ever handed on for storage`() {
        val bodies = listOf(
            """{"rotated":true}""",
            """{"rotated":true,"deviceToken":""}""",
            """{"rotated":true,"deviceToken":null}""",
            """{"rotated":true,"deviceToken":"zu-kurz"}""",
            """{"rotated":true,"deviceToken":"enthaelt ein leerzeichen und ist lang genug aaaa"}""",
            """{"rotated":true,"deviceToken":"plus+und/slash+sind+kein+base64url+aber+lang+genug"}""",
            "nicht einmal json",
            "",
            null,
        )
        for (body in bodies) {
            val outcome = TokenRotation.parse(200, body)
            assertTrue(
                "would have stored a non-token from: $body",
                outcome !is TokenRotationOutcome.Rotated,
            )
        }
    }

    @Test
    fun `a revoked device ends the pairing`() {
        val outcome = TokenRotation.parse(
            403,
            """{"error":{"code":"DEVICE_REVOKED","message":"Geraet wurde widerrufen"}}""",
        )

        assertEquals(TokenRotationOutcome.Revoked, outcome)
    }

    /**
     * 403 is shared by several codes. Only one of them means the pairing is over; the rest are
     * the server refusing this single request, which must not cost a registration.
     */
    @Test
    fun `another 403 does not throw the registration away`() {
        val outcome = TokenRotation.parse(
            403,
            """{"error":{"code":"CAPABILITY_DENIED","message":"nicht freigegeben"}}""",
        )

        assertEquals(TokenRotationOutcome.Unreachable, outcome)
        assertEquals(TokenRotationOutcome.Unreachable, TokenRotation.parse(403, "kein json"))
    }

    /** Not revoked, just no longer accepted - so the app does not blame the owner for it. */
    @Test
    fun `a refused token is kept apart from a revoked device`() {
        assertEquals(
            TokenRotationOutcome.Rejected,
            TokenRotation.parse(401, """{"error":{"code":"UNAUTHORIZED","message":"weg"}}"""),
        )
    }

    /** Server trouble says nothing about the token, so the backoff keeps its job. */
    @Test
    fun `a server error or a rate limit is only unreachable`() {
        assertEquals(TokenRotationOutcome.Unreachable, TokenRotation.parse(500, "boom"))
        assertEquals(TokenRotationOutcome.Unreachable, TokenRotation.parse(429, """{"error":{}}"""))
        assertEquals(TokenRotationOutcome.Unreachable, TokenRotation.parse(502, "<html>"))
    }
}
