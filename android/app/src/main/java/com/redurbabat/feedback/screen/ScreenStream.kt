package com.redurbabat.feedback.screen

import com.redurbabat.feedback.protocol.ProtocolConstants

/** Why a screen stream ended (protocol/PROTOCOL.md section 8.5.8). */
enum class ScreenStopReason(val wireName: String) {
    OWNER_STOPPED("owner_stopped"),
    CLIENT_CANCELLED("client_cancelled"),
    SESSION_EXPIRED("session_expired"),
    CAPABILITY_REVOKED("capability_revoked"),
    DEVICE_REVOKED("device_revoked"),
    CONSENT_DECLINED("consent_declined"),
    CONSENT_TIMEOUT("consent_timeout"),
    PROJECTION_STOPPED("projection_stopped"),
    ENCODER_ERROR("encoder_error"),
    TIMEOUT("timeout"),
    CONNECTION_LOST("connection_lost"),
    ;

    companion object {
        fun fromWire(value: String?): ScreenStopReason? =
            entries.firstOrNull { it.wireName == value }
    }
}

/** What the owner did with the request (protocol section 8.5.4). */
enum class ScreenConsentState(val wireName: String) {
    PENDING("pending"),
    GRANTED("granted"),
    DECLINED("declined"),
}

/** The caps the server asked for in `screen.start`. */
data class ScreenStartRequest(
    val streamId: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int,
    val maxBitrateKbps: Int,
)

/** What the encoder is actually told to produce. */
data class ScreenEncoderSettings(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
)

/** What the encoder reports once it is running (protocol section 8.5.5). */
data class ScreenStreamConfig(
    val width: Int,
    val height: Int,
    val codec: String,
    val fps: Int,
    /** base64 SPS/PPS in Annex-B. */
    val configBase64: String,
)

/** One encoded frame, straight out of MediaCodec. */
class EncodedScreenFrame(
    val data: ByteArray,
    val keyFrame: Boolean,
    val timestampUs: Long,
)

/**
 * Turns a display size into encoder settings the protocol allows.
 *
 * Pure and android-free, because this is the kind of arithmetic that looks obviously right and
 * then produces a 1081 pixel wide encoder input that MediaCodec refuses on some chipsets.
 */
object ScreenEncoderPlan {

    /**
     * H.264 wants macroblock aligned dimensions. Sixteen is the safe multiple: some encoders
     * accept even numbers, some do not, and the ones that do not fail at runtime on a device we
     * do not have.
     */
    private const val ALIGNMENT = 16

    /** Bits per pixel per frame. Screen content is mostly flat, so this is deliberately low. */
    private const val BITS_PER_PIXEL = 0.07

    private const val MIN_BITRATE_KBPS = 300

    fun forDisplay(
        displayWidth: Int,
        displayHeight: Int,
        request: ScreenStartRequest,
    ): ScreenEncoderSettings {
        require(displayWidth > 0 && displayHeight > 0) { "display size must be positive" }

        // The device never trusts the server to have applied its own limits: the smaller of the
        // two wins, in both directions, exactly as with FILE_MAX_DOWNLOAD_BYTES.
        val maxWidth = clampCap(request.maxWidth)
        val maxHeight = clampCap(request.maxHeight)

        val scale = minOf(
            1.0,
            maxWidth.toDouble() / displayWidth.toDouble(),
            maxHeight.toDouble() / displayHeight.toDouble(),
        )
        val width = align((displayWidth * scale).toInt())
        val height = align((displayHeight * scale).toInt())

        val fps = request.maxFps.coerceIn(1, ProtocolConstants.SCREEN_MAX_FPS)
        val bitrateCap = if (request.maxBitrateKbps <= 0) {
            ProtocolConstants.SCREEN_MAX_BITRATE_KBPS
        } else {
            minOf(request.maxBitrateKbps, ProtocolConstants.SCREEN_MAX_BITRATE_KBPS)
        }
        val wanted = (width.toDouble() * height.toDouble() * fps.toDouble() * BITS_PER_PIXEL / 1000.0).toInt()
        val bitrateKbps = wanted.coerceIn(MIN_BITRATE_KBPS, maxOf(MIN_BITRATE_KBPS, bitrateCap))

        return ScreenEncoderSettings(width = width, height = height, fps = fps, bitrateKbps = bitrateKbps)
    }

