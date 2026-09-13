package com.redurbabat.feedback.screen

import com.redurbabat.feedback.protocol.MessageType
import com.redurbabat.feedback.protocol.ProtocolError
import com.redurbabat.feedback.security.CryptoUtils
import org.json.JSONObject

/** One frame the agent should put on the wire. */
data class OutgoingScreenMessage(
    val type: MessageType,
    val payload: JSONObject,
)

/** Result of handling one `screen.*` request. */
sealed interface ScreenOutcome {
    data class Failure(val error: ProtocolError, val message: String) : ScreenOutcome

    /** Handled. Anything to say comes later, through [ScreenRequestHandler.drainReadyMessages]. */
    data object Accepted : ScreenOutcome
}

/**
 * What the handler captures from. [AndroidScreenCapture] is the real implementation.
 *
 * The interface exists so the protocol side - consent order, window, chunking, stop paths - is
 * unit tested against a fake, instead of being inferred from behaviour on a phone nobody here has.
 */
interface ScreenCaptureSource {
    /**
     * Asks the owner, then Android, then starts capturing.
     *
     * Must never start capturing without both answers, and must report every step back through
     * [ScreenCaptureListener] (protocol section 8.5.2).
     */
    fun requestCapture(
        streamId: String,
        settings: ScreenEncoderSettings,
        listener: ScreenCaptureListener,
    )

    /** Asks the encoder for a sync frame. */
    fun requestKeyframe()

    /** Stops the projection first, then the foreground service (protocol section 8.5.8). */
    fun stop(reason: ScreenStopReason)
}

/** How the capture source reports back. Implemented by [ScreenRequestHandler]. */
interface ScreenCaptureListener {
    fun onConsent(state: ScreenConsentState)

    fun onStarted(config: ScreenStreamConfig)

    fun onFrame(frame: EncodedScreenFrame)

    fun onStopped(reason: ScreenStopReason)
}

/**
 * Answers the `screen.*` request types for one agent connection.
 *
 * Only one stream at a time (`SCREEN_MAX_CONCURRENT_STREAMS`). A second `screen.start` while one
 * is running is refused rather than silently replacing the first: the owner consented to one
 * viewer, and quietly handing the picture to a second request would make that consent a lie.
 *
 * Every method is guarded: frames arrive on the encoder thread while the WebSocket thread drains
 * the queue, and a half-written ArrayDeque is not the kind of bug that shows up in a test.
 */
