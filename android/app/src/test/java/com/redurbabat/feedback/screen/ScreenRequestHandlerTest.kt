package com.redurbabat.feedback.screen

import com.redurbabat.feedback.protocol.MessageType
import com.redurbabat.feedback.protocol.ProtocolConstants
import com.redurbabat.feedback.protocol.ProtocolError
import com.redurbabat.feedback.security.CryptoUtils
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val STREAM = "9f1c0b7e-0000-4000-8000-000000000001"
private const val SESSION = "1b2c3d4e-0000-4000-8000-000000000002"

private class FakeCapture : ScreenCaptureSource {
    var listener: ScreenCaptureListener? = null
    var settings: ScreenEncoderSettings? = null
    var keyframeRequests = 0
    val stops = ArrayList<ScreenStopReason>()

    override fun requestCapture(
        streamId: String,
        settings: ScreenEncoderSettings,
        listener: ScreenCaptureListener,
    ) {
        this.listener = listener
        this.settings = settings
    }

    override fun requestKeyframe() {
        keyframeRequests += 1
    }

    override fun stop(reason: ScreenStopReason) {
        stops.add(reason)
    }

    /** Runs the whole consent flow the way the real source is required to. */
    fun grantAndStart(fps: Int = ProtocolConstants.SCREEN_MAX_FPS) {
        val target = listener ?: error("capture was never requested")
        target.onConsent(ScreenConsentState.PENDING)
        target.onConsent(ScreenConsentState.GRANTED)
        target.onStarted(
            ScreenStreamConfig(
                width = settings?.width ?: 720,
                height = settings?.height ?: 1280,
                codec = "avc1.42E01E",
                fps = fps,
                configBase64 = CryptoUtils.Base64.encode(byteArrayOf(0, 0, 0, 1, 0x67)),
            ),
        )
    }
}

private fun startPayload(streamId: String = STREAM): JSONObject = JSONObject()
    .put("streamId", streamId)
    .put("maxWidth", ProtocolConstants.SCREEN_MAX_DIMENSION)
    .put("maxHeight", ProtocolConstants.SCREEN_MAX_DIMENSION)
    .put("maxFps", ProtocolConstants.SCREEN_MAX_FPS)
    .put("maxBitrateKbps", ProtocolConstants.SCREEN_MAX_BITRATE_KBPS)

private fun frame(size: Int, keyFrame: Boolean, timestampUs: Long = 0L) =
    EncodedScreenFrame(ByteArray(size) { (it % 97).toByte() }, keyFrame, timestampUs)

class ScreenRequestHandlerTest {

