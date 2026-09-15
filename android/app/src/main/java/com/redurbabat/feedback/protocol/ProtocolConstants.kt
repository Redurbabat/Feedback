package com.redurbabat.feedback.protocol

/** Limits from protocol/PROTOCOL.md section 11 plus the v1 capability list. */
object ProtocolConstants {
    const val PROTOCOL_VERSION = 1

    const val PAIRING_TTL_MS = 300_000L
    const val PAIRING_MAX_LOOKUP_ATTEMPTS = 5
    const val CLOCK_SKEW_MS = 120_000L

    /**
     * Control-Center-seitig, hier nur gespiegelt, damit Abschnitt 11 an einer Stelle vollstaendig
     * ist - wie PAIRING_MAX_LOOKUP_ATTEMPTS, das auf dem Geraet ebenfalls nichts tut.
     */
    const val CONTROL_ELEVATION_TTL_MS = 300_000L
    /**
     * Wie lange ein Geraete-Token ohne Rotation gilt (THREAT_MODEL 4.13). Jede Rotation setzt das
     * Fenster neu; ein Geraet, das sich verbindet, laeuft nie hinein.
     */
    const val DEVICE_TOKEN_TTL_MS = 7_776_000_000L

    /** Ab diesem Alter tauscht die naechste Verbindung den Token aus. */
    const val DEVICE_TOKEN_ROTATE_AFTER_MS = 604_800_000L

    const val NONCE_RETENTION_MS = 900_000L
    const val REMOTE_SESSION_TTL_MS = 60_000L
    const val HEARTBEAT_INTERVAL_MS = 30_000L
    const val HEARTBEAT_MISS_LIMIT = 3
    const val MAX_FRAME_BYTES = 65_536
    const val MAX_JSON_BODY_BYTES = 16_384
    const val DEVICE_NAME_MAX = 64
    const val PUBLIC_KEY_MAX_BASE64 = 512

    /** `files.read` limits from section 11. */
    const val FILES_SESSION_TTL_MS = 300_000L
    const val FILE_CHUNK_BYTES = 32_768
    const val FILE_TRANSFER_WINDOW = 4
    const val FILE_MAX_CONCURRENT_TRANSFERS = 2
    const val FILE_TRANSFER_IDLE_TIMEOUT_MS = 30_000L
    const val FILE_MAX_DOWNLOAD_BYTES = 268_435_456L
    const val FILE_MAX_LIST_ENTRIES = 200
    const val FILE_NAME_MAX = 255

    /**
     * `screen.view` limits from section 11.
     *
     * [SCREEN_FRAME_WINDOW] is smaller than [FILE_TRANSFER_WINDOW] on purpose: an unacked
     * file chunk costs memory, an unacked frame costs latency. Once the window is full the
     * device drops frames instead of queueing them - a queue only moves the picture further
     * into the past.
     */
    const val SCREEN_SESSION_TTL_MS = 600_000L
    const val SCREEN_CONSENT_TIMEOUT_MS = 60_000L
    const val SCREEN_CHUNK_BYTES = 32_768
    const val SCREEN_FRAME_WINDOW = 3
    const val SCREEN_MAX_FRAME_BYTES = 1_048_576
    const val SCREEN_MAX_CONCURRENT_STREAMS = 1
    const val SCREEN_STREAM_IDLE_TIMEOUT_MS = 15_000L
    const val SCREEN_MAX_DIMENSION = 1_280
    const val SCREEN_MAX_FPS = 15
    const val SCREEN_MAX_BITRATE_KBPS = 2_500

    /** Secret sizes from section 5.2 and the nonce size from the pairing/start example. */
    const val PAIRING_NONCE_BYTES = 18
    const val TICKET_BYTES = 32
    const val DEVICE_SECRET_BYTES = 32
    const val DISPLAY_CODE_DIGITS = 6

    const val PLATFORM_ANDROID = "android"

    /** Capability list v1 in wire order. */
    val CAPABILITY_WIRE_NAMES: List<String> = Capability.toWireList(Capability.values().toList())
}