class ScreenRequestHandler(
    private val capture: ScreenCaptureSource,
    /** Called whenever there is something to send, because frames arrive without being asked for. */
    private val onMessagesReady: () -> Unit = {},
) : ScreenCaptureListener {

    private val lock = Any()
    private val pending = ArrayDeque<OutgoingScreenMessage>()
    private var window = ScreenSendWindow()

    /** Null when nothing is running. Set the moment a start is accepted, not when frames begin. */
    private var streamId: String? = null

    /**
     * The Remote Session every outgoing frame belongs to.
     *
     * Kept here because frames go out without a request to answer, so there is no envelope to
     * copy it from - and a privileged frame without a session id is refused by the server.
     */
    private var sessionId: String? = null
    private var streaming = false

    val isActive: Boolean get() = synchronized(lock) { streamId != null }

    /** The session id to stamp on the messages [drainReadyMessages] returns. */
    val activeSessionId: String? get() = synchronized(lock) { sessionId }

    /**
     * Starts the consent flow.
     *
     * The display size is passed in rather than read here, so this class stays free of Android
     * and the scaling arithmetic can be tested on its own ([ScreenEncoderPlan]).
     */
    fun handleStart(
        payload: JSONObject,
        sessionId: String?,
        displayWidth: Int,
        displayHeight: Int,
    ): ScreenOutcome {
        val id = payload.optString("streamId", "")
        val settings: ScreenEncoderSettings
        if (id.isBlank()) {
            return ScreenOutcome.Failure(ProtocolError.INVALID_MESSAGE, "streamId fehlt")
        }
        if (displayWidth <= 0 || displayHeight <= 0) {
            return ScreenOutcome.Failure(
                ProtocolError.UNSUPPORTED,
                "Die Anzeige liefert keine brauchbare Groesse",
            )
        }

        synchronized(lock) {
            if (streamId != null) {
                return ScreenOutcome.Failure(
                    ProtocolError.RATE_LIMITED,
                    "Es laeuft bereits eine Bildschirmuebertragung",
                )
            }
            val request = ScreenStartRequest(
                streamId = id,
                maxWidth = payload.optInt("maxWidth", 0),
                maxHeight = payload.optInt("maxHeight", 0),
                maxFps = payload.optInt("maxFps", 0),
                maxBitrateKbps = payload.optInt("maxBitrateKbps", 0),
            )
            settings = ScreenEncoderPlan.forDisplay(displayWidth, displayHeight, request)
            streamId = id
            this.sessionId = sessionId
            streaming = false
            window = ScreenSendWindow()
        }

        // Outside the lock: requestCapture calls straight back into onConsent, and a listener
        // that re-enters a held lock is how a deadlock gets shipped.
        capture.requestCapture(id, settings, this)
        return ScreenOutcome.Accepted
    }

    fun handleAck(payload: JSONObject): ScreenOutcome {
        val id = payload.optString("streamId", "")
        if (!payload.has("sequence")) {
            return ScreenOutcome.Failure(ProtocolError.INVALID_MESSAGE, "sequence fehlt")
        }
        val accepted = synchronized(lock) {
            if (id != streamId) {
                return ScreenOutcome.Failure(ProtocolError.NOT_FOUND, "Unbekannter Strom")
            }
            window.recordAck(payload.optLong("sequence", -1L))
        }
        if (!accepted) {
            // An ack for a frame that was never produced is not loss, it is a counterpart
            // inventing frames. Nothing good follows from continuing.
            stopLocally(ScreenStopReason.ENCODER_ERROR)
            return ScreenOutcome.Failure(
                ProtocolError.INVALID_MESSAGE,
                "Bestaetigung passt zu keinem gesendeten Frame",
            )
        }
        onMessagesReady()
        return ScreenOutcome.Accepted
    }

    fun handleKeyframeRequest(payload: JSONObject): ScreenOutcome {
        val id = payload.optString("streamId", "")
        synchronized(lock) {
            if (id != streamId) {
                return ScreenOutcome.Failure(ProtocolError.NOT_FOUND, "Unbekannter Strom")
            }
        }
        capture.requestKeyframe()
        return ScreenOutcome.Accepted
    }

    fun handleStop(payload: JSONObject): ScreenOutcome {
        val id = payload.optString("streamId", "")
        synchronized(lock) {
            if (id != streamId) {
                return ScreenOutcome.Failure(ProtocolError.NOT_FOUND, "Unbekannter Strom")
            }
        }
        val reason = ScreenStopReason.fromWire(payload.optString("reason", ""))
            ?: ScreenStopReason.CLIENT_CANCELLED
        stopLocally(reason)
        return ScreenOutcome.Accepted
    }

    /**
     * Stops capturing without telling the server.
     *
     * Used when the reason came from this side: a withdrawn capability, a lost connection, a
     * revoked device. The projection stops first; whether a frame can still reach the server is
     * a separate question and must never delay it.
     */
    fun stopLocally(reason: ScreenStopReason) {
        synchronized(lock) {
            if (streamId == null) {
                return
            }
            clear()
        }
        capture.stop(reason)
    }

    /** Everything queued and allowed to go out right now. */
    fun drainReadyMessages(): List<OutgoingScreenMessage> {
        synchronized(lock) {
            if (pending.isEmpty()) {
                return emptyList()
            }
            val ready = ArrayList<OutgoingScreenMessage>(pending.size)
            while (pending.isNotEmpty()) {
                ready.add(pending.removeFirst())
            }
            return ready
        }
    }

    // ------------------------------------------------------- capture callbacks ---

    override fun onConsent(state: ScreenConsentState) {
        synchronized(lock) {
            val id = streamId ?: return
            enqueue(
                MessageType.SCREEN_CONSENT,
                JSONObject().put("streamId", id).put("state", state.wireName),
            )
            if (state == ScreenConsentState.DECLINED) {
                // The owner said no. Nothing is running, so there is nothing to stop - but the
                // handler must forget the stream, or the next request would look like a duplicate.
                clear()
            }
        }
        onMessagesReady()
    }

    override fun onStarted(config: ScreenStreamConfig) {
        synchronized(lock) {
            val id = streamId ?: return
            streaming = true
            enqueue(
                MessageType.SCREEN_STARTED,
                JSONObject()
                    .put("streamId", id)
                    .put("width", config.width)
                    .put("height", config.height)
                    .put("codec", config.codec)
                    .put("fps", config.fps)
                    .put("config", config.configBase64),
            )
        }
        onMessagesReady()
    }

    override fun onFrame(frame: EncodedScreenFrame) {
        val needsKeyframe = synchronized(lock) {
            val id = streamId
            if (id == null || !streaming) {
                return
            }
            // Checked before the window, not after: a frame that can never be sent must not
            // occupy a slot the server will never acknowledge, or three of them in a row would
            // stall the stream for good.
            val chunks = ScreenFrameChunker.split(frame.data)
            if (chunks == null) {
                window.skip()
                return@synchronized true
            }
            val admission = window.offer()
            if (admission is FrameAdmission.Drop) {
                // The viewer is behind. Throwing the frame away is the correct answer; asking
                // the encoder for a keyframe is what keeps the picture from staying broken.
                return@synchronized true
            }
            val sequence = (admission as FrameAdmission.Send).sequence
            for (chunk in chunks) {
                enqueue(
                    MessageType.SCREEN_FRAME,
                    JSONObject()
                        .put("streamId", id)
                        .put("sequence", sequence)
                        .put("chunkIndex", chunk.chunkIndex)
                        .put("chunkCount", chunk.chunkCount)
                        .put("keyFrame", frame.keyFrame)
                        .put("timestampUs", frame.timestampUs)
                        .put("data", CryptoUtils.Base64.encode(chunk.data)),
                )
            }
            false
        }
        if (needsKeyframe) {
            capture.requestKeyframe()
            return
        }
        onMessagesReady()
    }

    override fun onStopped(reason: ScreenStopReason) {
        synchronized(lock) {
            val id = streamId ?: return
            enqueue(
                MessageType.SCREEN_STOP,
                JSONObject().put("streamId", id).put("reason", reason.wireName),
            )
            clear()
        }
        onMessagesReady()
    }

    private fun enqueue(type: MessageType, payload: JSONObject) {
        pending.addLast(OutgoingScreenMessage(type, payload))
    }

    private fun clear() {
        streamId = null
        sessionId = null
        streaming = false
    }
}
