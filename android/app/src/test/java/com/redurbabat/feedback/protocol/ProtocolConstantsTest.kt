package com.redurbabat.feedback.protocol

import org.junit.Assert.assertEquals
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
