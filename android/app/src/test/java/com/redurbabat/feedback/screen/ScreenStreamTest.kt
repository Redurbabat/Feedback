package com.redurbabat.feedback.screen

import com.redurbabat.feedback.protocol.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSendWindowTest {

    @Test
    fun `admits frames until the window is full`() {
        val window = ScreenSendWindow(windowSize = 2)
        assertTrue(window.offer() is FrameAdmission.Send)
        assertTrue(window.offer() is FrameAdmission.Send)
        assertTrue(window.offer() is FrameAdmission.Drop)
        assertEquals(2, window.inFlight)
    }

    @Test
    fun `a dropped frame still spends its sequence number`() {
        val window = ScreenSendWindow(windowSize = 1)
        assertEquals(0L, (window.offer() as FrameAdmission.Send).sequence)
        assertEquals(1L, (window.offer() as FrameAdmission.Drop).sequence)
        // The gap is what tells the server something was lost, instead of hiding it.
        window.recordAck(0L)
        assertEquals(2L, (window.offer() as FrameAdmission.Send).sequence)
    }

    @Test
    fun `an ack frees the window`() {
        val window = ScreenSendWindow(windowSize = 2)
        window.offer()
        window.offer()
        assertFalse(window.canSend())
        assertTrue(window.recordAck(1L))
        assertTrue(window.canSend())
        assertEquals(0, window.inFlight)
    }

    @Test
    fun `acks are cumulative because the server also acknowledges what it dropped`() {
        val window = ScreenSendWindow(windowSize = 4)
        repeat(4) { window.offer() }
        assertTrue(window.recordAck(2L))
        assertEquals(1, window.inFlight)
    }

    @Test
    fun `refuses an ack for a frame that was never produced`() {
        val window = ScreenSendWindow(windowSize = 2)
        window.offer()
        assertFalse(window.recordAck(5L))
        assertFalse(window.recordAck(-1L))
    }

    @Test
    fun `refuses a stale ack`() {
        val window = ScreenSendWindow(windowSize = 3)
        repeat(3) { window.offer() }
        assertTrue(window.recordAck(1L))
        assertFalse(window.recordAck(0L))
    }
}

class ScreenFrameChunkerTest {

    @Test
    fun `an empty frame is still one chunk`() {
        val chunks = requireNotNull(ScreenFrameChunker.split(ByteArray(0)))
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].data.size)
        assertEquals(1, chunks[0].chunkCount)
    }

    @Test
    fun `a delta frame usually fits into one chunk`() {
        val chunks = requireNotNull(ScreenFrameChunker.split(ByteArray(4_096) { 7 }))
        assertEquals(1, chunks.size)
        assertEquals(4_096, chunks[0].data.size)
    }

    @Test
    fun `a keyframe is split and reassembles byte for byte`() {
        val size = ProtocolConstants.SCREEN_CHUNK_BYTES * 3 + 17
        val frame = ByteArray(size) { (it % 251).toByte() }
        val chunks = requireNotNull(ScreenFrameChunker.split(frame))

        assertEquals(4, chunks.size)
        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.chunkIndex)
            assertEquals(4, chunk.chunkCount)
            assertTrue(chunk.data.size <= ProtocolConstants.SCREEN_CHUNK_BYTES)
        }
        val rebuilt = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk.data }
        assertTrue(frame.contentEquals(rebuilt))
    }

    @Test
    fun `refuses a frame the protocol does not allow`() {
        assertNull(ScreenFrameChunker.split(ByteArray(ProtocolConstants.SCREEN_MAX_FRAME_BYTES + 1)))
    }
}

class ScreenEncoderPlanTest {

    private fun request(
        maxWidth: Int = ProtocolConstants.SCREEN_MAX_DIMENSION,
        maxHeight: Int = ProtocolConstants.SCREEN_MAX_DIMENSION,
        maxFps: Int = ProtocolConstants.SCREEN_MAX_FPS,
        maxBitrateKbps: Int = ProtocolConstants.SCREEN_MAX_BITRATE_KBPS,
    ) = ScreenStartRequest("stream", maxWidth, maxHeight, maxFps, maxBitrateKbps)

