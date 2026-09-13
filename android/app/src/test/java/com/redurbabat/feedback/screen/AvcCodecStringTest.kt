package com.redurbabat.feedback.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvcCodecStringTest {

    private fun sps(profile: Int, constraints: Int, level: Int, fourByteStartCode: Boolean): ByteArray {
        val start = if (fourByteStartCode) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1)
        return start + byteArrayOf(
            0x67.toByte(),
            profile.toByte(),
            constraints.toByte(),
            level.toByte(),
            0x00,
        )
    }

    @Test
    fun `reads baseline level 3_0 out of a four byte start code`() {
        assertEquals("avc1.42E01E", AvcCodecString.fromAnnexB(sps(0x42, 0xE0, 0x1E, true)))
    }

    @Test
    fun `reads a three byte start code too`() {
        assertEquals("avc1.4D4028", AvcCodecString.fromAnnexB(sps(0x4D, 0x40, 0x28, false)))
    }

    @Test
    fun `skips a leading non SPS unit`() {
        val aud = byteArrayOf(0, 0, 0, 1, 0x09, 0x10)
        assertEquals("avc1.64002A", AvcCodecString.fromAnnexB(aud + sps(0x64, 0x00, 0x2A, true)))
    }

    @Test
    fun `finds the SPS when a PPS follows it`() {
        val pps = byteArrayOf(0, 0, 0, 1, 0x68.toByte(), 0xCE.toByte(), 0x3C.toByte(), 0x80.toByte())
        assertEquals("avc1.42C01F", AvcCodecString.fromAnnexB(sps(0x42, 0xC0, 0x1F, true) + pps))
    }

    @Test
    fun `refuses to guess when there is no SPS`() {
        assertNull(AvcCodecString.fromAnnexB(ByteArray(0)))
        assertNull(AvcCodecString.fromAnnexB(byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(AvcCodecString.fromAnnexB(byteArrayOf(0, 0, 0, 1, 0x68.toByte(), 1, 2, 3)))
    }

    @Test
    fun `does not read past the end of a truncated unit`() {
        assertNull(AvcCodecString.fromAnnexB(byteArrayOf(0, 0, 0, 1, 0x67, 0x42)))
    }
}

class ScreenSessionPresentationTest {

    @Test
    fun `the consent text says that everything on the display is included`() {
        val body = ScreenSessionPresentation.consentBody()
        // The whole point of our own question: Android's dialog says *that* it records, not what
        // is in the picture and not who asked.
        assertEquals(true, body.contains("andere Apps"))
        assertEquals(true, body.contains("Benachrichtigungen"))
        assertEquals(true, body.contains("kein Ton"))
        assertEquals(true, body.contains("Android"))
    }

    @Test
    fun `the notification offers a stop`() {
        assertEquals("Stoppen", ScreenSessionPresentation.notificationStopAction())
        assertEquals(true, ScreenSessionPresentation.notificationBody().contains("Stoppen"))
    }

    @Test
    fun `the status line distinguishes running from idle`() {
        assertEquals("Bildschirmuebertragung laeuft", ScreenSessionPresentation.statusLine(true))
        assertEquals("Keine Bildschirmuebertragung", ScreenSessionPresentation.statusLine(false))
    }
}