    @Test
    fun `a start does not produce anything until the owner answers`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)

        assertEquals(ScreenOutcome.Accepted, handler.handleStart(startPayload(), SESSION, 1080, 1920))
        // Nothing is on the wire yet: our own question and Android's dialog both come first.
        assertTrue(handler.drainReadyMessages().isEmpty())
        assertTrue(capture.settings != null)
    }

    @Test
    fun `reports pending and granted before the first frame`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)
        handler.handleStart(startPayload(), SESSION, 1080, 1920)
        capture.grantAndStart()

        val messages = handler.drainReadyMessages()
        assertEquals(
            listOf(
                MessageType.SCREEN_CONSENT,
                MessageType.SCREEN_CONSENT,
                MessageType.SCREEN_STARTED,
            ),
            messages.map { it.type },
        )
        assertEquals("pending", messages[0].payload.getString("state"))
        assertEquals("granted", messages[1].payload.getString("state"))
        assertEquals("avc1.42E01E", messages[2].payload.getString("codec"))
    }

    @Test
    fun `a refusal ends the stream and frees the handler for a later attempt`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)
        handler.handleStart(startPayload(), SESSION, 1080, 1920)
        capture.listener?.onConsent(ScreenConsentState.DECLINED)

        val messages = handler.drainReadyMessages()
        assertEquals(listOf(MessageType.SCREEN_CONSENT), messages.map { it.type })
        assertEquals("declined", messages[0].payload.getString("state"))
        assertFalse(handler.isActive)

        // The owner may change their mind; a refusal must not block the next request.
        assertEquals(ScreenOutcome.Accepted, handler.handleStart(startPayload(), SESSION, 1080, 1920))
    }

    @Test
    fun `frames before screen started are not sent`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)
        handler.handleStart(startPayload(), SESSION, 1080, 1920)
        capture.listener?.onConsent(ScreenConsentState.GRANTED)
        capture.listener?.onFrame(frame(1_000, keyFrame = true))

        assertTrue(handler.drainReadyMessages().none { it.type == MessageType.SCREEN_FRAME })
    }

    @Test
    fun `splits one frame into chunks that carry the same sequence`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)
        handler.handleStart(startPayload(), SESSION, 1080, 1920)
        capture.grantAndStart()
        handler.drainReadyMessages()

        capture.listener?.onFrame(frame(ProtocolConstants.SCREEN_CHUNK_BYTES + 100, keyFrame = true, timestampUs = 42L))
        val chunks = handler.drainReadyMessages()
        assertEquals(2, chunks.size)
        chunks.forEachIndexed { index, message ->
            assertEquals(MessageType.SCREEN_FRAME, message.type)
            assertEquals(0L, message.payload.getLong("sequence"))
            assertEquals(index, message.payload.getInt("chunkIndex"))
            assertEquals(2, message.payload.getInt("chunkCount"))
            assertTrue(message.payload.getBoolean("keyFrame"))
            assertEquals(42L, message.payload.getLong("timestampUs"))
        }
        val rebuilt = chunks.fold(ByteArray(0)) { acc, message ->
            acc + CryptoUtils.Base64.decode(message.payload.getString("data"))
        }
        assertEquals(ProtocolConstants.SCREEN_CHUNK_BYTES + 100, rebuilt.size)
    }

    @Test
    fun `drops frames instead of queueing them once the window is full`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)
        handler.handleStart(startPayload(), SESSION, 1080, 1920)
        capture.grantAndStart()
        handler.drainReadyMessages()

        repeat(ProtocolConstants.SCREEN_FRAME_WINDOW + 3) { index ->
            capture.listener?.onFrame(frame(512, keyFrame = index == 0, timestampUs = index.toLong()))
        }

        val sequences = handler.drainReadyMessages()
            .filter { it.type == MessageType.SCREEN_FRAME }
            .map { it.payload.getLong("sequence") }
            .distinct()
        // A queue would keep every frame and make the picture steadily later; the window is what
        // keeps it current instead.
        assertEquals(ProtocolConstants.SCREEN_FRAME_WINDOW, sequences.size)
        assertTrue(capture.keyframeRequests >= 1)
    }

    @Test
    fun `an ack lets the next frame through`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)
        handler.handleStart(startPayload(), SESSION, 1080, 1920)
        capture.grantAndStart()
        handler.drainReadyMessages()

        repeat(ProtocolConstants.SCREEN_FRAME_WINDOW) { capture.listener?.onFrame(frame(256, it == 0)) }
        handler.drainReadyMessages()
        capture.listener?.onFrame(frame(256, false))
        assertTrue(handler.drainReadyMessages().isEmpty())

        val ack = JSONObject().put("streamId", STREAM).put("sequence", 1L)
        assertEquals(ScreenOutcome.Accepted, handler.handleAck(ack))
        capture.listener?.onFrame(frame(256, false))
        assertTrue(handler.drainReadyMessages().any { it.type == MessageType.SCREEN_FRAME })
    }

    @Test
    fun `an ack for a frame that never existed ends the stream`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)
        handler.handleStart(startPayload(), SESSION, 1080, 1920)
        capture.grantAndStart()

        val outcome = handler.handleAck(JSONObject().put("streamId", STREAM).put("sequence", 99L))
        assertTrue(outcome is ScreenOutcome.Failure)
        assertEquals(ProtocolError.INVALID_MESSAGE, (outcome as ScreenOutcome.Failure).error)
        assertEquals(listOf(ScreenStopReason.ENCODER_ERROR), capture.stops)
        assertFalse(handler.isActive)
    }

    @Test
    fun `refuses a second stream while one is running`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)
        handler.handleStart(startPayload(), SESSION, 1080, 1920)
        capture.grantAndStart()

        val second = handler.handleStart(startPayload("other-stream"), SESSION, 1080, 1920)
        assertTrue(second is ScreenOutcome.Failure)
        // Handing the picture to a second request would make the owner's consent a lie.
        assertEquals(ProtocolError.RATE_LIMITED, (second as ScreenOutcome.Failure).error)
    }

    @Test
    fun `answers a message about a foreign stream with NOT_FOUND`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)
        handler.handleStart(startPayload(), SESSION, 1080, 1920)
        capture.grantAndStart()

        val foreign = JSONObject().put("streamId", "someone-else").put("sequence", 0L)
        assertEquals(
            ProtocolError.NOT_FOUND,
            (handler.handleAck(foreign) as ScreenOutcome.Failure).error,
        )
        assertEquals(
            ProtocolError.NOT_FOUND,
            (handler.handleKeyframeRequest(foreign) as ScreenOutcome.Failure).error,
        )
        // The running stream is untouched by a message that was not about it.
        assertTrue(handler.isActive)
    }

    @Test
    fun `a server stop stops the capture`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)
        handler.handleStart(startPayload(), SESSION, 1080, 1920)
        capture.grantAndStart()

        val stop = JSONObject().put("streamId", STREAM).put("reason", "client_cancelled")
        assertEquals(ScreenOutcome.Accepted, handler.handleStop(stop))
        assertEquals(listOf(ScreenStopReason.CLIENT_CANCELLED), capture.stops)
        assertFalse(handler.isActive)
    }

    @Test
    fun `a locally stopped stream tells the capture and not the server`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)
        handler.handleStart(startPayload(), SESSION, 1080, 1920)
        capture.grantAndStart()
        handler.drainReadyMessages()

        handler.stopLocally(ScreenStopReason.CONNECTION_LOST)
        assertEquals(listOf(ScreenStopReason.CONNECTION_LOST), capture.stops)
        // Nothing is queued for a socket that is the reason we are stopping.
        assertTrue(handler.drainReadyMessages().isEmpty())
        assertFalse(handler.isActive)
    }

    @Test
    fun `the capture side ending the stream is reported to the server`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)
        handler.handleStart(startPayload(), SESSION, 1080, 1920)
        capture.grantAndStart()
        handler.drainReadyMessages()

        capture.listener?.onStopped(ScreenStopReason.OWNER_STOPPED)
        val messages = handler.drainReadyMessages()
        assertEquals(listOf(MessageType.SCREEN_STOP), messages.map { it.type })
        assertEquals("owner_stopped", messages[0].payload.getString("reason"))
        assertFalse(handler.isActive)
    }

    @Test
    fun `a keyframe request reaches the encoder`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)
        handler.handleStart(startPayload(), SESSION, 1080, 1920)
        capture.grantAndStart()

        val before = capture.keyframeRequests
        handler.handleKeyframeRequest(JSONObject().put("streamId", STREAM))
        assertEquals(before + 1, capture.keyframeRequests)
    }

    @Test
    fun `a frame bigger than the protocol allows is dropped, not truncated`() {
        val capture = FakeCapture()
        val handler = ScreenRequestHandler(capture)
        handler.handleStart(startPayload(), SESSION, 1080, 1920)
        capture.grantAndStart()
        handler.drainReadyMessages()

        capture.listener?.onFrame(frame(ProtocolConstants.SCREEN_MAX_FRAME_BYTES + 1, keyFrame = true))
        assertTrue(handler.drainReadyMessages().isEmpty())
        assertTrue(capture.keyframeRequests >= 1)
    }

    @Test
    fun `refuses a start without a stream id`() {
        val handler = ScreenRequestHandler(FakeCapture())
        val outcome = handler.handleStart(JSONObject(), SESSION, 1080, 1920)
        assertEquals(ProtocolError.INVALID_MESSAGE, (outcome as ScreenOutcome.Failure).error)
    }

    @Test
    fun `refuses a start when the display size is unusable`() {
        val handler = ScreenRequestHandler(FakeCapture())
        val outcome = handler.handleStart(startPayload(), SESSION, 0, 0)
        assertEquals(ProtocolError.UNSUPPORTED, (outcome as ScreenOutcome.Failure).error)
    }
}
