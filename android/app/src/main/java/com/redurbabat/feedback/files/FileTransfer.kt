package com.redurbabat.feedback.files

import com.redurbabat.feedback.protocol.ProtocolConstants

/** Why a transfer ended early (protocol/PROTOCOL.md section 8.3.7). */
enum class FileTransferCancelReason(val wireName: String) {
    CLIENT_CANCELLED("client_cancelled"),
    SESSION_EXPIRED("session_expired"),
    CAPABILITY_REVOKED("capability_revoked"),
    DEVICE_REVOKED("device_revoked"),
    TOO_LARGE("too_large"),
    READ_ERROR("read_error"),
    TIMEOUT("timeout"),
    ;

    companion object {
        fun fromWire(value: String?): FileTransferCancelReason? =
            entries.firstOrNull { it.wireName == value }
    }
}

/**
 * Send window for one download.
 *
 * The device may keep at most [ProtocolConstants.FILE_TRANSFER_WINDOW] chunks in flight and then
 * waits for `files.download.ack`. Without this a fast reader would queue an entire file into the
 * socket buffer, which is exactly the unbounded memory use the protocol forbids.
 *
 * Pure and android-free so the arithmetic is unit tested rather than inferred from behaviour.
 */
class FileTransferWindow(
    val windowSize: Int = ProtocolConstants.FILE_TRANSFER_WINDOW,
) {
    init {
        require(windowSize > 0) { "window size must be positive" }
    }

    private var nextSequence = 0L
    private var highestAcked = -1L

    /** Sequence numbers sent but not yet acknowledged. */
    val inFlight: Int get() = (nextSequence - 1L - highestAcked).toInt().coerceAtLeast(0)

    val nextSequenceNumber: Long get() = nextSequence

    fun canSend(): Boolean = inFlight < windowSize

    /** Reserves the next sequence number. Callers must check [canSend] first. */
    fun recordSent(): Long {
        check(canSend()) { "transfer window is full" }
        val sequence = nextSequence
        nextSequence += 1L
        return sequence
    }

    /**
     * Acknowledges up to and including [sequence]. Returns false for an ack of something never
     * sent or for a stale ack, which the caller treats as a protocol violation.
     */
    fun recordAck(sequence: Long): Boolean {
        if (sequence < 0L || sequence >= nextSequence) {
            return false
        }
        if (sequence <= highestAcked) {
            return false
        }
        highestAcked = sequence
        return true
    }
}

/**
 * Receive-side sequence check for one download.
 *
 * Enforces section 8.3.6: sequences start at 0, rise by exactly 1, never repeat, and nothing
 * follows the chunk marked `last`.
 */
class FileChunkSequence {
    private var expected = 0L
    private var finished = false

    val expectedSequence: Long get() = expected
    val isFinished: Boolean get() = finished

    /** Returns false when the chunk violates the sequence rules; the caller then aborts. */
    fun accept(sequence: Long, last: Boolean): Boolean {
        if (finished) {
            return false
        }
        if (sequence != expected) {
            return false
        }
        expected += 1L
        if (last) {
            finished = true
        }
        return true
    }
}

/** Size rules for one download, evaluated before a transfer starts. */
object FileTransferPolicy {

    /**
     * The device enforces its own ceiling as well as the server's, and the smaller value wins: a
     * server that asks for more than the device is willing to stream does not get it.
     */
    fun effectiveMaxDownloadBytes(serverMaxBytes: Long?): Long {
        val deviceMax = ProtocolConstants.FILE_MAX_DOWNLOAD_BYTES
        if (serverMaxBytes == null || serverMaxBytes <= 0L) {
            return deviceMax
        }
        return minOf(deviceMax, serverMaxBytes)
    }

    fun isDownloadable(sizeBytes: Long?, serverMaxBytes: Long? = null): Boolean {
        if (sizeBytes == null || sizeBytes < 0L) {
            return false
        }
        return sizeBytes <= effectiveMaxDownloadBytes(serverMaxBytes)
    }

    /** Number of chunks a file of [sizeBytes] produces, for progress reporting. */
    fun chunkCount(sizeBytes: Long): Long {
        require(sizeBytes >= 0L) { "size must not be negative" }
        if (sizeBytes == 0L) {
            return 1L
        }
        val chunk = ProtocolConstants.FILE_CHUNK_BYTES.toLong()
        return (sizeBytes + chunk - 1L) / chunk
    }
}
