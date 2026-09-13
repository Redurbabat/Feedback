package com.redurbabat.feedback.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixed EC P-256 SPKI test vector. The expected values are the derivations required by
 * protocol/PROTOCOL.md section 3 and must stay byte for byte compatible with the server.
 */
class DeviceIdentityTest {

    @Test
    fun testVectorIsAP256SpkiOfNinetyOneBytes() {
        assertEquals(91, SPKI.size)
    }

    @Test
    fun fingerprintHexIsSha256OfTheSpki() {
        assertEquals(EXPECTED_SHA256_HEX, DeviceIdentity.fingerprintHex(SPKI))
    }

    @Test
    fun deviceIdUsesPrefixAndFirstTwentyFourHexCharacters() {
        val deviceId = DeviceIdentity.deriveDeviceId(SPKI)
        assertEquals("fb-4fd658a33b3352ac15163073", deviceId)
        assertEquals(27, deviceId.length)
        assertTrue(deviceId.startsWith(DeviceIdentity.DEVICE_ID_PREFIX))
        assertEquals(
            EXPECTED_SHA256_HEX.substring(0, DeviceIdentity.DEVICE_ID_HEX_LENGTH),
            deviceId.removePrefix(DeviceIdentity.DEVICE_ID_PREFIX),
        )
    }

    @Test
    fun fingerprintIsGroupedInSixteenBlocksOfFour() {
        val fingerprint = DeviceIdentity.formatFingerprint(SPKI)
        assertEquals(
            "4fd6:58a3:3b33:52ac:1516:3073:5cb1:bf82:" +
                "4e92:3fb0:408c:fcb4:5662:fbc8:908a:f8e1",
            fingerprint,
        )
        assertEquals(16, fingerprint.split(":").size)
        assertEquals(EXPECTED_SHA256_HEX, fingerprint.replace(":", ""))
    }

    @Test
    fun fromSpkiEncodesThePublicKeyAsStandardBase64() {
        val identity = DeviceIdentity.fromSpki(SPKI)
        assertEquals(SPKI_BASE64, identity.publicKeyBase64)
        assertEquals(DeviceIdentity.deriveDeviceId(SPKI), identity.deviceId)
        assertEquals(DeviceIdentity.formatFingerprint(SPKI), identity.fingerprint)
    }

    @Test
    fun derivationChangesWhenASingleByteChanges() {
        val modified = SPKI.copyOf()
        modified[modified.size - 1] = (modified[modified.size - 1] + 1).toByte()
        assertTrue(DeviceIdentity.deriveDeviceId(SPKI) != DeviceIdentity.deriveDeviceId(modified))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyPublicKeyIsRejectedForDeviceId() {
        DeviceIdentity.deriveDeviceId(ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyPublicKeyIsRejectedForFingerprint() {
        DeviceIdentity.formatFingerprint(ByteArray(0))
    }

    companion object {
        const val SPKI_BASE64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEqjL3js7KE5tBRrEreepAStUT" +
            "HMGjHDEQfesCVvr7lMI0bRO2/FHkO31F+gI+KZf8IIEv0cX5RosjeK+NYh6sVQ=="

        const val EXPECTED_SHA256_HEX =
            "4fd658a33b3352ac151630735cb1bf824e923fb0408cfcb45662fbc8908af8e1"

        val SPKI: ByteArray = CryptoUtils.Base64.decode(SPKI_BASE64)
    }
}