    @Test
    fun `scales a tall phone display down to the long edge limit`() {
        val settings = ScreenEncoderPlan.forDisplay(1440, 3120, request())
        assertTrue(settings.height <= ProtocolConstants.SCREEN_MAX_DIMENSION)
        assertTrue(settings.width <= ProtocolConstants.SCREEN_MAX_DIMENSION)
        // Aspect ratio kept within one alignment step.
        val ratio = settings.width.toDouble() / settings.height.toDouble()
        assertTrue(kotlin.math.abs(ratio - 1440.0 / 3120.0) < 0.02)
    }

    @Test
    fun `never upscales a small display`() {
        val settings = ScreenEncoderPlan.forDisplay(480, 800, request())
        assertTrue(settings.width <= 480)
        assertTrue(settings.height <= 800)
    }

    @Test
    fun `aligns both dimensions to sixteen`() {
        val settings = ScreenEncoderPlan.forDisplay(1081, 2001, request())
        assertEquals(0, settings.width % 16)
        assertEquals(0, settings.height % 16)
    }

    @Test
    fun `never falls below one macroblock`() {
        val settings = ScreenEncoderPlan.forDisplay(4, 4, request())
        assertTrue(settings.width >= 16)
        assertTrue(settings.height >= 16)
    }

    @Test
    fun `takes the smaller of the two limits in both directions`() {
        // The server asks for less than the protocol allows.
        val modest = ScreenEncoderPlan.forDisplay(1440, 3120, request(maxWidth = 320, maxHeight = 320))
        assertTrue(modest.width <= 320)
        assertTrue(modest.height <= 320)

        // The server asks for more than the protocol allows; the device does not oblige.
        val greedy = ScreenEncoderPlan.forDisplay(
            4096,
            4096,
            request(maxWidth = 4096, maxHeight = 4096, maxFps = 120, maxBitrateKbps = 50_000),
        )
        assertTrue(greedy.width <= ProtocolConstants.SCREEN_MAX_DIMENSION)
        assertTrue(greedy.height <= ProtocolConstants.SCREEN_MAX_DIMENSION)
        assertEquals(ProtocolConstants.SCREEN_MAX_FPS, greedy.fps)
        assertTrue(greedy.bitrateKbps <= ProtocolConstants.SCREEN_MAX_BITRATE_KBPS)
    }

    @Test
    fun `falls back to the protocol limits when the server sends nothing usable`() {
        val settings = ScreenEncoderPlan.forDisplay(
            1080,
            1920,
            ScreenStartRequest("stream", 0, 0, 0, 0),
        )
        assertEquals(ProtocolConstants.SCREEN_MAX_FPS, settings.fps)
        assertTrue(settings.width <= ProtocolConstants.SCREEN_MAX_DIMENSION)
        assertTrue(settings.bitrateKbps <= ProtocolConstants.SCREEN_MAX_BITRATE_KBPS)
        assertTrue(settings.bitrateKbps > 0)
    }
}

class ScreenFrameHeaderTest {

    private val header = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xE0.toByte(), 0x1E)

    @Test
    fun `prepends the configuration to a keyframe that lacks it`() {
        val frame = byteArrayOf(0, 0, 0, 1, 0x65, 0x11)
        val result = ScreenFrameHeader.withHeader(frame, header)
        assertEquals(header.size + frame.size, result.size)
        assertTrue((header + frame).contentEquals(result))
    }

    @Test
    fun `leaves a keyframe that already carries it untouched`() {
        val frame = header + byteArrayOf(0, 0, 0, 1, 0x65, 0x11)
        // The encoder prepends it itself from API 29 on; doing it twice would waste bytes on
        // every keyframe for the life of the stream.
        assertTrue(frame === ScreenFrameHeader.withHeader(frame, header))
    }

    @Test
    fun `does nothing without a configuration to add`() {
        val frame = byteArrayOf(1, 2, 3)
        assertTrue(frame === ScreenFrameHeader.withHeader(frame, ByteArray(0)))
    }

    @Test
    fun `treats a frame shorter than the header as lacking it`() {
        val frame = byteArrayOf(0, 0)
        val result = ScreenFrameHeader.withHeader(frame, header)
        assertEquals(header.size + frame.size, result.size)
    }
}
