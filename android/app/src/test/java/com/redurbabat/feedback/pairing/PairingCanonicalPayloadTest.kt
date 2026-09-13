package com.redurbabat.feedback.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCanonicalPayloadTest {

    @Test
    fun startProducesTheExactLineSequenceOfSectionFourOne() {
        val payload = start()
        assertEquals(
            listOf(
                "feedback-pairing-start-v1",
                DEVICE_ID,
                PUBLIC_KEY,
                FINGERPRINT,
                "Galaxy S24",
                "android",
                "16",
                "36",
                "0.2.0",
                NONCE,
                "1757760000000",
            ),
            payload.split("\n"),
        )
        assertFalse(payload.endsWith("\n"))
        assertEquals(11, payload.split("\n").size)
    }

    @Test
    fun claimProducesTheExactLineSequenceOfSectionFourTwo() {
        val payload = PairingCanonicalPayload.claim(
            pairingId = PAIRING_ID,
            deviceId = DEVICE_ID,
            deviceSecretBase64Url = DEVICE_SECRET,
            issuedAtEpochMillis = 1_757_760_100_000L,
        )
        assertEquals(
            listOf(
                "feedback-pairing-claim-v1",
                PAIRING_ID,
                DEVICE_ID,
                DEVICE_SECRET,
                "1757760100000",
            ),
            payload.split("\n"),
        )
        assertFalse(payload.endsWith("\n"))
    }

    @Test
    fun localProducesTheExactLineSequenceOfSectionFourThree() {
        val payload = PairingCanonicalPayload.local(
            deviceId = DEVICE_ID,
            publicKeyBase64 = PUBLIC_KEY,
            nonceBase64Url = NONCE,
            issuedAtEpochMillis = 1_757_760_000_000L,
            expiresAtEpochMillis = 1_757_760_300_000L,
            sixDigitCode = "049382",
        )
        assertEquals(
            listOf(
                "feedback-pairing-v1",
                DEVICE_ID,
                PUBLIC_KEY,
                NONCE,
                "1757760000000",
                "1757760300000",
                "049382",
            ),
            payload.split("\n"),
        )
    }

    @Test
    fun numbersAreFormattedWithoutGroupingOrLocaleDigits() {
        val payload = start(issuedAt = 1_000_000_000_000L)
        assertTrue(payload.split("\n")[10] == "1000000000000")
        assertTrue(payload.split("\n")[7] == "36")
    }

    @Test(expected = IllegalArgumentException::class)
    fun startRejectsALineBreakInDeviceName() {
        start(deviceName = "Galaxy\nS24")
    }

    @Test(expected = IllegalArgumentException::class)
    fun startRejectsALineBreakInTheFirstField() {
        start(deviceId = "fb-1234\nfb-5678")
    }

    @Test(expected = IllegalArgumentException::class)
    fun startRejectsALineBreakInTheNonce() {
        start(nonce = "abc\ndef")
    }

    @Test(expected = IllegalArgumentException::class)
    fun startRejectsAnEmptyField() {
        start(deviceName = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun startRejectsADeviceNameAboveTheProtocolLimit() {
        start(deviceName = "x".repeat(65))
    }

    @Test
    fun startAcceptsADeviceNameAtTheProtocolLimit() {
        val name = "x".repeat(64)
        assertEquals(name, start(deviceName = name).split("\n")[4])
    }

    @Test(expected = IllegalArgumentException::class)
    fun startRejectsAnOversizedPublicKey() {
        start(publicKey = "A".repeat(513))
    }

    @Test(expected = IllegalArgumentException::class)
    fun startRejectsANonPositiveSdkInt() {
        start(sdkInt = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun claimRejectsALineBreakInThePairingId() {
        PairingCanonicalPayload.claim(
            pairingId = "abc\ndef",
            deviceId = DEVICE_ID,
            deviceSecretBase64Url = DEVICE_SECRET,
            issuedAtEpochMillis = 1L,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun claimRejectsAnEmptyDeviceSecret() {
        PairingCanonicalPayload.claim(
            pairingId = PAIRING_ID,
            deviceId = DEVICE_ID,
            deviceSecretBase64Url = "",
            issuedAtEpochMillis = 1L,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun localRejectsACodeThatIsNotSixDigits() {
        PairingCanonicalPayload.local(
            deviceId = DEVICE_ID,
            publicKeyBase64 = PUBLIC_KEY,
            nonceBase64Url = NONCE,
            issuedAtEpochMillis = 1L,
            expiresAtEpochMillis = 2L,
            sixDigitCode = "12345",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun localRejectsANonNumericCode() {
        PairingCanonicalPayload.local(
            deviceId = DEVICE_ID,
            publicKeyBase64 = PUBLIC_KEY,
            nonceBase64Url = NONCE,
            issuedAtEpochMillis = 1L,
            expiresAtEpochMillis = 2L,
            sixDigitCode = "12345a",
        )
    }

    private fun start(
        deviceId: String = DEVICE_ID,
        publicKey: String = PUBLIC_KEY,
        fingerprint: String = FINGERPRINT,
        deviceName: String = "Galaxy S24",
        platform: String = "android",
        osVersion: String = "16",
        sdkInt: Int = 36,
        appVersion: String = "0.2.0",
        nonce: String = NONCE,
        issuedAt: Long = 1_757_760_000_000L,
    ): String = PairingCanonicalPayload.start(
        deviceId = deviceId,
        publicKeyBase64 = publicKey,
        fingerprint = fingerprint,
        deviceName = deviceName,
        platform = platform,
        osVersion = osVersion,
        sdkInt = sdkInt,
        appVersion = appVersion,
        nonceBase64Url = nonce,
        issuedAtEpochMillis = issuedAt,
    )

    companion object {
        private const val DEVICE_ID = "fb-4fd658a33b3352ac15163073"
        private const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE"
        private const val FINGERPRINT = "4fd6:58a3:3b33:52ac"
        private const val NONCE = "T-B3RJb0jT5Vd8KQnV3Qm1zx"
        private const val PAIRING_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        private const val DEVICE_SECRET = "Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyYQ"
    }
}
