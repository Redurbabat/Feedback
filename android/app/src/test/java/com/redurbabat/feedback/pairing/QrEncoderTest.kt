package com.redurbabat.feedback.pairing

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrEncoderTest {

    private val ticket = "iNQmb9TmM40TuEX88olXnSCciXgjuSF9o-Fhk28DFYk"
    private val payload = PairingQrPayload.build(ticket)

    @Test
    fun `an encoded pairing payload decodes back to exactly the same payload`() {
        val matrix = QrEncoder.encodeOrNull(payload)
        assertNotNull(matrix)
        assertEquals(payload, decode(matrix!!))
    }

    @Test
    fun `the decoded payload yields the original ticket`() {
        val matrix = QrEncoder.encodeOrNull(payload)!!
        assertEquals(ticket, PairingQrPayload.parseTicketOrNull(decode(matrix)))
    }

    @Test
    fun `the matrix is square and carries the required quiet zone`() {
        val matrix = QrEncoder.encodeOrNull(payload)!!
        assertTrue("matrix must not be empty", matrix.size > 0)

        val quietZone = QrMatrix.QUIET_ZONE_MODULES
        for (offset in 0 until quietZone) {
            for (index in 0 until matrix.size) {
                assertTrue(
                    "top quiet zone row $offset must be light",
                    !matrix.isDark(index, offset),
                )
                assertTrue(
                    "left quiet zone column $offset must be light",
                    !matrix.isDark(offset, index),
                )
                assertTrue(
                    "bottom quiet zone must be light",
                    !matrix.isDark(index, matrix.size - 1 - offset),
                )
                assertTrue(
                    "right quiet zone must be light",
                    !matrix.isDark(matrix.size - 1 - offset, index),
                )
            }
        }
    }

    @Test
    fun `the matrix size matches a real QR version plus the quiet zone`() {
        val matrix = QrEncoder.encodeOrNull(payload)!!
        val symbolSize = matrix.size - 2 * QrMatrix.QUIET_ZONE_MODULES
        // QR versions are 21, 25, 29 ... modules across.
        assertTrue("unexpected symbol size $symbolSize", symbolSize >= 21)
        assertEquals(0, (symbolSize - 21) % 4)
    }

    @Test
    fun `empty content does not produce a code`() {
        assertNull(QrEncoder.encodeOrNull(""))
    }

    private fun decode(matrix: QrMatrix): String {
        val source = MatrixLuminanceSource(matrix)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val result = QRCodeReader().decode(
            bitmap,
            mapOf(DecodeHintType.PURE_BARCODE to true),
        )
        return result.text
    }

    /** Minimal grayscale view over a [QrMatrix] so the real ZXing reader can be used. */
    private class MatrixLuminanceSource(
        private val matrix: QrMatrix,
    ) : LuminanceSource(matrix.size, matrix.size) {

        private val luminances = ByteArray(matrix.size * matrix.size) { index ->
            val x = index % matrix.size
            val y = index / matrix.size
            if (matrix.isDark(x, y)) 0x00.toByte() else 0xff.toByte()
        }

        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val target = if (row != null && row.size >= width) row else ByteArray(width)
            System.arraycopy(luminances, y * width, target, 0, width)
            return target
        }

        override fun getMatrix(): ByteArray = luminances
    }
}
