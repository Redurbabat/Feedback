package com.redurbabat.feedback.security

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The check that decides whether the device may send its token at all.
 *
 * Its value is entirely in the cases that must NOT come back Trusted, so that is what most of
 * this file is: a verifier that is too agreeable is invisible until the day it matters.
 */
class ServerIdentityTest {

    private val deviceId = "fb-0123456789abcdef01234567"
    private val nonce = "bm9uY2UtZWlucw"
    private val issuedAt = 1_700_000_000_000L

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun publicKeyBase64(pair: KeyPair): String =
        CryptoUtils.Base64.encode(pair.public.encoded)

    private fun sign(pair: KeyPair, payload: String): String {
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(pair.private)
            update(payload.toByteArray(Charsets.UTF_8))
        }.sign()
        return CryptoUtils.Base64Url.encode(signature)
    }

    private fun proofFrom(
        signer: KeyPair,
        offeredKey: String,
        nonceUsed: String = nonce,
        deviceIdUsed: String = deviceId,
        issuedAtUsed: Long = issuedAt,
    ): String = sign(
        signer,
        ServerIdentity.payload(deviceIdUsed, nonceUsed, offeredKey, issuedAtUsed),
    )

    private fun check(
        pinned: String,
        offered: String,
        signature: String,
        nonceUsed: String = nonce,
        deviceIdUsed: String = deviceId,
        issuedAtUsed: Long = issuedAt,
    ) = ServerIdentity.check(
        pinnedPublicKeyBase64 = pinned,
        deviceId = deviceIdUsed,
        nonceBase64Url = nonceUsed,
        offeredPublicKeyBase64 = offered,
        issuedAtEpochMillis = issuedAtUsed,
        signatureBase64Url = signature,
    )

    @Test
    fun `the server that was paired with is trusted`() {
        val server = keyPair()
        val key = publicKeyBase64(server)

        assertEquals(
            ServerIdentityCheck.Trusted,
            check(pinned = key, offered = key, signature = proofFrom(server, key)),
        )
    }

    /**
     * The case the whole mechanism exists for, and the one an obvious implementation gets wrong.
     * An impostor holds a perfectly good key pair and signs its own key with it - verifying the
     * signature against the key that CAME WITH the answer would say yes every time. The signature
     * is checked against the pinned key, and the offered key is compared before that.
     */
    @Test
    fun `a different server with a flawless signature of its own is refused`() {
        val paired = keyPair()
        val impostor = keyPair()
        val impostorKey = publicKeyBase64(impostor)

        assertEquals(
            ServerIdentityCheck.KeyChanged,
            check(
                pinned = publicKeyBase64(paired),
                offered = impostorKey,
                signature = proofFrom(impostor, impostorKey),
            ),
        )
    }

    /** Naming the right key is not enough; the signature has to be over this exact challenge. */
    @Test
    fun `naming the pinned key without being able to sign for it is refused`() {
        val paired = keyPair()
        val impostor = keyPair()
        val pairedKey = publicKeyBase64(paired)

        assertEquals(
            ServerIdentityCheck.BadSignature,
            check(pinned = pairedKey, offered = pairedKey, signature = proofFrom(impostor, pairedKey)),
        )
    }

    /** A proof collected once must not be reusable for the next question. */
    @Test
    fun `a signature for another nonce, device or time does not carry over`() {
        val server = keyPair()
        val key = publicKeyBase64(server)
        val signature = proofFrom(server, key)

        assertEquals(
            ServerIdentityCheck.BadSignature,
            check(key, key, signature, nonceUsed = "bm9uY2UtendlaQ"),
        )
        assertEquals(
            ServerIdentityCheck.BadSignature,
            check(key, key, signature, deviceIdUsed = "fb-ffffffffffffffffffffffff"),
        )
        assertEquals(
            ServerIdentityCheck.BadSignature,
            check(key, key, signature, issuedAtUsed = issuedAt + 1),
        )
    }

    @Test
    fun `garbage is refused rather than interpreted`() {
        val server = keyPair()
        val key = publicKeyBase64(server)
        val signature = proofFrom(server, key)

        assertEquals(ServerIdentityCheck.Malformed, check("", "", signature))
        assertEquals(ServerIdentityCheck.Malformed, check(key, key, ""))
        assertEquals(ServerIdentityCheck.Malformed, check(key, key, "!!! kein base64url !!!"))
        assertEquals(ServerIdentityCheck.Malformed, check("kein schluessel", "kein schluessel", signature))
        // An empty offered key is a broken answer, not "a different server" - the difference
        // matters, because KeyChanged is the one the owner is told about in those words.
        assertEquals(ServerIdentityCheck.Malformed, check(key, "", signature))
    }

    /** One scheme in this protocol: the payload is the server's, line for line. */
    @Test
    fun `the payload is exactly the five lines the server signs`() {
        assertEquals(
            "feedback-server-identity-v1\n$deviceId\n$nonce\nAAAA\n$issuedAt",
            ServerIdentity.payload(deviceId, nonce, "AAAA", issuedAt),
        )
    }
}
