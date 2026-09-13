package com.redurbabat.feedback.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoUtilsTest {

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    @Test
    fun base64MatchesRfc4648TestVectors() {
        assertEquals("", CryptoUtils.Base64.encode(ByteArray(0)))
        assertEquals("Zg==", CryptoUtils.Base64.encode(ascii("f")))
        assertEquals("Zm8=", CryptoUtils.Base64.encode(ascii("fo")))
        assertEquals("Zm9v", CryptoUtils.Base64.encode(ascii("foo")))
        assertEquals("Zm9vYg==", CryptoUtils.Base64.encode(ascii("foob")))
        assertEquals("Zm9vYmE=", CryptoUtils.Base64.encode(ascii("fooba")))
        assertEquals("Zm9vYmFy", CryptoUtils.Base64.encode(ascii("foobar")))
    }

    @Test
    fun base64UrlMatchesRfc4648TestVectorsWithoutPadding() {
        assertEquals("", CryptoUtils.Base64Url.encode(ByteArray(0)))
        assertEquals("Zg", CryptoUtils.Base64Url.encode(ascii("f")))
        assertEquals("Zm8", CryptoUtils.Base64Url.encode(ascii("fo")))
        assertEquals("Zm9v", CryptoUtils.Base64Url.encode(ascii("foo")))
        assertEquals("Zm9vYg", CryptoUtils.Base64Url.encode(ascii("foob")))
        assertEquals("Zm9vYmE", CryptoUtils.Base64Url.encode(ascii("fooba")))
        assertEquals("Zm9vYmFy", CryptoUtils.Base64Url.encode(ascii("foobar")))
    }

    @Test
    fun alphabetsDifferOnIndexSixtyTwoAndSixtyThree() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xfe.toByte())
        assertEquals("+//+", CryptoUtils.Base64.encode(bytes))
        assertEquals("-__-", CryptoUtils.Base64Url.encode(bytes))
        assertArrayEquals(bytes, CryptoUtils.Base64.decode("+//+"))
        assertArrayEquals(bytes, CryptoUtils.Base64Url.decode("-__-"))
    }

    @Test
    fun base64RoundTripsEveryLengthUpToSixtyFour() {
        for (length in 0..64) {
            val bytes = ByteArray(length) { index -> (index * 7 + 13).toByte() }
            assertArrayEquals(bytes, CryptoUtils.Base64.decode(CryptoUtils.Base64.encode(bytes)))
            assertArrayEquals(
                bytes,
                CryptoUtils.Base64Url.decode(CryptoUtils.Base64Url.encode(bytes)),
            )
        }
    }

    @Test
    fun base64RoundTripsAllByteValues() {
        val bytes = ByteArray(256) { index -> index.toByte() }
        assertArrayEquals(bytes, CryptoUtils.Base64.decode(CryptoUtils.Base64.encode(bytes)))
        assertArrayEquals(bytes, CryptoUtils.Base64Url.decode(CryptoUtils.Base64Url.encode(bytes)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun base64RejectsLengthThatIsNotAMultipleOfFour() {
        CryptoUtils.Base64.decode("Zg=")
    }

    @Test(expected = IllegalArgumentException::class)
    fun base64RejectsUrlAlphabet() {
        CryptoUtils.Base64.decode("-__-")
    }

    @Test(expected = IllegalArgumentException::class)
    fun base64RejectsEmbeddedWhitespace() {
        CryptoUtils.Base64.decode("Zm v")
    }

    @Test(expected = IllegalArgumentException::class)
    fun base64RejectsPaddingInTheMiddle() {
        CryptoUtils.Base64.decode("Zm=vYmFy")
    }

    @Test(expected = IllegalArgumentException::class)
    fun base64RejectsNonAsciiCharacters() {
        CryptoUtils.Base64.decode("Zm9ä")
    }

    @Test(expected = IllegalArgumentException::class)
    fun base64UrlRejectsPadding() {
        CryptoUtils.Base64Url.decode("Zg==")
    }

    @Test(expected = IllegalArgumentException::class)
    fun base64UrlRejectsStandardAlphabet() {
        CryptoUtils.Base64Url.decode("+//+")
    }

    @Test(expected = IllegalArgumentException::class)
    fun base64UrlRejectsDanglingSingleCharacter() {
        CryptoUtils.Base64Url.decode("Z")
    }

    @Test(expected = IllegalArgumentException::class)
    fun base64UrlRejectsNonZeroTrailingBits() {
        // "Zh" carries four trailing bits that are not zero, so it is not canonical.
        CryptoUtils.Base64Url.decode("Zh")
    }

    @Test
    fun base64UrlAcceptsCanonicalTrailingBits() {
        assertArrayEquals("f".toByteArray(Charsets.US_ASCII), CryptoUtils.Base64Url.decode("Zg"))
    }

    @Test
    fun toHexIsLowercaseAndZeroPadded() {
        assertEquals("", CryptoUtils.toHex(ByteArray(0)))
        assertEquals(
            "000f7fff",
            CryptoUtils.toHex(byteArrayOf(0x00, 0x0f, 0x7f, 0xff.toByte())),
        )
        assertEquals("80", CryptoUtils.toHex(byteArrayOf(0x80.toByte())))
    }

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            CryptoUtils.toHex(CryptoUtils.sha256(ByteArray(0))),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            CryptoUtils.toHex(CryptoUtils.sha256(ascii("abc"))),
        )
    }

    @Test
    fun constantTimeEqualsComparesContentAndLength() {
        assertTrue(CryptoUtils.constantTimeEquals(ascii("token"), ascii("token")))
        assertTrue(CryptoUtils.constantTimeEquals(ByteArray(0), ByteArray(0)))
        assertFalse(CryptoUtils.constantTimeEquals(ascii("token"), ascii("tokeo")))
        assertFalse(CryptoUtils.constantTimeEquals(ascii("token"), ascii("token ")))
        assertFalse(CryptoUtils.constantTimeEquals(byteArrayOf(0x00), byteArrayOf(0x80.toByte())))
    }
}
