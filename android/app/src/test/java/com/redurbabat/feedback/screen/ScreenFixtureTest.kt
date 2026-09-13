package com.redurbabat.feedback.screen

import com.redurbabat.feedback.protocol.MessageType
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android half of the cross-implementation contract test.
 *
 * `protocol/fixtures/screen-v1.json` is read here and by
 * `server/test/protocol/fixtures.test.ts`. Neither side can rename a field, widen a limit or
 * invent a state without the other one failing - which is the one thing two independent
 * implementations of the same protocol cannot notice on their own.
 *
 * Server-to-device fixtures are fed to the real handler; device-to-server fixtures are compared
 * key for key against what the real handler produces.
 */
class ScreenFixtureTest {

    private class RecordingCapture : ScreenCaptureSource {
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
    }

    private val fixtures: JSONObject by lazy { JSONObject(fixtureFile().readText()) }

    /**
     * Walks up from the working directory.
     *
     * Gradle runs unit tests from the module directory, but that is a convention rather than a
     * guarantee, and a test that silently fails to find its own input would be worse than one
     * that cannot start.
     */
    private fun fixtureFile(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "protocol/fixtures/screen-v1.json")
            if (candidate.isFile) {
                return candidate
            }
            directory = directory.parentFile
        }
        throw AssertionError("protocol/fixtures/screen-v1.json nicht gefunden")
    }

    private fun entries(group: String, type: String, direction: String): List<JSONObject> {
        val array: JSONArray = fixtures.getJSONArray(group)
        val result = ArrayList<JSONObject>()
        for (index in 0 until array.length()) {
            val entry = array.getJSONObject(index)
            if (entry.getString("type") == type && entry.getString("direction") == direction) {
                result.add(entry.getJSONObject("payload"))
            }
        }
        return result
    }

    private fun only(group: String, type: String, direction: String): JSONObject {
        val found = entries(group, type, direction)
        assertEquals("genau ein Fixture fuer $type erwartet", 1, found.size)
        return found[0]
    }

    private fun keysOf(payload: JSONObject): Set<String> {
        val keys = LinkedHashSet<String>()
        for (key in payload.keys()) {
            keys.add(key)
        }
        return keys
    }

    @Test
    fun `the fixture file is actually there and not empty`() {
        // Without this, every assertion below would pass on an empty file.
        assertTrue(fixtures.getJSONArray("accepted").length() > 5)
        assertTrue(fixtures.getJSONArray("rejected").length() > 3)
    }

    @Test
    fun `the canonical screen start is accepted and produces the protocol limits`() {
        val capture = RecordingCapture()
        val handler = ScreenRequestHandler(capture)
        val start = only("accepted", "screen.start", "server-to-device")

        assertEquals(
            ScreenOutcome.Accepted,
            handler.handleStart(start, "5f1e2d3c-0000-4000-8000-000000000001", 1080, 1920),
        )
        val settings = requireNotNull(capture.settings)
        assertTrue(settings.width <= start.getInt("maxWidth"))
        assertTrue(settings.height <= start.getInt("maxHeight"))
        assertEquals(start.getInt("maxFps"), settings.fps)
        assertTrue(settings.bitrateKbps <= start.getInt("maxBitrateKbps"))
    }

    @Test
    fun `the canonical ack and keyframe request are understood`() {
        val capture = RecordingCapture()
        val handler = ScreenRequestHandler(capture)
        val start = only("accepted", "screen.start", "server-to-device")
        val streamId = start.getString("streamId")
        handler.handleStart(start, "session", 1080, 1920)
        capture.listener?.onConsent(ScreenConsentState.GRANTED)
        capture.listener?.onStarted(
            ScreenStreamConfig(720, 1280, "avc1.42E01E", 15, "AAAAAWc="),
        )
        // Ten frames and no acks, so sequence 9 - the one the fixture names - has been
        // produced. The window drops most of them, which is the point: a dropped frame still
        // spends its number, so the server may well acknowledge one this side never sent.
        repeat(10) { index ->
            capture.listener?.onFrame(
                EncodedScreenFrame(ByteArray(64), index == 0, index.toLong()),
            )
        }
        handler.drainReadyMessages()

        val ack = only("accepted", "screen.frame.ack", "server-to-device")
        assertEquals(streamId, ack.getString("streamId"))
        assertEquals(ScreenOutcome.Accepted, handler.handleAck(ack))

        val before = capture.keyframeRequests
        val keyframe = only("accepted", "screen.keyframe.request", "server-to-device")
        assertEquals(ScreenOutcome.Accepted, handler.handleKeyframeRequest(keyframe))
        assertEquals(before + 1, capture.keyframeRequests)
    }

    @Test
    fun `the canonical stop from the viewer stops the capture`() {
        val capture = RecordingCapture()
        val handler = ScreenRequestHandler(capture)
        val start = only("accepted", "screen.start", "server-to-device")
        handler.handleStart(start, "session", 1080, 1920)
        capture.listener?.onConsent(ScreenConsentState.GRANTED)

        val stop = only("accepted", "screen.stop", "server-to-device")
        assertEquals(ScreenOutcome.Accepted, handler.handleStop(stop))
        assertEquals(listOf(ScreenStopReason.CLIENT_CANCELLED), capture.stops)
    }

    @Test
    fun `what the handler sends carries exactly the fields the fixtures declare`() {
        val capture = RecordingCapture()
        val handler = ScreenRequestHandler(capture)
        val start = only("accepted", "screen.start", "server-to-device")
        handler.handleStart(start, "session", 1080, 1920)

        capture.listener?.onConsent(ScreenConsentState.PENDING)
        capture.listener?.onStarted(
            ScreenStreamConfig(720, 1280, "avc1.42E01E", 15, "AAAAAWc="),
        )
        capture.listener?.onFrame(EncodedScreenFrame(ByteArray(64) { 3 }, true, 0L))
        capture.listener?.onStopped(ScreenStopReason.OWNER_STOPPED)

        val produced = handler.drainReadyMessages()
        assertKeysMatch(produced, MessageType.SCREEN_CONSENT, "screen.consent")
        assertKeysMatch(produced, MessageType.SCREEN_STARTED, "screen.started")
        assertKeysMatch(produced, MessageType.SCREEN_FRAME, "screen.frame")
        assertKeysMatch(produced, MessageType.SCREEN_STOP, "screen.stop")
    }

    private fun assertKeysMatch(
        produced: List<OutgoingScreenMessage>,
        type: MessageType,
        wireName: String,
    ) {
        val message = produced.firstOrNull { it.type == type }
        assertNotNull("Der Handler hat kein $wireName erzeugt", message)
        val expected = keysOf(entries("accepted", wireName, "device-to-server").first())
        val actual = keysOf(message!!.payload)
        // Field for field: a rename on either side stops here instead of on a phone.
        assertEquals(wireName, expected, actual)
    }

    @Test
    fun `a consent state nobody defined is not one this side can produce`() {
        val rejected = entries("rejected", "screen.consent", "device-to-server")
        assertTrue(rejected.isNotEmpty())
        val states = ScreenConsentState.entries.map { it.wireName }.toSet()
        for (payload in rejected) {
            assertTrue(
                "Das Fixture nennt einen Zustand, den dieses Geraet senden koennte",
                payload.getString("state") !in states,
            )
        }
    }

    @Test
    fun `every stop reason in the fixtures exists on this side`() {
        for (group in listOf("accepted")) {
            for (payload in entries(group, "screen.stop", "device-to-server") +
                entries(group, "screen.stop", "server-to-device")) {
                val reason = payload.getString("reason")
                assertNotNull(
                    "Unbekannter Stop-Grund im Fixture: $reason",
                    ScreenStopReason.fromWire(reason),
                )
            }
        }
    }
}
