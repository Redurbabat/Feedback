package com.redurbabat.feedback.security

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The same file the server reads: `protocol/fixtures/server-identity-v1.json`.
 *
 * Every signature in it was produced by the real server module, not by this test. That is the
 * point - the two sides agree on a canonical payload, a curve, a digest and a signature encoding,
 * and none of that is checked by either side testing against itself. The rejected cases carry
 * more weight than the accepted one, in particular `key_changed`: an impostor signing its own key
 * with its own key is flawless cryptography and must still be refused.
 */
class ServerIdentityFixtureTest {

    private val fixtures: JSONObject by lazy { JSONObject(fixtureFile().readText()) }

    private fun fixtureFile(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "protocol/fixtures/server-identity-v1.json")
            if (candidate.isFile) {
                return candidate
            }
            directory = directory.parentFile
        }
        throw AssertionError("protocol/fixtures/server-identity-v1.json nicht gefunden")
    }

    private fun checkCase(case: JSONObject): ServerIdentityCheck {
        val challenge = fixtures.getJSONObject("challenge")
        return ServerIdentity.check(
            pinnedPublicKeyBase64 = case.getString("pinnedPublicKey"),
            deviceId = challenge.getString("deviceId"),
            nonceBase64Url = challenge.getString("nonce"),
            offeredPublicKeyBase64 = case.getString("offeredPublicKey"),
            issuedAtEpochMillis = challenge.getLong("issuedAt"),
            signatureBase64Url = case.getString("signature"),
        )
    }

    /** Both sides build the payload from the same five lines, or nothing else here holds. */
    @Test
    fun `the canonical payload matches the one the server signed`() {
        val challenge = fixtures.getJSONObject("challenge")
        val accepted = fixtures.getJSONObject("accepted")

        assertEquals(
            fixtures.getString("canonicalPayload"),
            ServerIdentity.payload(
                deviceId = challenge.getString("deviceId"),
                nonceBase64Url = challenge.getString("nonce"),
                publicKeyBase64 = accepted.getString("offeredPublicKey"),
                issuedAtEpochMillis = challenge.getLong("issuedAt"),
            ),
        )
    }

    @Test
    fun `a signature made by the server verifies here`() {
        assertEquals(
            ServerIdentityCheck.Trusted,
            checkCase(fixtures.getJSONObject("accepted")),
        )
    }

    @Test
    fun `every rejected case is rejected for the reason the fixture names`() {
        val rejected = fixtures.getJSONArray("rejected")
        // A fixture file that lost its rejected cases would let this test pass while checking
        // nothing at all.
        assertEquals(2, rejected.length())

        for (index in 0 until rejected.length()) {
            val case = rejected.getJSONObject(index)
            val expected = when (val reason = case.getString("reason")) {
                "key_changed" -> ServerIdentityCheck.KeyChanged
                "bad_signature" -> ServerIdentityCheck.BadSignature
                else -> throw AssertionError("unbekannter Ablehnungsgrund: $reason")
            }
            assertEquals(case.getString("description"), expected, checkCase(case))
        }
    }
}