    private fun clampCap(requested: Int): Int {
        if (requested <= 0) {
            return ProtocolConstants.SCREEN_MAX_DIMENSION
        }
        return minOf(requested, ProtocolConstants.SCREEN_MAX_DIMENSION)
    }

    private fun align(value: Int): Int {
        val rounded = (value / ALIGNMENT) * ALIGNMENT
        return if (rounded < ALIGNMENT) ALIGNMENT else rounded
    }
}

/** What the send window decided about one encoded frame. */
sealed interface FrameAdmission {
    /** The frame may go out under this sequence number. */
    data class Send(val sequence: Long) : FrameAdmission

    /** The window is full. The frame is thrown away; its sequence number is spent anyway. */
    data class Drop(val sequence: Long) : FrameAdmission
}

/**
 * Send window for one screen stream.
 *
 * The difference to [com.redurbabat.feedback.files.FileTransferWindow] is the whole point: a file
 * chunk *waits* for room, a frame is *thrown away*. Buffering frames would keep the picture
 * complete and make it steadily later, which is the opposite of what a live view is for.
 *
 * A dropped frame still consumes its sequence number. The gap is what tells the server that
 * something was lost (protocol section 8.5.6) instead of hiding it behind a renumbering.
 */
class ScreenSendWindow(
    val windowSize: Int = ProtocolConstants.SCREEN_FRAME_WINDOW,
) {
    init {
        require(windowSize > 0) { "window size must be positive" }
    }

    private var nextSequence = 0L
    private val unacked = ArrayDeque<Long>()

    val inFlight: Int get() = unacked.size

    val nextSequenceNumber: Long get() = nextSequence

    fun canSend(): Boolean = unacked.size < windowSize

    /** Takes the next sequence number and says whether the frame fits through the window. */
    fun offer(): FrameAdmission {
        val sequence = nextSequence
        nextSequence += 1L
        if (unacked.size >= windowSize) {
            return FrameAdmission.Drop(sequence)
        }
        unacked.addLast(sequence)
        return FrameAdmission.Send(sequence)
    }

    /**
     * Spends a sequence number without occupying the window.
     *
     * For a frame that was never put on the wire at all - one too large to chunk. Occupying a
     * slot for it would wait forever for an acknowledgement the server has no reason to send.
     */
    fun skip(): Long {
        val sequence = nextSequence
        nextSequence += 1L
        return sequence
    }

    /**
     * Acknowledges everything up to and including [sequence].
     *
     * Cumulative on purpose: the server also acknowledges sequences it dropped, so an ack may
     * name a frame this side never sent. Returns false for an ack of something not yet produced,
     * which is a broken counterpart rather than loss.
     */
    fun recordAck(sequence: Long): Boolean {
        if (sequence < 0L || sequence >= nextSequence) {
            return false
        }
        var removed = false
        while (unacked.isNotEmpty() && unacked.first() <= sequence) {
            unacked.removeFirst()
            removed = true
        }
        return removed
    }
}

/** One piece of one frame, ready to go into a `screen.frame` payload. */
class ScreenFrameChunk(
    val chunkIndex: Int,
    val chunkCount: Int,
    val data: ByteArray,
)

/**
 * Splits an encoded frame into protocol sized pieces.
 *
 * A delta frame usually fits into one chunk; a keyframe almost never does. Both go through the
 * same path so the rare case is not the untested one.
 */
object ScreenFrameChunker {

    fun chunkCount(size: Int): Int {
        require(size >= 0) { "size must not be negative" }
        if (size == 0) {
            return 1
        }
        val chunk = ProtocolConstants.SCREEN_CHUNK_BYTES
        return (size + chunk - 1) / chunk
    }

    /** Null when the frame is larger than the protocol allows; the caller then drops it. */
    fun split(data: ByteArray): List<ScreenFrameChunk>? {
        if (data.size > ProtocolConstants.SCREEN_MAX_FRAME_BYTES) {
            return null
        }
        val count = chunkCount(data.size)
        val chunkSize = ProtocolConstants.SCREEN_CHUNK_BYTES
        val chunks = ArrayList<ScreenFrameChunk>(count)
        for (index in 0 until count) {
            val from = index * chunkSize
            val to = minOf(from + chunkSize, data.size)
            chunks.add(
                ScreenFrameChunk(
                    chunkIndex = index,
                    chunkCount = count,
                    data = data.copyOfRange(from, to),
                ),
            )
        }
        return chunks
    }
}
