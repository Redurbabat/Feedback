package com.redurbabat.feedback.files

import com.redurbabat.feedback.protocol.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferWindowTest {

    @Test
    fun `the window bounds how many chunks are in flight`() {
        val window = FileTransferWindow(windowSize = 4)
        for (expected in 0L until 4L) {
            assertTrue(window.canSend())
            assertEquals(expected, window.recordSent())
        }
        assertFalse("a fifth chunk must wait for an ack", window.canSend())
        assertEquals(4, window.inFlight)
    }

    @Test
    fun `an ack frees exactly the acknowledged prefix`() {
        val window = FileTransferWindow(windowSize = 2)
        window.recordSent()
        window.recordSent()
        assertFalse(window.canSend())

        assertTrue(window.recordAck(0L))
        assertTrue(window.canSend())
        assertEquals(1, window.inFlight)
        assertEquals(2L, window.recordSent())
        assertFalse(window.canSend())
    }

    @Test
    fun `a cumulative ack releases everything up to it`() {
        val window = FileTransferWindow(windowSize = 4)
        repeat(4) { window.recordSent() }
        assertTrue(window.recordAck(3L))
        assertEquals(0, window.inFlight)
        assertTrue(window.canSend())
    }

    @Test
    fun `an ack for something never sent is refused`() {
        val window = FileTransferWindow(windowSize = 4)
        assertFalse(window.recordAck(0L))
        window.recordSent()
        assertFalse(window.recordAck(1L))
        assertFalse(window.recordAck(-1L))
    }

    @Test
    fun `a repeated or stale ack is refused`() {
        val window = FileTransferWindow(windowSize = 4)
        repeat(3) { window.recordSent() }
        assertTrue(window.recordAck(1L))
        assertFalse("repeat", window.recordAck(1L))
        assertFalse("stale", window.recordAck(0L))
        assertTrue(window.recordAck(2L))
    }

    @Test
    fun `sending beyond the window is a programming error`() {
        val window = FileTransferWindow(windowSize = 1)
        window.recordSent()
        assertThrows(IllegalStateException::class.java) { window.recordSent() }
    }

    @Test
    fun `the default window matches the protocol`() {
        assertEquals(ProtocolConstants.FILE_TRANSFER_WINDOW, FileTransferWindow().windowSize)
        assertThrows(IllegalArgumentException::class.java) { FileTransferWindow(windowSize = 0) }
    }
}

class FileChunkSequenceTest {

    @Test
    fun `a gapless sequence is accepted up to last`() {
        val sequence = FileChunkSequence()
        assertTrue(sequence.accept(0L, last = false))
        assertTrue(sequence.accept(1L, last = false))
        assertTrue(sequence.accept(2L, last = true))
        assertTrue(sequence.isFinished)
    }

    @Test
    fun `a gap aborts the transfer`() {
        val sequence = FileChunkSequence()
        assertTrue(sequence.accept(0L, last = false))
        assertFalse(sequence.accept(2L, last = false))
    }

    @Test
    fun `a repeated chunk aborts the transfer`() {
        val sequence = FileChunkSequence()
        assertTrue(sequence.accept(0L, last = false))
        assertFalse(sequence.accept(0L, last = false))
    }

    @Test
    fun `a sequence that does not start at zero is refused`() {
        assertFalse(FileChunkSequence().accept(1L, last = false))
    }

    @Test
    fun `nothing is accepted after the last chunk`() {
        val sequence = FileChunkSequence()
        assertTrue(sequence.accept(0L, last = true))
        assertFalse(sequence.accept(1L, last = false))
        assertFalse(sequence.accept(1L, last = true))
    }

    @Test
    fun `a single chunk file is a valid transfer`() {
        val sequence = FileChunkSequence()
        assertEquals(0L, sequence.expectedSequence)
        assertTrue(sequence.accept(0L, last = true))
        assertEquals(1L, sequence.expectedSequence)
    }
}

class FileTransferPolicyTest {

    @Test
    fun `the smaller of the device and server ceiling wins`() {
        val deviceMax = ProtocolConstants.FILE_MAX_DOWNLOAD_BYTES
        assertEquals(deviceMax, FileTransferPolicy.effectiveMaxDownloadBytes(null))
        assertEquals(deviceMax, FileTransferPolicy.effectiveMaxDownloadBytes(0L))
        assertEquals(deviceMax, FileTransferPolicy.effectiveMaxDownloadBytes(deviceMax * 4))
        assertEquals(1_000L, FileTransferPolicy.effectiveMaxDownloadBytes(1_000L))
    }

    @Test
    fun `a file at the ceiling is downloadable and one byte more is not`() {
        assertTrue(FileTransferPolicy.isDownloadable(1_000L, serverMaxBytes = 1_000L))
        assertFalse(FileTransferPolicy.isDownloadable(1_001L, serverMaxBytes = 1_000L))
        assertTrue(
            FileTransferPolicy.isDownloadable(ProtocolConstants.FILE_MAX_DOWNLOAD_BYTES),
        )
        assertFalse(
            FileTransferPolicy.isDownloadable(ProtocolConstants.FILE_MAX_DOWNLOAD_BYTES + 1L),
        )
    }

    @Test
    fun `an unknown or negative size is never downloadable`() {
        assertFalse(FileTransferPolicy.isDownloadable(null))
        assertFalse(FileTransferPolicy.isDownloadable(-1L))
    }

    @Test
    fun `chunk counts round up and an empty file still sends one chunk`() {
        val chunk = ProtocolConstants.FILE_CHUNK_BYTES.toLong()
        assertEquals(1L, FileTransferPolicy.chunkCount(0L))
        assertEquals(1L, FileTransferPolicy.chunkCount(1L))
        assertEquals(1L, FileTransferPolicy.chunkCount(chunk))
        assertEquals(2L, FileTransferPolicy.chunkCount(chunk + 1L))
        assertEquals(3L, FileTransferPolicy.chunkCount(chunk * 3L))
        assertThrows(IllegalArgumentException::class.java) {
            FileTransferPolicy.chunkCount(-1L)
        }
    }

    @Test
    fun `a base64 chunk plus envelope stays under the frame limit`() {
        val rawBytes = ProtocolConstants.FILE_CHUNK_BYTES
        val base64Length = 4L * ((rawBytes + 2L) / 3L)
        assertTrue(
            "base64 chunk $base64Length must leave room for the envelope",
            base64Length < ProtocolConstants.MAX_FRAME_BYTES - 2_048L,
        )
    }

    @Test
    fun `cancel reasons map exactly to the protocol names`() {
        val expected = listOf(
            "client_cancelled",
            "session_expired",
            "capability_revoked",
            "device_revoked",
            "too_large",
            "read_error",
            "timeout",
        )
        assertEquals(expected, FileTransferCancelReason.entries.map { it.wireName })
        for (name in expected) {
            assertEquals(name, FileTransferCancelReason.fromWire(name)?.wireName)
        }
        assertNull(FileTransferCancelReason.fromWire("because"))
        assertNull(FileTransferCancelReason.fromWire(null))
    }
}
