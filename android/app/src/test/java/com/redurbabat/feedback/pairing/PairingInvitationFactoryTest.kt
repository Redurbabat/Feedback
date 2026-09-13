package com.redurbabat.feedback.pairing

import com.redurbabat.feedback.protocol.ProtocolConstants
import com.redurbabat.feedback.security.CryptoUtils
import com.redurbabat.feedback.security.DeviceIdentity
import com.redurbabat.feedback.security.DeviceIdentityStore
import com.redurbabat.feedback.testutil.JvmDeviceKeyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PairingInvitationFactoryTest {

    private val keyManager = JvmDeviceKeyManager()
    private val identityStore = DeviceIdentityStore(keyManager)
    private val identity: DeviceIdentity = identityStore.loadOrCreate()

    private fun factory(
        codeValue: Int = 7,
        nonceFill: Byte = 0x41,
        now: Long = FIXED_NOW,
    ): PairingInvitationFactory = PairingInvitationFactory(
        identityStore = identityStore,
        secureRandom = FixedRandom(codeValue, nonceFill),
        clock = FixedClock(now),
    )

    @Test
    fun codeIsAlwaysSixDigitsAndZeroPadded() {
        assertEquals("000007", factory(codeValue = 7).create(identity).code)
        assertEquals("000000", factory(codeValue = 0).create(identity).code)
        assertEquals("999999", factory(codeValue = 999_999).create(identity).code)
        assertEquals("493821", factory(codeValue = 493_821).create(identity).code)
    }

    @Test
    fun codeIsPurelyNumeric() {
        val code = factory(codeValue = 42).create(identity).code
        assertEquals(ProtocolConstants.DISPLAY_CODE_DIGITS, code.length)
        for (character in code) {
            assertTrue(character in '0'..'9')
        }
    }

    @Test
    fun nonceHasTheProtocolLengthAndIsBase64UrlWithoutPadding() {
        val invitation = factory().create(identity)
        assertEquals(24, invitation.nonceBase64Url.length)
        assertTrue(invitation.nonceBase64Url.indexOf('=') < 0)
        assertEquals(
            ProtocolConstants.PAIRING_NONCE_BYTES,
            CryptoUtils.Base64Url.decode(invitation.nonceBase64Url).size,
        )
    }

    @Test
    fun expiryFollowsTheProtocolTtlAndUsesTheInjectedClock() {
        val invitation = factory(now = FIXED_NOW).create(identity)
        assertEquals(FIXED_NOW, invitation.issuedAtEpochMillis)
        assertEquals(FIXED_NOW + ProtocolConstants.PAIRING_TTL_MS, invitation.expiresAtEpochMillis)
        assertEquals(300_000L, PairingInvitationFactory.PAIRING_TTL_MILLIS)
    }

    @Test
    fun invitationExpiresExactlyAtTheTtlBoundary() {
        val invitation = factory(now = FIXED_NOW).create(identity)
        assertFalse(invitation.isExpired(FIXED_NOW))
        assertFalse(invitation.isExpired(invitation.expiresAtEpochMillis - 1))
        assertTrue(invitation.isExpired(invitation.expiresAtEpochMillis))
        assertTrue(invitation.isExpired(invitation.expiresAtEpochMillis + 1))
    }

    @Test
    fun explicitTimestampOverridesTheClock() {
        val invitation = factory(now = FIXED_NOW).create(identity, nowEpochMillis = 1_000L)
        assertEquals(1_000L, invitation.issuedAtEpochMillis)
        assertEquals(1_000L + ProtocolConstants.PAIRING_TTL_MS, invitation.expiresAtEpochMillis)
    }

    @Test
    fun payloadIsTheCanonicalLocalProof() {
        val invitation = factory().create(identity)
        val payload = String(
            CryptoUtils.Base64Url.decode(invitation.payloadBase64Url),
            Charsets.UTF_8,
        )
        val expected = PairingCanonicalPayload.local(
            deviceId = identity.deviceId,
            publicKeyBase64 = identity.publicKeyBase64,
            nonceBase64Url = invitation.nonceBase64Url,
            issuedAtEpochMillis = invitation.issuedAtEpochMillis,
            expiresAtEpochMillis = invitation.expiresAtEpochMillis,
            sixDigitCode = invitation.code,
        )
        assertEquals(expected, payload)
        assertTrue(payload.startsWith("feedback-pairing-v1\n"))
        assertFalse(payload.endsWith("\n"))
    }

    @Test
    fun signatureVerifiesAgainstTheDeviceKey() {
        val invitation = factory().create(identity)
        val payload = CryptoUtils.Base64Url.decode(invitation.payloadBase64Url)
        val signature = CryptoUtils.Base64Url.decode(invitation.signatureBase64Url)
        assertTrue(keyManager.verify(payload, signature))
    }

    @Test
    fun signatureDoesNotVerifyForATamperedPayload() {
        val invitation = factory().create(identity)
        val payload = CryptoUtils.Base64Url.decode(invitation.payloadBase64Url)
        val signature = CryptoUtils.Base64Url.decode(invitation.signatureBase64Url)
        payload[payload.size - 1] = (payload[payload.size - 1] + 1).toByte()
        assertFalse(keyManager.verify(payload, signature))
    }

    @Test
    fun identityMatchesTheProtocolDerivation() {
        val spki = keyManager.publicKeySpki()
        assertEquals(DeviceIdentity.deriveDeviceId(spki), identity.deviceId)
        assertEquals(DeviceIdentity.formatFingerprint(spki), identity.fingerprint)
        assertEquals(CryptoUtils.Base64.encode(spki), identity.publicKeyBase64)
    }

    private class FixedClock(private val now: Long) : Clock {
        override fun nowEpochMillis(): Long = now
    }

    private class FixedRandom(
        private val intValue: Int,
        private val fill: Byte,
    ) : SecureRandom() {
        override fun nextInt(bound: Int): Int = intValue % bound

        override fun nextBytes(bytes: ByteArray) {
            bytes.fill(fill)
        }
    }

    companion object {
        private const val FIXED_NOW = 1_757_760_000_000L
    }
}
