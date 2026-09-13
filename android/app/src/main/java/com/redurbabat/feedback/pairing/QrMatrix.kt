package com.redurbabat.feedback.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A square grid of QR modules. `true` is a dark module.
 *
 * Deliberately free of `android.*` and of Compose, so the encoding itself is unit testable and the
 * drawing code stays a thin renderer.
 */
class QrMatrix(
    val size: Int,
    private val modules: BooleanArray,
) {
    init {
        require(size > 0) { "QR size must be positive" }
        require(modules.size == size * size) { "QR module count must match the size" }
    }

    fun isDark(x: Int, y: Int): Boolean = modules[y * size + x]

    companion object {
        /**
         * Quiet zone in modules. The QR specification requires four; without it many scanners
         * refuse the code.
         */
        const val QUIET_ZONE_MODULES = 4
    }
}

/** Encodes pairing payloads as QR matrices. */
object QrEncoder {

    /**
     * Returns null when the content cannot be encoded, so the caller can fall back to the
     * six-digit code instead of crashing the pairing screen.
     */
    fun encodeOrNull(content: String): QrMatrix? {
        if (content.isEmpty()) {
            return null
        }
        val hints = mapOf(
            // The payload is ASCII; pinning the charset keeps the encoding identical everywhere.
            EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
            // A pairing code is read once, close up, from a bright screen. Level M is the
            // balanced choice and keeps the matrix small enough to stay crisp on a phone.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QrMatrix.QUIET_ZONE_MODULES,
        )

        val bitMatrix = try {
            // Width and height of 0 ask for the natural module size; scaling happens at draw time.
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        } catch (_: WriterException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }

        val size = bitMatrix.width
        if (size <= 0 || bitMatrix.height != size) {
            return null
        }
        val modules = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                modules[y * size + x] = bitMatrix.get(x, y)
            }
        }
        return QrMatrix(size, modules)
    }
}
