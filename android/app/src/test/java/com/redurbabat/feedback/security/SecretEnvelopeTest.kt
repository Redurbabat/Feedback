package com.redurbabat.feedback.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretEnvelopeTest {

    private fun iv(): ByteArray = ByteArray(SecretEnvelope.IV_LENGTH_BYTES) { it.toByte() }

    private fun ciphertext(): ByteArray = ByteArray(32) { (0xff - it).toByte() }

    @Test
    fun encodeProducesVersionedThreeSegmentForm() {
        val encoded = SecretEnvelope(iv(), ciphertext()).encode()
        val parts = encoded.split(":")
        assertEquals(3, parts.size)
        assertEquals(SecretEnvelope.VERSION_PREFIX, parts[0])
        assertEquals(16, parts[1].length)
        assertTrue(parts[1].indexOf('=') < 0)
        assertTrue(parts[2].indexOf('=') < 0)
    }

    @Test
    fun roundTripsIvAndCiphertext() {
        val original = SecretEnvelope(iv(), ciphertext())
        val decoded = SecretEnvelope.decode(original.encode())
        assertArrayEquals(original.iv, decoded.iv)
        assertArrayEquals(original.ciphertext, decoded.ciphertext)
        assertEquals(original, decoded)
        assertEquals(original.hashCode(), decoded.hashCode())
    }

    @Test
    fun toStringNeverLeaksMaterial() {
        val text = SecretEnvelope(iv(), ciphertext()).toString()
        assertEquals("SecretEnvelope(version=v1)", text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongIvLength() {
        SecretEnvelope(ByteArray(8), ciphertext())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyCiphertext() {
        SecretEnvelope(iv(), ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsMissingSegment() {
        SecretEnvelope.decode("v1:" + CryptoUtils.Base64Url.encode(iv()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsUnknownVersion() {
        SecretEnvelope.decode(
            "v2:" + CryptoUtils.Base64Url.encode(iv()) + ":" +
                CryptoUtils.Base64Url.encode(ciphertext()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsNonBase64UrlSegment() {
        SecretEnvelope.decode("v1:++++++++++++++++:" + CryptoUtils.Base64Url.encode(ciphertext()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsTruncatedIv() {
        SecretEnvelope.decode(
            "v1:" + CryptoUtils.Base64Url.encode(ByteArray(8)) + ":" +
                CryptoUtils.Base64Url.encode(ciphertext()),
        )
    }
}
