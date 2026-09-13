package com.redurbabat.feedback.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the limits from protocol/PROTOCOL.md section 11 against silent drift. */
class ProtocolConstantsTest {

    @Test
    fun limitsMatchTheProtocolDocument() {
        assertEquals(1, ProtocolConstants.PROTOCOL_VERSION)
        assertEquals(300_000L, ProtocolConstants.PAIRING_TTL_MS)
        assertEquals(5, ProtocolConstants.PAIRING_MAX_LOOKUP_ATTEMPTS)
        assertEquals(120_000L, ProtocolConstants.CLOCK_SKEW_MS)
        assertEquals(900_000L, ProtocolConstants.NONCE_RETENTION_MS)
        assertEquals(60_000L, ProtocolConstants.REMOTE_SESSION_TTL_MS)
        assertEquals(30_000L, ProtocolConstants.HEARTBEAT_INTERVAL_MS)
        assertEquals(3, ProtocolConstants.HEARTBEAT_MISS_LIMIT)
        assertEquals(65_536, ProtocolConstants.MAX_FRAME_BYTES)
        assertEquals(16_384, ProtocolConstants.MAX_JSON_BODY_BYTES)
        assertEquals(64, ProtocolConstants.DEVICE_NAME_MAX)
        assertEquals(512, ProtocolConstants.PUBLIC_KEY_MAX_BASE64)
    }

    @Test
    fun filesLimitsMatchTheProtocolDocument() {
        assertEquals(300_000L, ProtocolConstants.FILES_SESSION_TTL_MS)
        assertEquals(32_768, ProtocolConstants.FILE_CHUNK_BYTES)
        assertEquals(4, ProtocolConstants.FILE_TRANSFER_WINDOW)
        assertEquals(2, ProtocolConstants.FILE_MAX_CONCURRENT_TRANSFERS)
        assertEquals(30_000L, ProtocolConstants.FILE_TRANSFER_IDLE_TIMEOUT_MS)
        assertEquals(268_435_456L, ProtocolConstants.FILE_MAX_DOWNLOAD_BYTES)
        assertEquals(200, ProtocolConstants.FILE_MAX_LIST_ENTRIES)
        assertEquals(255, ProtocolConstants.FILE_NAME_MAX)
    }

    /**
     * A files session has to outlive a single request, otherwise browsing would expire mid-listing.
     */
    @Test
    fun aFilesSessionOutlivesAOneShotSession() {
        assertTrue(
            ProtocolConstants.FILES_SESSION_TTL_MS > ProtocolConstants.REMOTE_SESSION_TTL_MS,
        )
    }

    @Test
    fun capabilityListMatchesSectionEightOne() {
        assertEquals(
            listOf(
                "system.info",
                "files.read",
                "media.photos.read",
                "media.videos.read",
                "screen.view",
                "screen.control",
                "clipboard.read",
                "clipboard.write",
            ),
            ProtocolConstants.CAPABILITY_WIRE_NAMES,
        )
    }
}
